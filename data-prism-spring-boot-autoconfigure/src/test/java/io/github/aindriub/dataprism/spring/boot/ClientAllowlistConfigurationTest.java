package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationDecision;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client allowlist is a decision the deployer makes: a {@link ClientAllowlist} bean, a list in
 * {@code dataprism.security-policy.allowed-clients}, or {@code client-allowlist=unrestricted} said in so many words.
 */
class ClientAllowlistConfigurationTest {

    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller("principal-1", "client-1",
            Set.of("investigator"), "investigation", "case-1", Instant.parse("2026-01-01T12:00:00Z"));
    private static final String LIST = "dataprism.security-policy.allowed-clients";
    private static final String UNRESTRICTED = "dataprism.security-policy.client-allowlist";

    /** The case authorisation is decided, so only the allowlist is under test. */
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(SecurityWiringTest.valid())
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
    void nothing_set_and_no_bean_is_refused() {
        context.withUserConfiguration(SecurityWiringTest.Base.class).run(result -> {
            SecurityWiringTest.assertRefusal(result.getStartupFailure(), "MISSING_CLIENT_ALLOWLIST");
            assertThat(result.getStartupFailure()).hasMessageContaining(LIST).hasMessageContaining(UNRESTRICTED)
                    .hasMessageContaining("ClientAllowlist");
        });
    }

