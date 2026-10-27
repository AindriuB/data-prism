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
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
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

/**
 * A deployer's own CaseAuthorizer and ClientAllowlist beans are used, not silently replaced. Both come through
 * Spring's single injection, so none, many or a non-candidate is Spring's to refuse; what is checked here is the
 * outcome.
 */
class SecurityWiringTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller("principal-1", "client-1",
            Set.of("investigator"), "investigation", "case-1", Instant.parse("2026-01-01T12:00:00Z"));

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(valid());

    /** The allowlist decision is made, so a test about the case authoriser is not refused for the other. */
    private final WebApplicationContextRunner forCase = context
            .withPropertyValues("dataprism.security-policy.client-allowlist=unrestricted");
    /** The case decision is made, so a test about the allowlist is not refused for the other. */
    private final WebApplicationContextRunner forAllowlist = context
            .withPropertyValues("dataprism.security-policy.case-authorisation=token-trusted");

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
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class).run(result -> {
            assertThat(result).hasNotFailed();
            ScopeResolver resolver = result.getBean(ScopeResolver.class);
            AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                    .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
            assertThat(decision.allowed()).isTrue();
            assertThatThrownBy(() -> resolver.resolve(CALLER, decision, CLOCK))
                    .isInstanceOfSatisfying(SecurityRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo(ScopeResolver.CASE_NOT_AUTHORISED));
            assertThat(warnings()).noneMatch(w -> w.contains("case-authorisation"));
        });
    }

    @Test
    void a_user_client_allowlist_is_used() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class).run(result -> {
            assertThat(result).hasNotFailed();
            AuthorizationDecision decision = result.getBean(AuthorizationService.class)
                    .authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denialCode()).isEqualTo("CLIENT_NOT_ALLOWED");
        });
    }

    // ---- each refusal also fires under lazy initialisation ----

    @Test
    void a_missing_case_authorizer_is_refused_under_global_lazy_initialisation() {
        forCase.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @Test
    void a_missing_client_allowlist_is_refused_under_global_lazy_initialisation() {
        forAllowlist.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CLIENT_ALLOWLIST"));
    }

    @Test
    void a_bypassing_scope_resolver_is_refused_under_global_lazy_initialisation() {
        forCase.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void a_bypassing_authorization_service_is_refused_under_global_lazy_initialisation() {
        forAllowlist.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RestrictiveAllowlist.class, OwnAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void a_bypassing_scope_resolver_is_refused_under_boots_lazy_initialisation() {
        forCase.withInitializer(SecurityWiringTest::applyBootLazyInitialisation)
                .withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void a_scope_resolver_that_skips_the_default_is_refused_when_no_authorizer_exists_under_lazy_initialisation() {
        forCase.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @Test
    void an_authorization_service_that_skips_the_default_is_refused_when_no_allowlist_exists_under_lazy_initialisation() {
        forAllowlist.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, OwnAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CLIENT_ALLOWLIST"));
    }

    private static void applyBootLazyInitialisation(org.springframework.context.ConfigurableApplicationContext ctx) {
        ctx.addBeanFactoryPostProcessor(new org.springframework.boot.LazyInitializationBeanFactoryPostProcessor());
    }

    // ---- outcome check ----

    @Test
    void an_application_scope_resolver_without_the_injected_authorizer_is_refused() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void an_application_authorization_service_without_the_injected_allowlist_is_refused() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class, OwnAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void an_application_scope_resolver_built_with_the_injected_authorizer_starts() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, WiredScopeResolver.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(ScopeResolver.class).usesCaseAuthorizer(
                            result.getBean(CaseAuthorizer.class))).isTrue();
                });
    }

    @Test
    void an_application_authorization_service_built_with_the_injected_allowlist_starts() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class, WiredAuthorizationService.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(AuthorizationService.class).usesClientAllowlist(
                            result.getBean(ClientAllowlist.class))).isTrue();
                });
    }

    @Test
    void an_application_scope_resolver_that_skips_the_default_is_refused_when_no_authorizer_exists() {
        forCase.withUserConfiguration(Base.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @Test
    void an_application_authorization_service_that_skips_the_default_is_refused_when_no_allowlist_exists() {
        forAllowlist.withUserConfiguration(Base.class, OwnAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CLIENT_ALLOWLIST"));
    }

    @Test
    void a_scope_resolver_built_with_a_different_authorizer_while_a_bean_exists_is_refused() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, OtherAuthorizerScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void our_own_scope_resolver_replaced_after_the_fact_by_a_token_trusted_one_is_refused() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class,
                TokenTrustedResolverSubstitution.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void our_own_authorization_service_replaced_after_the_fact_by_an_unrestricted_one_is_refused() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class,
                UnrestrictedServiceSubstitution.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void a_same_named_scope_resolver_while_a_case_authorizer_exists_refuses_startup() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, SameNamedScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void a_factory_bean_scope_resolver_while_a_case_authorizer_exists_refuses_startup() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, FactoryScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void a_same_named_authorization_service_while_a_client_allowlist_exists_refuses_startup() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class, SameNamedAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    @Test
    void a_factory_bean_authorization_service_while_a_client_allowlist_exists_refuses_startup() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class, FactoryAuthorizationService.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
    }

    // ---- single injection: cardinality, candidates, scope ----

    @Test
    void two_case_authorizers_fail_with_springs_own_error() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, SecondCaseAuthorizer.class)
                .run(result -> assertThat(rootCause(result.getStartupFailure()))
                        .isInstanceOf(NoUniqueBeanDefinitionException.class)
                        .hasMessageContaining("refusing").hasMessageContaining("secondRefusing"));
    }

    @Test
    void two_client_allowlists_fail_with_springs_own_error() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class, SecondAllowlist.class)
                .run(result -> assertThat(rootCause(result.getStartupFailure()))
                        .isInstanceOf(NoUniqueBeanDefinitionException.class)
                        .hasMessageContaining("allowlist").hasMessageContaining("secondAllowlist"));
    }

    @Test
    void two_case_authorizers_fail_with_springs_own_error_under_lazy_initialisation() {
        forCase.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, SecondCaseAuthorizer.class)
                .run(result -> assertThat(rootCause(result.getStartupFailure()))
                        .isInstanceOf(NoUniqueBeanDefinitionException.class));
    }

    @Test
    void two_client_allowlists_fail_with_springs_own_error_under_lazy_initialisation() {
        forAllowlist.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(SecurityWiringTest::applyLazyInitialisation)
                .withUserConfiguration(Base.class, RestrictiveAllowlist.class, SecondAllowlist.class)
                .run(result -> assertThat(rootCause(result.getStartupFailure()))
                        .isInstanceOf(NoUniqueBeanDefinitionException.class));
    }

    @Test
    void two_case_authorizers_with_one_primary_start_and_every_resolver_holds_the_primary() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, PrimaryCaseAuthorizer.class,
                WiredScopeResolver.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    CaseAuthorizer primary = result.getBean("primaryAuthorizer", CaseAuthorizer.class);
                    result.getBeansOfType(ScopeResolver.class).values()
                            .forEach(resolver -> assertThat(resolver.usesCaseAuthorizer(primary)).isTrue());
                });
    }

    @Test
    void two_client_allowlists_with_one_primary_start_and_every_service_holds_the_primary() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class, PrimaryAllowlist.class,
                WiredAuthorizationService.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    ClientAllowlist primary = result.getBean("primaryAllowlist", ClientAllowlist.class);
                    result.getBeansOfType(AuthorizationService.class).values()
                            .forEach(service -> assertThat(service.usesClientAllowlist(primary)).isTrue());
                });
    }

    @Test
    void a_case_authorizer_that_is_not_an_autowire_candidate_counts_as_none() {
        forCase.withUserConfiguration(Base.class, NonCandidateAuthorizer.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @Test
    void a_case_authorizer_that_is_not_a_default_candidate_counts_as_none() {
        forCase.withUserConfiguration(Base.class, NonDefaultCandidateAuthorizer.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER"));
    }

    @Test
    void a_client_allowlist_that_is_not_an_autowire_candidate_counts_as_none() {
        forAllowlist.withUserConfiguration(Base.class, NonCandidateAllowlist.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CLIENT_ALLOWLIST"));
    }

    @Test
    void a_client_allowlist_that_is_not_a_default_candidate_counts_as_none() {
        forAllowlist.withUserConfiguration(Base.class, NonDefaultCandidateAllowlist.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "MISSING_CLIENT_ALLOWLIST"));
    }

    @Test
    void a_prototype_case_authorizer_is_refused_with_the_singleton_message() {
        forCase.withUserConfiguration(Base.class, PrototypeCaseAuthorizer.class).run(result -> {
            assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED");
            assertThat(result.getStartupFailure()).hasMessageContaining("singleton");
        });
    }

    @Test
    void a_prototype_client_allowlist_is_refused_with_the_singleton_message() {
        forAllowlist.withUserConfiguration(Base.class, PrototypeAllowlist.class).run(result -> {
            assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED");
            assertThat(result.getStartupFailure()).hasMessageContaining("singleton");
        });
    }

    @Test
    void a_target_class_scoped_proxy_case_authorizer_starts() {
        forCase.withUserConfiguration(Base.class, TargetClassScopedAuthorizer.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void an_interfaces_scoped_proxy_case_authorizer_starts() {
        forCase.withUserConfiguration(Base.class, InterfacesScopedAuthorizer.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void a_scoped_proxy_case_authorizer_still_refuses_a_bypassing_resolver() {
        forCase.withUserConfiguration(Base.class, TargetClassScopedAuthorizer.class, OwnScopeResolver.class)
                .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
    }

    @Test
    void an_interfaces_scoped_proxy_allowlist_fails_startup() {
        forAllowlist.withUserConfiguration(Base.class, InterfacesScopedAllowlist.class)
                .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void a_bean_merely_named_like_a_scoped_target_is_an_ordinary_bean() {
        forCase.withUserConfiguration(Base.class, FakeScopedTargetAuthorizer.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(ScopeResolver.class).usesCaseAuthorizer(
                            result.getBean("scopedTarget.extra", CaseAuthorizer.class))).isTrue();
                });
    }

    @Test
    void an_allowlist_merely_named_like_a_scoped_target_is_an_ordinary_bean() {
        forAllowlist.withUserConfiguration(Base.class, FakeScopedTargetAllowlist.class)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(AuthorizationService.class).usesClientAllowlist(
                            result.getBean("scopedTarget.extraAllow", ClientAllowlist.class))).isTrue();
                });
    }

    // ---- parent and child ----

    private static org.springframework.context.ConfigurableApplicationContext parentWith(Class<?>... configurations) {
        return new org.springframework.context.annotation.AnnotationConfigApplicationContext(configurations);
    }

    @Test
    void auto_configuration_in_the_child_only_uses_the_bean_in_the_parent() {
        try (var parent = parentWith(RefusingCaseAuthorizer.class)) {
            forCase.withParent(parent).withUserConfiguration(Base.class).run(result -> {
                assertThat(result).hasNotFailed();
                assertThat(result.getBean(ScopeResolver.class).usesCaseAuthorizer(
                        parent.getBean(CaseAuthorizer.class))).isTrue();
            });
        }
    }

    @Test
    void a_scope_resolver_that_bypasses_a_parent_contexts_case_authorizer_is_refused() {
        try (var parent = parentWith(RefusingCaseAuthorizer.class)) {
            forCase.withParent(parent).withUserConfiguration(Base.class, OwnScopeResolver.class)
                    .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
        }
    }

    @Test
    void a_parent_contexts_scope_resolver_that_ignores_the_childs_case_authorizer_is_refused() {
        try (var parent = parentWith(OwnScopeResolverParent.class)) {
            forCase.withParent(parent).withUserConfiguration(Base.class, RefusingCaseAuthorizer.class)
                    .run(result -> assertRefusal(result.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
        }
    }

    @Test
    void a_parent_contexts_authorization_service_that_ignores_the_childs_allowlist_is_refused() {
        try (var parent = parentWith(OwnAuthorizationServiceParent.class)) {
            forAllowlist.withParent(parent).withUserConfiguration(Base.class, RestrictiveAllowlist.class)
                    .run(result -> assertRefusal(result.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
        }
    }

    @Test
    void a_case_authorizer_in_each_of_two_auto_configured_contexts_refuses_the_child() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class).run(parent -> {
            assertThat(parent).hasNotFailed();
            forCase.withParent(parent).withUserConfiguration(Base.class, SecondCaseAuthorizer.class)
                    .run(child -> assertRefusal(child.getStartupFailure(), "CASE_AUTHORIZER_BYPASSED"));
        });
    }

    @Test
    void a_client_allowlist_in_each_of_two_auto_configured_contexts_refuses_the_child() {
        forAllowlist.withUserConfiguration(Base.class, RestrictiveAllowlist.class).run(parent -> {
            assertThat(parent).hasNotFailed();
            forAllowlist.withParent(parent).withUserConfiguration(Base.class, SecondAllowlist.class)
                    .run(child -> assertRefusal(child.getStartupFailure(), "CLIENT_ALLOWLIST_BYPASSED"));
        });
    }

    @Test
    void token_trusted_in_the_parent_and_a_bean_only_in_the_child_is_a_conflict_in_the_child() {
        forCase.withPropertyValues("dataprism.security-policy.case-authorisation=token-trusted")
                .withUserConfiguration(Base.class).run(parent -> {
                    assertThat(parent).hasNotFailed();
                    forCase.withParent(parent).withUserConfiguration(Base.class, RefusingCaseAuthorizer.class)
                            .run(child -> assertRefusal(child.getStartupFailure(), "CASE_AUTHORISATION_CONFLICT"));
                });
    }

    // ---- unchanged behaviour ----

    @Test
    void a_throwing_case_authorizer_is_logged_by_class_name_only() {
        String sentinel = "sentinel-case-and-principal";
        Logger resolverLog = (Logger) LoggerFactory.getLogger(ScopeResolver.class);
        ListAppender<ILoggingEvent> resolverAppender = new ListAppender<>();
        resolverAppender.start();
        resolverLog.addAppender(resolverAppender);
        try {
            forCase.withUserConfiguration(Base.class, ThrowingCaseAuthorizer.class).run(result -> {
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
    void an_application_bean_shadowing_the_bypass_check_refuses_startup() {
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, ShadowingBypassCheck.class)
                .run(result -> {
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
        forCase.withUserConfiguration(Base.class, RefusingCaseAuthorizer.class, ProxyingPostProcessor.class)
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

    // ---- fixtures ----

    @Configuration(proxyBeanMethods = false)
    static class TokenTrustedResolverSubstitution {
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor substituteResolver() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ScopeResolver && name.equals("dataPrismScopeResolver")) {
                        return new OwnScopeResolver().ownScopeResolver(
                                io.github.aindriub.dataprism.core.model.PseudonymisationVersion.HMAC_SHA256_V1);
                    }
                    return bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnrestrictedServiceSubstitution {
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor substituteService() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof AuthorizationService && name.equals("dataPrismAuthorizationService")) {
                        return new AuthorizationService(
                                new io.github.aindriub.dataprism.security.SecurityPolicy(Set.of("investigation"),
                                        java.util.Map.of()), "DEFAULT",
                                io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION,
                                ClientAllowlist.unrestricted());
                    }
                    return bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateAuthorizer {
        @Bean(defaultCandidate = false) CaseAuthorizer hiddenFromUnqualified() { return request -> false; }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateAllowlist {
        @Bean(defaultCandidate = false) ClientAllowlist hiddenAllowlist() { return ClientAllowlist.of(Set.of("x")); }
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeScopedTargetAuthorizer {
        @Bean("scopedTarget.extra") CaseAuthorizer extra() { return request -> false; }
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeScopedTargetAllowlist {
        @Bean("scopedTarget.extraAllow") ClientAllowlist extraAllow() { return ClientAllowlist.of(Set.of("x")); }
    }

    @Configuration(proxyBeanMethods = false)
    static class InterfacesScopedAllowlist {
        @Bean @org.springframework.context.annotation.Scope(value = "request",
                proxyMode = org.springframework.context.annotation.ScopedProxyMode.INTERFACES)
        ClientAllowlist scopedAllowlist() { return ClientAllowlist.of(Set.of("x")); }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonCandidateAuthorizer {
        @Bean(autowireCandidate = false) CaseAuthorizer hidden() { return request -> false; }
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

    @Configuration(proxyBeanMethods = false)
    static class PrimaryCaseAuthorizer {
        @Bean @org.springframework.context.annotation.Primary CaseAuthorizer primaryAuthorizer() { return request -> false; }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryAllowlist {
        @Bean @org.springframework.context.annotation.Primary ClientAllowlist primaryAllowlist() {
            return ClientAllowlist.of(Set.of("primary-client"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonCandidateAllowlist {
        @Bean(autowireCandidate = false) ClientAllowlist hiddenAllowlist2() { return ClientAllowlist.of(Set.of("x")); }
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

    static void assertRefusal(Throwable failure, String code) {
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
