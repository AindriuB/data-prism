package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationDecision;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityRefusedException;
import io.github.aindriub.dataprism.security.ToolInvocation;
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

/**
 * Case authorisation is a decision the deployer makes: a {@link CaseAuthorizer} bean, or
 * {@code dataprism.security-policy.case-authorisation=token-trusted} said in so many words. Neither refuses startup.
 */
class CaseAuthorisationConfigurationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller("principal-1", "client-1",
            Set.of("investigator"), "investigation", "case-1", Instant.parse("2026-01-01T12:00:00Z"));
    private static final String SETTING = "dataprism.security-policy.case-authorisation";

    /** The allowlist is decided, so only the case authoriser is under test. */
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(SecurityWiringTest.valid())
            .withPropertyValues("dataprism.security-policy.client-allowlist=unrestricted");

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
    void no_bean_and_no_property_is_refused() {
        context.withUserConfiguration(SecurityWiringTest.Base.class).run(result -> {
            SecurityWiringTest.assertRefusal(result.getStartupFailure(), "MISSING_CASE_AUTHORIZER");
            assertThat(result.getStartupFailure()).hasMessageContaining(SETTING)
                    .hasMessageContaining("CaseAuthorizer");
        });
    }

    @Test
    void the_property_alone_starts_with_one_warning_and_trusts_the_token() {
        context.withPropertyValues(SETTING + "=token-trusted")
                .withUserConfiguration(SecurityWiringTest.Base.class).run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasBean("dataPrismCaseAuthorizer");
                    ScopeResolver resolver = result.getBean(ScopeResolver.class);
                    AuthorizationDecision decision = decision(result.getBean(AuthorizationService.class));
                    assertThat(resolver.resolve(CALLER, decision, CLOCK)).isNotNull();
                    assertThat(warnings()).filteredOn(w -> w.contains("case-authorisation")).hasSize(1)
                            .allSatisfy(w -> assertThat(w).doesNotContain("case-1").doesNotContain("principal-1"));
                });
    }

    @Test
    void an_application_bean_alone_starts_and_the_default_resolver_holds_it() {
        context.withUserConfiguration(SecurityWiringTest.Base.class, Refusing.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean("dataPrismCaseAuthorizer");
            assertThat(result.getBean(ScopeResolver.class)
                    .usesCaseAuthorizer(result.getBean(CaseAuthorizer.class))).isTrue();
            assertThat(warnings()).noneMatch(w -> w.contains("case-authorisation"));
        });
    }

    @Test
    void an_application_authorizer_that_says_no_produces_case_not_authorised() {
        context.withUserConfiguration(SecurityWiringTest.Base.class, Refusing.class).run(result -> {
            ScopeResolver resolver = result.getBean(ScopeResolver.class);
            AuthorizationDecision decision = decision(result.getBean(AuthorizationService.class));
            assertThatThrownBy(() -> resolver.resolve(CALLER, decision, CLOCK))
                    .isInstanceOfSatisfying(SecurityRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo(ScopeResolver.CASE_NOT_AUTHORISED));
        });
    }

    @Test
    void the_property_and_an_application_bean_conflict() {
        context.withPropertyValues(SETTING + "=token-trusted")
                .withUserConfiguration(SecurityWiringTest.Base.class, Refusing.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CASE_AUTHORISATION_CONFLICT"));
    }

    @Test
    void the_property_and_an_application_bean_conflict_under_lazy_initialisation() {
        context.withPropertyValues(SETTING + "=token-trusted", "spring.main.lazy-initialization=true")
                .withUserConfiguration(SecurityWiringTest.Base.class, Refusing.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CASE_AUTHORISATION_CONFLICT"));
    }

    @Test
    void an_unsupported_value_is_refused() {
        context.withPropertyValues(SETTING + "=trusted")
                .withUserConfiguration(SecurityWiringTest.Base.class)
                .run(result -> {
                    SecurityWiringTest.assertRefusal(result.getStartupFailure(), "UNSUPPORTED_CASE_AUTHORISATION");
                    assertThat(result.getStartupFailure()).hasMessageNotContaining("=trusted");
                });
    }

    @Test
    void an_unsupported_value_is_refused_even_with_an_application_bean() {
        context.withPropertyValues(SETTING + "=false")
                .withUserConfiguration(SecurityWiringTest.Base.class, Refusing.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "UNSUPPORTED_CASE_AUTHORISATION"));
    }

    private static AuthorizationDecision decision(AuthorizationService service) {
        return service.authorize(CALLER, new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
    }

    private List<String> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void the_property_and_a_hidden_type_primary_authorizer_conflict() {
        context.withPropertyValues(SETTING + "=token-trusted")
                .withUserConfiguration(SecurityWiringTest.Base.class, HiddenPrimary.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CASE_AUTHORISATION_CONFLICT"));
    }

    @Test
    void the_property_and_a_null_typed_factory_bean_authorizer_conflict() {
        context.withPropertyValues(SETTING + "=token-trusted")
                .withUserConfiguration(SecurityWiringTest.Base.class, NullTypeFactory.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CASE_AUTHORISATION_CONFLICT"));
    }

    @Configuration(proxyBeanMethods = false)
    static class HiddenPrimary {
        @Bean @org.springframework.context.annotation.Primary Object hidden() {
            return (CaseAuthorizer) request -> true;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NullTypeFactory {
        @Bean org.springframework.beans.factory.FactoryBean<Object> hiddenFactory() {
            return new org.springframework.beans.factory.FactoryBean<>() {
                public Object getObject() { return (CaseAuthorizer) request -> true; }
                public Class<?> getObjectType() { return null; }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Refusing {
        @Bean CaseAuthorizer refusing() { return request -> false; }
    }
}