    @Test
    void a_list_alone_starts_and_a_client_off_the_list_is_denied() {
        context.withPropertyValues(LIST + "[0]=client-2", LIST + "[1]=client-3")
                .withUserConfiguration(SecurityWiringTest.Base.class).run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasBean("dataPrismClientAllowlist");
                    AuthorizationDecision decision = decision(result.getBean(AuthorizationService.class));
                    assertThat(decision.allowed()).isFalse();
                    assertThat(decision.denialCode()).isEqualTo("CLIENT_NOT_ALLOWED");
                    assertThat(warnings()).noneMatch(w -> w.contains("client-allowlist"));
                });
    }

    @Test
    void a_list_naming_the_client_admits_it() {
        context.withPropertyValues(LIST + "=client-1,client-2")
                .withUserConfiguration(SecurityWiringTest.Base.class).run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(decision(result.getBean(AuthorizationService.class)).allowed()).isTrue();
                });
    }

    @Test
    void unrestricted_alone_starts_with_one_warning_naming_the_setting() {
        context.withPropertyValues(UNRESTRICTED + "=unrestricted")
                .withUserConfiguration(SecurityWiringTest.Base.class).run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(decision(result.getBean(AuthorizationService.class)).allowed()).isTrue();
                    assertThat(warnings()).filteredOn(w -> w.contains("client-allowlist")).hasSize(1);
                });
    }

    @Test
    void an_application_bean_alone_starts_and_the_default_service_holds_it() {
        context.withUserConfiguration(SecurityWiringTest.Base.class, Restrictive.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean("dataPrismClientAllowlist");
            assertThat(result.getBean(AuthorizationService.class)
                    .usesClientAllowlist(result.getBean(ClientAllowlist.class))).isTrue();
            assertThat(decision(result.getBean(AuthorizationService.class)).denialCode())
                    .isEqualTo("CLIENT_NOT_ALLOWED");
        });
    }

    @Test
    void both_properties_conflict() {
        context.withPropertyValues(LIST + "[0]=client-1", UNRESTRICTED + "=unrestricted")
                .withUserConfiguration(SecurityWiringTest.Base.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CLIENT_ALLOWLIST_CONFLICT"));
    }

    @Test
    void a_list_and_an_application_bean_conflict() {
        context.withPropertyValues(LIST + "[0]=client-1")
                .withUserConfiguration(SecurityWiringTest.Base.class, Restrictive.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CLIENT_ALLOWLIST_CONFLICT"));
    }

    @Test
    void unrestricted_and_an_application_bean_conflict() {
        context.withPropertyValues(UNRESTRICTED + "=unrestricted")
                .withUserConfiguration(SecurityWiringTest.Base.class, Restrictive.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CLIENT_ALLOWLIST_CONFLICT"));
    }

    @Test
    void a_conflict_is_refused_under_lazy_initialisation() {
        context.withPropertyValues(UNRESTRICTED + "=unrestricted", "spring.main.lazy-initialization=true")
                .withUserConfiguration(SecurityWiringTest.Base.class, Restrictive.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CLIENT_ALLOWLIST_CONFLICT"));
    }

    @Test
    void an_empty_list_is_refused() {
        context.withPropertyValues(LIST + "=")
                .withUserConfiguration(SecurityWiringTest.Base.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "EMPTY_CLIENT_ALLOWLIST"));
    }

    @Test
    void a_blank_entry_is_refused() {
        context.withPropertyValues(LIST + "[0]=client-1", LIST + "[1]= ")
                .withUserConfiguration(SecurityWiringTest.Base.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "BLANK_ALLOWED_CLIENT"));
    }

    @Test
    void an_unsupported_value_is_refused() {
        context.withPropertyValues(UNRESTRICTED + "=any")
                .withUserConfiguration(SecurityWiringTest.Base.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "UNSUPPORTED_CLIENT_ALLOWLIST"));
    }

    private static AuthorizationDecision decision(AuthorizationService service) {
        return service.authorize(CALLER,
                new io.github.aindriub.dataprism.security.ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT"));
    }

    private List<String> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void a_child_inheriting_the_parents_list_starts_and_allows_only_that_client() {
        context.withPropertyValues(LIST + "[0]=client-1").withUserConfiguration(SecurityWiringTest.Base.class)
                .run(parent -> {
                    assertThat(parent).hasNotFailed();
                    context.withPropertyValues(LIST + "[0]=client-1").withParent(parent)
                            .withUserConfiguration(SecurityWiringTest.Base.class).run(child -> {
                                assertThat(child).hasNotFailed();
                                assertThat(decision(child.getBean(AuthorizationService.class)).allowed()).isTrue();
                                assertThat(child.getBean(AuthorizationService.class)
                                        .authorize(new AuthenticatedCaller("p", "client-2", Set.of("investigator"),
                                                "investigation", "case-1", Instant.parse("2026-01-01T12:00:00Z")),
                                                new io.github.aindriub.dataprism.security.ToolInvocation(
                                                        "get_entity_context", "GET_ENTITY_CONTEXT"))
                                        .denialCode()).isEqualTo("CLIENT_NOT_ALLOWED");
                            });
                });
    }

    @Test
    void a_child_with_a_different_list_than_the_parents_is_refused() {
        context.withPropertyValues(LIST + "[0]=client-1").withUserConfiguration(SecurityWiringTest.Base.class)
                .run(parent -> {
                    assertThat(parent).hasNotFailed();
                    context.withPropertyValues(LIST + "[0]=client-2").withParent(parent)
                            .withUserConfiguration(SecurityWiringTest.Base.class)
                            .run(child -> SecurityWiringTest.assertRefusal(child.getStartupFailure(),
                                    "CLIENT_ALLOWLIST_BYPASSED"));
                });
    }

    @Test
    void a_list_and_a_hidden_type_primary_allowlist_conflict() {
        context.withPropertyValues(LIST + "[0]=only-this-client")
                .withUserConfiguration(SecurityWiringTest.Base.class, HiddenPrimary.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CLIENT_ALLOWLIST_CONFLICT"));
    }

    @Test
    void unrestricted_and_a_null_typed_factory_bean_allowlist_conflict() {
        context.withPropertyValues(UNRESTRICTED + "=unrestricted")
                .withUserConfiguration(SecurityWiringTest.Base.class, NullTypeFactory.class)
                .run(result -> SecurityWiringTest.assertRefusal(result.getStartupFailure(),
                        "CLIENT_ALLOWLIST_CONFLICT"));
    }

    @Configuration(proxyBeanMethods = false)
    static class HiddenPrimary {
        @Bean @org.springframework.context.annotation.Primary Object hidden() {
            return ClientAllowlist.unrestricted();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NullTypeFactory {
        @Bean org.springframework.beans.factory.FactoryBean<Object> hiddenFactory() {
            return new org.springframework.beans.factory.FactoryBean<>() {
                public Object getObject() { return ClientAllowlist.unrestricted(); }
                public Class<?> getObjectType() { return null; }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Restrictive {
        @Bean ClientAllowlist allowlist() { return ClientAllowlist.of(Set.of("other-client")); }
    }
}
