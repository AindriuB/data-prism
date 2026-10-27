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
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
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
    void a_release_without_a_case_authorizer_is_refused_under_global_lazy_initialisation() {
        withVersion("0.8.0").withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @Test
    void a_bypassing_scope_resolver_is_refused_under_global_lazy_initialisation() {
        context.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void two_case_authorizers_are_refused_under_global_lazy_initialisation() {
        context.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, SecondCaseAuthorizer.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "AMBIGUOUS_CASE_AUTHORIZER"));
    }

    @Test
    void a_bypassing_authorization_service_is_refused_under_global_lazy_initialisation() {
        context.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RestrictiveAllowlist.class, OwnAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void two_client_allowlists_are_refused_under_global_lazy_initialisation() {
        context.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RestrictiveAllowlist.class, SecondAllowlist.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "AMBIGUOUS_CLIENT_ALLOWLIST"));
    }

    @Test
    void a_bypassing_scope_resolver_is_refused_under_boots_lazy_initialisation() {
        context.withInitializer(SecurityWiringTest::applyBootLazyInitialisation)
                .withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    private static void applyBootLazyInitialisation(org.springframework.context.ConfigurableApplicationContext ctx) {
        ctx.addBeanFactoryPostProcessor(new org.springframework.boot.LazyInitializationBeanFactoryPostProcessor());
    }

    private static org.springframework.context.ConfigurableApplicationContext parentWith(Class<?>... configurations) {
        org.springframework.context.annotation.AnnotationConfigApplicationContext parent =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext(configurations);
        return parent;
    }

    @Test
    void a_case_authorizer_in_the_parent_context_counts_and_satisfies_the_release_gate() {
        try (var parent = parentWith(RefusingCaseAuthorizer.class)) {
            withVersion("0.8.0").withParent(parent).withUserConfiguration(Base.class)
                    .run(result -> assertThat(result).hasNotFailed());
        }
    }

    @Test
    void a_scope_resolver_that_bypasses_a_parent_contexts_case_authorizer_is_refused() {
        try (var parent = parentWith(RefusingCaseAuthorizer.class)) {
            context.withParent(parent).withUserConfiguration(Base.class, OwnScopeResolver.class)
                    .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
        }
    }

    @Test
    void a_parent_contexts_scope_resolver_that_ignores_the_childs_case_authorizer_is_refused() {
        try (var parent = parentWith(OwnScopeResolverParent.class)) {
            context.withParent(parent).withUserConfiguration(Base.class, RefusingCaseAuthorizer.class)
                    .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
        }
    }

    @Test
    void a_parent_contexts_authorization_service_that_ignores_the_childs_allowlist_is_refused() {
        try (var parent = parentWith(OwnAuthorizationServiceParent.class)) {
            context.withParent(parent).withUserConfiguration(Base.class, RestrictiveAllowlist.class)
                    .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
        }
    }

    @Test
    void a_target_class_scoped_proxy_case_authorizer_starts_in_a_release() {
        withVersion("0.8.0").withUserConfiguration(Base.class, TargetClassScopedAuthorizer.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void an_interfaces_scoped_proxy_case_authorizer_starts_in_a_release() {
        withVersion("0.8.0").withUserConfiguration(Base.class, InterfacesScopedAuthorizer.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void a_scoped_proxy_case_authorizer_still_refuses_a_bypassing_resolver() {
        withVersion("0.8.0").withUserConfiguration(Base.class, TargetClassScopedAuthorizer.class,
                OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnScopeResolverParent {
        @Bean ScopeResolver parentScopeResolver() {
            return new ScopeResolver(io.github.aindriub.dataprism.core.model.PseudonymisationVersion.HMAC_SHA256_V1,
                    java.time.Duration.ofHours(1),
                    new io.github.aindriub.dataprism.security.PurposeValidator(Set.of("investigation")),
                    CaseAuthorizer.tokenTrusted());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnAuthorizationServiceParent {
        @Bean AuthorizationService parentAuthorizationService() {
            return new AuthorizationService(
                    new io.github.aindriub.dataprism.security.SecurityPolicy(Set.of("investigation"), java.util.Map.of()),
                    "DEFAULT", io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION,
                    ClientAllowlist.unrestricted());
        }
    }

    public static class ConcreteCaseAuthorizer implements CaseAuthorizer {
        @Override public boolean isAuthorised(io.github.aindriub.dataprism.security.CaseAccessRequest request) {
            return false;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TargetClassScopedAuthorizer {
        @Bean @org.springframework.web.context.annotation.RequestScope
        ConcreteCaseAuthorizer scopedAuthorizer() { return new ConcreteCaseAuthorizer(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class InterfacesScopedAuthorizer {
        @Bean @org.springframework.context.annotation.Scope(value = "request",
                proxyMode = org.springframework.context.annotation.ScopedProxyMode.INTERFACES)
        CaseAuthorizer scopedAuthorizer() { return new ConcreteCaseAuthorizer(); }
    }

    @Test
    void a_case_authorizer_in_each_of_parent_and_child_is_ambiguous() {
        try (var parent = parentWith(RefusingCaseAuthorizer.class)) {
            context.withParent(parent).withUserConfiguration(Base.class, SecondCaseAuthorizer.class)
                    .run(result -> assertRefusal(result.getStartupFailure(), "AMBIGUOUS_CASE_AUTHORIZER"));
        }
    }

    @Test
    void a_prototype_case_authorizer_is_refused() {
        context.withUserConfiguration(Base.class, PrototypeCaseAuthorizer.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "NON_SINGLETON_CASE_AUTHORIZER"));
    }

    @Test
    void a_prototype_client_allowlist_is_refused() {
        context.withUserConfiguration(Base.class, PrototypeAllowlist.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "NON_SINGLETON_CLIENT_ALLOWLIST"));
    }

    @Test
    void a_version_resource_present_more_than_once_is_unknown_and_so_forbidden() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dp-version");
        try {
            java.nio.file.Path meta = java.nio.file.Files.createDirectories(dir.resolve("META-INF/data-prism"));
            java.nio.file.Files.writeString(meta.resolve("version.properties"), "version=0.0.1-SNAPSHOT\n");
            try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {dir.toUri().toURL()},
                    SecurityWiring.class.getClassLoader())) {
                assertThat(SecurityWiring.embeddedVersion(SecurityWiring.class.getClassLoader())).isNotNull();
                assertThat(SecurityWiring.embeddedVersion(loader)).isNull();
            }
        } finally {
            try (var walk = java.nio.file.Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrototypeCaseAuthorizer {
        @Bean @org.springframework.context.annotation.Scope("prototype")
        CaseAuthorizer prototypeAuthorizer() { return request -> false; }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrototypeAllowlist {
        @Bean @org.springframework.context.annotation.Scope("prototype")
        ClientAllowlist prototypeAllowlist() { return ClientAllowlist.of(Set.of("other-client")); }
    }

    /**
     * Marks every definition lazy that has not said otherwise. Boot's own lazy-initialisation post-processor
     * happens to leave SmartInitializingSingleton beans alone; this one does not, so the guard must opt out
     * itself rather than rely on that.
     */
    private static void applyLazyInitialisation(org.springframework.context.ConfigurableApplicationContext ctx) {
        ctx.addBeanFactoryPostProcessor(factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
                if (factory.getBeanDefinition(name) instanceof org.springframework.beans.factory.support.AbstractBeanDefinition definition
                        && definition.getLazyInit() == null) {
                    definition.setLazyInit(true);
                }
            }
        });
    }

    @Test
    void two_case_authorizers_refuse_startup() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, SecondCaseAuthorizer.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "AMBIGUOUS_CASE_AUTHORIZER"));
    }

    @Test
    void two_client_allowlists_refuse_startup() {
        context.withUserConfiguration(Base.class, RestrictiveAllowlist.class, SecondAllowlist.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "AMBIGUOUS_CLIENT_ALLOWLIST"));
    }

    @Test
    void two_case_authorizers_refuse_startup_even_when_a_custom_resolver_uses_one() {
        withVersion("0.8.0").withUserConfiguration(Base.class, RefusingCaseAuthorizer.class,
                        SecondCaseAuthorizer.class, WiredToFirstScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "AMBIGUOUS_CASE_AUTHORIZER"));
    }

    @Test
    void two_client_allowlists_refuse_startup_even_when_a_custom_service_uses_one() {
        withVersion("0.8.0").withUserConfiguration(Base.class, RefusingCaseAuthorizer.class,
                        RestrictiveAllowlist.class, SecondAllowlist.class, WiredToFirstAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "AMBIGUOUS_CLIENT_ALLOWLIST"));
    }

    @Configuration(proxyBeanMethods = false)
    static class WiredToFirstScopeResolver {
        @Bean ScopeResolver firstScopeResolver(io.github.aindriub.dataprism.core.model.PseudonymisationVersion version,
                @org.springframework.beans.factory.annotation.Qualifier("refusing") CaseAuthorizer authorizer) {
            return new ScopeResolver(version, java.time.Duration.ofHours(1),
                    new io.github.aindriub.dataprism.security.PurposeValidator(Set.of("investigation")), authorizer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WiredToFirstAuthorizationService {
        @Bean AuthorizationService firstAuthorizationService(io.github.aindriub.dataprism.security.SecurityPolicy policy,
                DataPrismProperties properties,
                @org.springframework.beans.factory.annotation.Qualifier("allowlist") ClientAllowlist allowlist) {
            return new AuthorizationService(policy, properties.getPrivacy().getProfile(),
                    io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION, allowlist);
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"0.8.0", "0.8.0-RC1", "1.0.0"})
    void the_fallback_is_refused_at_runtime_in_a_release(String version) {
        withVersion(version).withUserConfiguration(Base.class).run(result -> {
            assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER");
            assertThat(result.getStartupFailure())
                    .hasMessageContaining("a CaseAuthorizer bean is required from 0.8.0");
            if (!version.equals("0.8.0")) {
                assertThat(result.getStartupFailure()).hasMessageNotContaining(version);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.8.0-SNAPSHOT", "0.7.1"})
    void the_fallback_starts_with_a_warning_before_a_release(String version) {
        withVersion(version).withUserConfiguration(Base.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(warnings()).filteredOn(w -> w.contains("CaseAuthorizer")).hasSize(1);
        });
    }

    @Test
    void an_unknown_version_is_refused_as_a_release() {
        context.withBean(SecurityWiring.LibraryVersion.class, () -> () -> null)
                .withUserConfiguration(Base.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "0.8", "99999999999.0.0", "0.99999999999.0", "0.8.99999999999", "1.0.0-"})
    void an_unparseable_or_oversized_version_is_forbidden(String version) {
        assertThat(SecurityWiring.fallbackForbidden(version)).isTrue();
    }

    @Test
    void the_real_embedded_version_is_the_maven_project_version() {
        String expected = System.getProperty("dataprism.expectedVersion");
        assertThat(expected).isNotBlank().doesNotContain("${");
        assertThat(SecurityWiring.embeddedVersion()).isEqualTo(expected);
    }

    @Test
    void without_injection_the_real_version_is_used_and_the_gate_matches_it() {
        boolean forbidden = SecurityWiring.fallbackForbidden(System.getProperty("dataprism.expectedVersion"));
        context.withUserConfiguration(Base.class).run(result -> {
            if (forbidden) {
                assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER");
            } else {
                assertThat(result).hasNotFailed();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.8.0", "1.0.0"})
    void an_application_scope_resolver_without_a_case_authorizer_is_refused_in_a_release(String version) {
        withVersion(version).withUserConfiguration(Base.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @Test
    void an_application_scope_resolver_without_a_case_authorizer_starts_before_a_release() {
        withVersion("0.8.0-SNAPSHOT").withUserConfiguration(Base.class, OwnScopeResolver.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.8.0", "0.8.0-RC1", "1.0.0", "0.8.0-SNAPSHOT", "0.7.1"})
    void with_a_case_authorizer_bean_every_version_starts(String version) {
        withVersion(version).withUserConfiguration(Base.class, RefusingCaseAuthorizer.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    private WebApplicationContextRunner withVersion(String version) {
        return context.withBean(SecurityWiring.LibraryVersion.class, () -> () -> version);
    }

    @Test
    void replacing_the_scope_resolver_while_a_case_authorizer_exists_refuses_startup() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void a_scope_resolver_built_with_the_deployers_case_authorizer_starts_in_a_release() {
        withVersion("0.8.0").withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, WiredScopeResolver.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(ScopeResolver.class).usesCaseAuthorizer(
                            result.getBean(CaseAuthorizer.class))).isTrue();
                });
    }

    @Test
    void a_scope_resolver_built_with_a_different_authorizer_while_a_bean_exists_refuses_startup() {
        withVersion("0.8.0").withUserConfiguration(Base.class, RefusingCaseAuthorizer.class,
                        OtherAuthorizerScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void an_authorization_service_built_with_the_deployers_client_allowlist_starts() {
        withVersion("0.8.0").withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, RestrictiveAllowlist.class,
                        WiredAuthorizationService.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void an_authorization_service_built_with_a_different_allowlist_while_a_bean_exists_refuses_startup() {
        withVersion("0.8.0").withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, RestrictiveAllowlist.class,
                        OwnAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Configuration(proxyBeanMethods = false)
    static class WiredScopeResolver {
        @Bean ScopeResolver wiredScopeResolver(io.github.aindriub.dataprism.core.model.PseudonymisationVersion version,
                CaseAuthorizer authorizer) {
            return new ScopeResolver(version, java.time.Duration.ofHours(1),
                    new io.github.aindriub.dataprism.security.PurposeValidator(Set.of("investigation")), authorizer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherAuthorizerScopeResolver {
        @Bean ScopeResolver otherScopeResolver(io.github.aindriub.dataprism.core.model.PseudonymisationVersion version) {
            return new ScopeResolver(version, java.time.Duration.ofHours(1),
                    new io.github.aindriub.dataprism.security.PurposeValidator(Set.of("investigation")),
                    request -> true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WiredAuthorizationService {
        @Bean AuthorizationService wiredAuthorizationService(io.github.aindriub.dataprism.security.SecurityPolicy policy,
                DataPrismProperties properties, ClientAllowlist allowlist) {
            return new AuthorizationService(policy, properties.getPrivacy().getProfile(),
                    io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION, allowlist);
        }
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
     * end-to-end proxied-default context cannot be built; this is an accepted limitation. The unwrapping the guard
     * relies on is tested directly, and the context test below covers a proxied deployer-side CaseAuthorizer.
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
        context.withUserConfiguration(Base.class, ShadowingBypassCheck.class).run(result -> {
            assertThat(result).hasFailed();
            // bean-definition overriding is off, so the same-named bean is rejected outright
            assertThat(result.getStartupFailure()).isInstanceOf(
                    org.springframework.beans.factory.support.BeanDefinitionOverrideException.class);
            assertThat(result.getStartupFailure().getMessage()).contains("dataPrismSecurityBypassCheck");
        });
    }

    /*
     * Real proxying of ScopeResolver and AuthorizationService is impossible (both final), so a post-processor
     * that wraps the deployer-side CaseAuthorizer (an interface, so JDK-proxyable) is the closest end-to-end
     * stand-in: the wired resolver must still use it and the bypass guard must not misread it as a replacement.
     */
    @Test
    void a_proxied_case_authorizer_is_still_consulted_and_does_not_trip_the_bypass_guard() {
        context.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, ProxyingPostProcessor.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(java.lang.reflect.Proxy.isProxyClass(result.getBean(CaseAuthorizer.class).getClass()))
                            .isTrue();
                    ScopeResolver resolver = result.getBean(ScopeResolver.class);
                    AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                            .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
                    assertThatThrownBy(() -> resolver.resolve(CALLER, decision, CLOCK))
                            .isInstanceOfSatisfying(SecurityRefusedException.class,
                                    e -> assertThat(e.code()).isEqualTo(ScopeResolver.CASE_NOT_AUTHORISED));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxyingPostProcessor {
        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor proxyCaseAuthorizers() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof CaseAuthorizer ? new ProxyFactory(bean).getProxy() : bean;
                }
            };
        }
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
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
