package io.github.aindriub.dataprism.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.annotations.Chainable;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueries;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dataprism.catalog.*}, the source catalog built at startup, and the catalog tools on the HTTP transport.
 */
class CatalogConfigurationTest {

    private static final String WIRING = "io.github.aindriub.dataprism.spring.boot.CatalogWiring";

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedIntegrations.class)
            .withPropertyValues(valid());

    private Logger log;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void capture() {
        log = (Logger) LoggerFactory.getLogger(WIRING);
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
    void the_defaults_reach_the_orchestrator_options() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            OrchestrationOptions options = options(result.getBean(ContextOrchestrator.class));
            assertThat(options.unselectedSourceLimit()).isEqualTo(8);
            assertThat(options.realNames().permits("any-scope")).isFalse();
        });
    }

    @Test
    void the_limit_and_the_real_names_scopes_bind_and_reach_the_orchestrator_options() {
        context.withPropertyValues("dataprism.catalog.unselected-source-limit=3",
                "dataprism.catalog.real-names-scopes[0]=SCOPE-1", "dataprism.catalog.real-names-scopes[1]=TEAM-*")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    OrchestrationOptions options = options(result.getBean(ContextOrchestrator.class));
                    assertThat(options.unselectedSourceLimit()).isEqualTo(3);
                    assertThat(options.realNames().permits("SCOPE-1")).isTrue();
                    assertThat(options.realNames().permits("TEAM-7")).isTrue();
                    assertThat(options.realNames().permits("SCOPE-2")).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "9"})
    void a_limit_that_is_not_positive_or_above_max_sources_refuses_startup(String value) {
        context.withPropertyValues("dataprism.catalog.unselected-source-limit=" + value).run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("INVALID_CATALOG_SETTING");
            assertThat(refusal.getMessage()).contains("dataprism.catalog.unselected-source-limit")
                    .doesNotContain("-5");
        });
    }

    @Test
    void a_limit_that_is_not_an_integer_refuses_startup_with_the_stable_code() {
        context.withPropertyValues("dataprism.catalog.unselected-source-limit=abc").run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("INVALID_CATALOG_SETTING");
            assertThat(refusal.getMessage()).contains("dataprism.catalog.unselected-source-limit")
                    .doesNotContain("abc");
        });
    }

    @Test
    void a_description_that_names_the_host_of_a_property_configured_source_refuses_startup() {
        described("ok").withPropertyValues("dataprism.sources.documented.base-url=https://an-alias:8443")
                .run(result -> {
                    assertThat(result).hasFailed();
                    DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
                    assertThat(refusal.code()).isEqualTo("SOURCE_DESCRIPTION_LEAKS_NAME");
                    assertThat(refusal.getMessage()).doesNotContain("an alias description")
                            .doesNotContain("an-alias");
                });
    }

    @Test
    void a_limit_equal_to_max_sources_starts() {
        context.withPropertyValues("dataprism.catalog.unselected-source-limit=8")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mid*dle", "*lead", "two**", "  "})
    void a_malformed_scope_pattern_refuses_startup_without_naming_the_pattern(String pattern) {
        context.withPropertyValues("dataprism.catalog.real-names-scopes[0]=" + pattern).run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("INVALID_CATALOG_SETTING");
            assertThat(refusal.getMessage()).contains("dataprism.catalog.real-names-scopes")
                    .doesNotContain(pattern.trim().isEmpty() ? "\u0000" : pattern);
        });
    }

    @Test
    void the_catalog_is_built_once_and_is_the_one_the_orchestrator_and_the_queries_use() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            SourceCatalog catalog = result.getBean("dataPrismSourceCatalog", SourceCatalog.class);
            assertThat(catalog.entries()).extracting(SourceCatalog.Entry::name).containsExactly("customer");
            assertThat(result.getBeansOfType(SourceCatalog.class)).hasSize(1);
            assertThat(options(result.getBean(ContextOrchestrator.class)).catalog()).containsSame(catalog);
            assertThat(ReflectionTestUtils.getField(result.getBean(CatalogQueries.class), "catalog"))
                    .isSameAs(catalog);
        });
    }

    @Test
    void the_catalog_queries_answer_for_the_context_orchestrator_bean() {
        context.run(result -> assertThat(ReflectionTestUtils.getField(result.getBean(CatalogQueries.class),
                "orchestrator")).isSameAs(result.getBean(ContextOrchestrator.class)));
    }

    @Test
    void list_sources_and_describe_source_are_registered_on_the_http_transport() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(DataPrismMcpServer.HttpTransport.class).server().listTools())
                    .extracting(McpSchema.Tool::name)
                    .contains("get_entity_context", "compare_entity_sources", "list_sources", "describe_source");
        });
    }

    @Test
    void a_description_that_names_its_source_refuses_startup_with_the_leak_code() {
        described("leak")
                .run(result -> {
                    assertThat(result).hasFailed();
                    DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
                    assertThat(refusal.code()).isEqualTo("SOURCE_DESCRIPTION_LEAKS_NAME");
                    assertThat(refusal.getMessage()).doesNotContain("the leaked text");
                });
    }

    @Test
    void a_description_that_cannot_be_built_refuses_startup_with_the_invalid_description_code() {
        described("invalid")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(refusal(result.getStartupFailure()).code()).isEqualTo("INVALID_SOURCE_DESCRIPTION");
                });
    }

    @Test
    void a_bad_chainable_declaration_refuses_startup_with_the_chainable_code() {
        described("chainable")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(refusal(result.getStartupFailure()).code()).isEqualTo("INVALID_CHAINABLE_FIELD");
                });
    }

    @Test
    void one_info_line_counts_the_generated_fallbacks_and_one_warn_line_counts_the_odd_names() {
        described("ok")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    List<ILoggingEvent> info = appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                            .toList();
                    List<ILoggingEvent> warn = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                            .toList();
                    assertThat(info).hasSize(1);
                    // customer and Plain_Name use the fallback; documented has its own text.
                    assertThat(info.get(0).getFormattedMessage()).contains("2").contains("customer");
                    assertThat(warn).hasSize(1);
                    assertThat(warn.get(0).getFormattedMessage()).contains("1").doesNotContain("Plain_Name");
                    for (ILoggingEvent event : appender.list) {
                        assertThat(event.getFormattedMessage()).doesNotContain("an alias description")
                                .doesNotContain("a real description");
                    }
                });
    }

    @Test
    void the_catalog_the_queries_and_the_options_are_privacy_critical_with_a_competing_bean_refusal() {
        for (String bean : new String[] {"dataPrismSourceCatalog", "dataPrismCatalogQueries",
                "dataPrismOrchestrationOptions"}) {
            PrivacyExtensionPoints.BeanContract contract = PrivacyExtensionPoints.contractOf(bean);
            assertThat(contract.classification()).as(bean)
                    .isEqualTo(PrivacyExtensionPoints.Classification.PRIVACY_CRITICAL);
            assertThat(contract.guard()).as(bean).isEqualTo(PrivacyExtensionPoints.Guard.COMPETING_BEAN_REFUSAL);
        }
    }

    @Test
    void an_application_source_catalog_is_refused() {
        context.withUserConfiguration(CompetingCatalog.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(refusal(result.getStartupFailure()).code()).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void an_application_catalog_queries_bean_is_refused() {
        context.withUserConfiguration(CompetingQueries.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(refusal(result.getStartupFailure()).code()).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void an_application_orchestration_options_bean_is_refused() {
        context.withUserConfiguration(CompetingOptions.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(refusal(result.getStartupFailure()).code()).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    /** The context with the extra adapters of {@link DescribedAdapters}, each with the source entry it must have. */
    private WebApplicationContextRunner described(String mode) {
        WebApplicationContextRunner runner = context.withUserConfiguration(DescribedAdapters.class)
                .withPropertyValues("described.mode=" + mode,
                        "dataprism.sources.documented.base-url=https://documented.example",
                        "dataprism.sources.documented.timeout=2s",
                        "dataprism.sources.[Plain_Name].base-url=https://plain.example",
                        "dataprism.sources.[Plain_Name].timeout=2s");
        return mode.equals("chainable") ? runner.withPropertyValues(
                "dataprism.sources.parcels.base-url=https://parcels.example",
                "dataprism.sources.parcels.timeout=2s") : runner;
    }

    private static OrchestrationOptions options(ContextOrchestrator orchestrator) {
        return (OrchestrationOptions) ReflectionTestUtils.getField(orchestrator, "options");
    }

    private static DataPrismConfigurationException refusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e) {
                return e;
            }
        }
        throw new AssertionError("no DataPrismConfigurationException in the cause chain", failure);
    }

    private static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                "dataprism.security-policy.case-authorisation=token-trusted",
                "dataprism.security-policy.client-allowlist=unrestricted",
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
    static class ReviewedIntegrations {
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

    @LlmExposedModel
    record BadChainable(@InternalIdentifier @Chainable(keyNamespace = "Zq Bad") String ref) {
    }

    /** Adds adapters whose descriptions the catalog rules check; the mode is a test property. */
    @Configuration(proxyBeanMethods = false)
    static class DescribedAdapters {
        @Bean
        DataSourceAdapter<TestPayload> documentedAdapter(org.springframework.core.env.Environment env) {
            String mode = env.getProperty("described.mode");
            return new DataSourceAdapter<>() {
                public String sourceName() { return "documented"; }
                public Class<TestPayload> responseType() { return TestPayload.class; }
                public TestPayload fetch(DataRequest request) { return null; }
                @Override
                public SourceDescription description() {
                    return switch (mode) {
                        case "leak" -> new SourceDescription(Set.of("CUSTOMER"), "the leaked text of documented",
                                null, List.of(), null, null);
                        case "invalid" -> throw new IllegalArgumentException("the leaked text");
                        default -> new SourceDescription(Set.of("CUSTOMER"), "an alias description",
                                "a real description", List.of(), null, null);
                    };
                }
            };
        }

        @Bean
        DataSourceAdapter<TestPayload> oddlyNamedAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "Plain_Name"; }
                public Class<TestPayload> responseType() { return TestPayload.class; }
                public TestPayload fetch(DataRequest request) { return null; }
            };
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "described.mode",
                havingValue = "chainable")
        DataSourceAdapter<BadChainable> chainableAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "parcels"; }
                public Class<BadChainable> responseType() { return BadChainable.class; }
                public BadChainable fetch(DataRequest request) { return null; }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CompetingCatalog {
        @Bean
        SourceCatalog applicationCatalog() {
            return SourceCatalog.build(List.of(), java.util.Map.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CompetingQueries {
        @Bean
        CatalogQueries applicationQueries() {
            return org.mockito.Mockito.mock(CatalogQueries.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CompetingOptions {
        @Bean
        OrchestrationOptions applicationOptions() {
            return OrchestrationOptions.defaults();
        }
    }
}
