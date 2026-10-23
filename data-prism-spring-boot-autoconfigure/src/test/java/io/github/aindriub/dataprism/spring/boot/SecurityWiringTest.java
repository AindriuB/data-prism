package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
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
            assertThat(warnings()).isEmpty();
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
            assertThat(warnings()).hasSize(1);
            assertThat(warnings().get(0)).contains("CaseAuthorizer").contains("without assignment checks");
        });
    }

    private List<String> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String[] valid() {
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
}
