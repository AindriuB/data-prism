package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.RefusalCodes;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationDecision;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityRefusedException;
import io.github.aindriub.dataprism.security.ToolInvocation;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A deployer's own CaseAuthorizer and ClientAllowlist beans are used, not silently replaced. */
class SecurityWiringTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller("principal-1", "client-1",
            Set.of("investigator"), "investigation", "case-1", Instant.parse("2026-01-01T12:00:00Z"));

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(valid());

    private Logger log;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void capture() {
        log = (Logger) LoggerFactory.getLogger(SecurityWiring.class);
        appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        log.setLevel(Level.DEBUG);
    }

    @AfterEach
    void release() {
        log.detachAppender(appender);
    }

    @Test
    void a_user_case_authorizer_that_refuses_is_used() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class).run(result -> {
            assertThat(result).hasNotFailed();
            ScopeResolver resolver = result.getBean(ScopeResolver.class);
            AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                    .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
            assertThat(decision.allowed()).isTrue();
            assertThatThrownBy(() -> resolver.resolve(CALLER, decision, CLOCK))
                    .isInstanceOfSatisfying(SecurityRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo(ScopeResolver.CASE_NOT_AUTHORISED));
            assertThat(warnings()).noneMatch(w -> w.contains("CaseAuthorizer"));
        });
    }

    @Test
    void a_user_client_allowlist_is_used() {
        context.withUserConfiguration(Base.class, RestrictiveAllowlist.class).run(result -> {
            assertThat(result).hasNotFailed();
            AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                    .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denialCode()).isEqualTo("CLIENT_NOT_ALLOWED");
        });
    }

    @Test
    void two_case_authorizers_refuse_startup() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, SecondCaseAuthorizer.class)
                .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void two_client_allowlists_refuse_startup() {
        context.withUserConfiguration(Base.class, RestrictiveAllowlist.class, SecondAllowlist.class)
                .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void with_none_present_the_token_is_trusted_and_one_warning_is_logged() {
        context.withUserConfiguration(Base.class).run(result -> {
            assertThat(result).hasNotFailed();
            ScopeResolver resolver = result.getBean(ScopeResolver.class);
            AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                    .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
            assertThat(decision.allowed()).isTrue();
            assertThat(resolver.resolve(CALLER, decision, CLOCK)).isNotNull();
            assertThat(warnings()).filteredOn(w -> w.contains("CaseAuthorizer")).hasSize(1)
                    .allMatch(w -> w.contains("without assignment checks"));
        });
    }

    @Test
    void replacing_the_scope_resolver_while_a_case_authorizer_exists_refuses_startup() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void replacing_the_authorization_service_while_a_client_allowlist_exists_refuses_startup() {
        context.withUserConfiguration(Base.class, RestrictiveAllowlist.class, OwnAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void a_same_named_scope_resolver_while_a_case_authorizer_exists_refuses_startup() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, SameNamedScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void a_factory_bean_scope_resolver_while_a_case_authorizer_exists_refuses_startup() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, FactoryScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void a_same_named_authorization_service_while_a_client_allowlist_exists_refuses_startup() {
        context.withUserConfiguration(Base.class, RestrictiveAllowlist.class, SameNamedAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void a_factory_bean_authorization_service_while_a_client_allowlist_exists_refuses_startup() {
        context.withUserConfiguration(Base.class, RestrictiveAllowlist.class, FactoryAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void a_throwing_case_authorizer_is_logged_by_class_name_only() {
        String sentinel = "sentinel-case-and-principal";
        Logger resolverLog = (Logger) LoggerFactory.getLogger(ScopeResolver.class);
        ListAppender<ILoggingEvent> resolverAppender = new ListAppender<>();
        resolverAppender.start();
        resolverLog.addAppender(resolverAppender);
        try {
            context.withUserConfiguration(Base.class, ThrowingCaseAuthorizer.class).run(result -> {
                AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                        .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
                assertThatThrownBy(() -> result.getBean(ScopeResolver.class).resolve(CALLER, decision, CLOCK))
                        .isInstanceOf(SecurityRefusedException.class);
            });
            assertThat(resolverAppender.list).isNotEmpty();
            assertThat(resolverAppender.list).allSatisfy(e -> {
                assertThat(e.getFormattedMessage()).doesNotContain(sentinel).contains("IllegalStateException");
                assertThat(e.getThrowableProxy()).isNull();
            });
        } finally {
            resolverLog.detachAppender(resolverAppender);
        }
    }

    @Test
    void replacing_either_with_no_such_bean_present_is_allowed() {
        context.withUserConfiguration(Base.class, OwnScopeResolver.class, OwnAuthorizationService.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void the_unrestricted_allowlist_fallback_logs_one_fixed_warning() {
        context.withUserConfiguration(Base.class).run(result -> {
            assertThat(warnings()).contains("No ClientAllowlist bean is defined: every client is allowed. Supply a ClientAllowlist bean.");
        });
    }

    /*
     * ScopeResolver and AuthorizationService are final, so neither CGLIB nor a JDK proxy can wrap them and an
     * end-to-end proxied-default context cannot be built; the unwrapping the guard relies on is tested directly.
     */
    @Test
    void unwrapping_sees_through_one_proxy_to_the_target() {
        Runnable target = () -> { };
        assertThat(SecurityWiring.unwrap(proxyOf(target))).isSameAs(target);
    }

    @Test
    void unwrapping_sees_through_nested_proxies_to_the_target() {
        Runnable target = () -> { };
        assertThat(SecurityWiring.unwrap(proxyOf(proxyOf(proxyOf(target))))).isSameAs(target);
    }

    @Test
    void unwrapping_leaves_an_unproxied_bean_alone() {
        Runnable target = () -> { };
        assertThat(SecurityWiring.unwrap(target)).isSameAs(target);
    }

    @Test
    void a_proxy_of_a_different_object_is_not_the_target() {
        Runnable ours = () -> { };
        Runnable theirs = () -> { };
        assertThat(SecurityWiring.unwrap(proxyOf(theirs))).isNotSameAs(ours);
    }

    private static Runnable proxyOf(Runnable target) {
        return (Runnable) new ProxyFactory(target).getProxy();
    }

    @Test
    void an_application_bean_shadowing_the_bypass_check_refuses_startup() {
        context.withUserConfiguration(Base.class, ShadowingBypassCheck.class)
                .run(result -> assertThat(result).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class ShadowingBypassCheck {
        @Bean("dataPrismSecurityBypassCheck") Object shadow() { return new Object(); }
    }

    private static void assertRefusal(Throwable failure, String code) {
        assertThat(failure).isNotNull();
        Throwable t = failure;
        while (t != null && !(t instanceof DataPrismConfigurationException)) {
            t = t.getCause();
        }
        assertThat(t).isInstanceOfSatisfying(DataPrismConfigurationException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(RefusalCodes.sanitise(e.code())).isEqualTo(code);
        });
    }

    private List<String> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                "dataprism.security-policy.purposes[0]=investigation",
                "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                "dataprism.privacy.hmac-key.key-id=v1",
                "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                "dataprism.metrics.sink=micrometer",
                "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    @Configuration(proxyBeanMethods = false)
    static class Base {
        @Bean
        DataSourceAdapter<TestPayload> customerAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "customer"; }
                public Class<TestPayload> responseType() { return TestPayload.class; }
                public TestPayload fetch(DataRequest request) { return null; }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }

    @Configuration(proxyBeanMethods = false)
    static class RefusingCaseAuthorizer {
        @Bean CaseAuthorizer refusing() { return request -> false; }
    }

    @Configuration(proxyBeanMethods = false)
    static class ThrowingCaseAuthorizer {
        @Bean CaseAuthorizer throwing() {
            return request -> { throw new IllegalStateException("sentinel-case-and-principal"); };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnScopeResolver {
        @Bean ScopeResolver ownScopeResolver(io.github.aindriub.dataprism.core.model.PseudonymisationVersion version) {
            return new ScopeResolver(version, java.time.Duration.ofHours(1),
                    new io.github.aindriub.dataprism.security.PurposeValidator(Set.of("investigation")), CaseAuthorizer.tokenTrusted());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnAuthorizationService {
        @Bean AuthorizationService ownAuthorizationService(io.github.aindriub.dataprism.security.SecurityPolicy policy, DataPrismProperties properties) {
            return new AuthorizationService(policy, properties.getPrivacy().getProfile(),
                    io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondCaseAuthorizer {
        @Bean CaseAuthorizer secondRefusing() { return request -> false; }
    }

    @Configuration(proxyBeanMethods = false)
    static class RestrictiveAllowlist {
        @Bean ClientAllowlist allowlist() { return ClientAllowlist.of(Set.of("other-client")); }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondAllowlist {
        @Bean ClientAllowlist secondAllowlist() { return ClientAllowlist.of(Set.of("another-client")); }
    }

    @Configuration(proxyBeanMethods = false)
    static class SameNamedScopeResolver {
        @Bean("dataPrismScopeResolver") ScopeResolver same(io.github.aindriub.dataprism.core.model.PseudonymisationVersion version) {
            return new OwnScopeResolver().ownScopeResolver(version);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryScopeResolver {
        @Bean org.springframework.beans.factory.FactoryBean<ScopeResolver> factory(io.github.aindriub.dataprism.core.model.PseudonymisationVersion version) {
            return new org.springframework.beans.factory.FactoryBean<>() {
                public ScopeResolver getObject() { return new OwnScopeResolver().ownScopeResolver(version); }
                public Class<?> getObjectType() { return ScopeResolver.class; }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SameNamedAuthorizationService {
        @Bean("dataPrismAuthorizationService") AuthorizationService same(io.github.aindriub.dataprism.security.SecurityPolicy policy, DataPrismProperties properties) {
            return new OwnAuthorizationService().ownAuthorizationService(policy, properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryAuthorizationService {
        @Bean org.springframework.beans.factory.FactoryBean<AuthorizationService> factory(io.github.aindriub.dataprism.security.SecurityPolicy policy, DataPrismProperties properties) {
            return new org.springframework.beans.factory.FactoryBean<>() {
                public AuthorizationService getObject() { return new OwnAuthorizationService().ownAuthorizationService(policy, properties); }
                public Class<?> getObjectType() { return AuthorizationService.class; }
            };
        }
    }
}
