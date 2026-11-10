package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueries;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dataprism.links.*}: the defaults, every key and the code it refuses with, and what
 * {@code dataprism.links.enabled} switches on. Every refusal names a property and never the value.
 */
class LinksConfigurationTest {

    private static final String KEY = "dataprism.links.key-id=lk1";
    private static final String KEY_REF = "dataprism.links.keys.lk1.environment-variable=LINK_REF";

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(LinkKeyConfigurationTest.ReviewedIntegrations.class)
            .withPropertyValues(OversightConfigurationTest.base("single-node"));

    private static final String[] OPERATOR = {
            "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
            "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=dataprism.operate"};

    private WebApplicationContextRunner enabled() {
        return context.withPropertyValues(KEY, KEY_REF, "dataprism.links.enabled=true");
    }

    @Test
    void links_are_off_by_default_and_the_options_carry_neither_links_nor_follow() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            OrchestrationOptions options = result.getBean(OrchestrationOptions.class);
            assertThat(options.links()).isEmpty();
            assertThat(options.follow()).isEmpty();
            assertThat(names(result.getBean(McpSyncServer.class))).doesNotContain("follow");
        });
    }

    @Test
    void a_link_key_alone_keeps_links_off_but_gives_page_links_the_gate() {
        context.withPropertyValues(KEY, KEY_REF).run(result -> {
            assertThat(result).hasNotFailed();
            OrchestrationOptions options = result.getBean(OrchestrationOptions.class);
            assertThat(options.links()).isEmpty();
            assertThat(options.follow()).isEmpty();
            assertThat(result).hasSingleBean(LinkAttemptGate.class);
            assertThat(names(result.getBean(McpSyncServer.class))).doesNotContain("follow");
        });
    }

    @Test
    void enabling_links_serves_follow_and_the_decided_defaults_reach_the_options() {
        enabled().run(result -> {
            assertThat(result).hasNotFailed();
            OrchestrationOptions options = result.getBean(OrchestrationOptions.class);
            assertThat(options.links()).isPresent();
            assertThat(options.links().get().maxPerResponse()).isEqualTo(50);
            assertThat(options.follow()).isPresent();
            assertThat(options.follow().get().maxTargets()).isEqualTo(5);
            assertThat(options.follow().get().resolverTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(options.follow().get().budgetCost()).isEqualTo(1);
            assertThat(names(result.getBean(McpSyncServer.class))).contains("follow");
            assertThat(result).hasSingleBean(LinkAttemptGate.class).hasSingleBean(LinkAttemptLimiter.class);
            assertThat(result.getBean(LinkAttemptLimiter.class)).isInstanceOf(InMemoryLinkAttemptLimiter.class);
        });
    }

    @Test
    void the_same_gate_reaches_the_catalog_queries_and_follow() {
        enabled().run(result -> {
            assertThat(result).hasNotFailed();
            LinkAttemptGate gate = result.getBean(LinkAttemptGate.class);
            assertThat(result.getBean(OrchestrationOptions.class).follow().get().gate()).isSameAs(gate);
            assertThat(result).hasSingleBean(CatalogQueries.class);
            assertThat(pageGate(result.getBean(CatalogQueries.class))).isSameAs(gate);
        });
    }

    /** The gate inside the catalog's page links; there is no accessor, so it is read from the field. */
    private static LinkAttemptGate pageGate(CatalogQueries queries) throws ReflectiveOperationException {
        java.lang.reflect.Field field = CatalogQueries.class.getDeclaredField("links");
        field.setAccessible(true);
        CatalogQueries.PageLinks links = (CatalogQueries.PageLinks) field.get(queries);
        assertThat(links).isNotNull();
        return links.gate();
    }

    @Test
    void with_a_link_key_and_links_off_invalid_page_values_on_list_sources_lock_the_caller_out() {
        context.withPropertyValues(KEY, KEY_REF, "dataprism.links.invalid-attempt-limit=3").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(pageGate(result.getBean(CatalogQueries.class))).isSameAs(result.getBean(LinkAttemptGate.class));
            CatalogQueries queries = result.getBean(CatalogQueries.class);
            InvestigationContext caller = new InvestigationContext("investigator-1", "client-1", "CASE-1",
                    Set.of(io.github.aindriub.dataprism.core.model.Capability.EXPOSE_SOURCE_NAMES));
            PrivacyContext scope = new PrivacyContext("scope-a", PrivacyScopeType.CASE, "DEFAULT", "test",
                    Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1"));
            CatalogQueries.ListRequest invalid =
                    new CatalogQueries.ListRequest(null, null, null, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            for (int i = 0; i < 3; i++) {
                assertThat(queries.listSources(invalid, caller, scope, null, "c").refusalCode())
                        .isNotEqualTo("LINK_ATTEMPTS_EXHAUSTED");
            }
            assertThat(queries.listSources(invalid, caller, scope, null, "c").refusalCode())
                    .isEqualTo("LINK_ATTEMPTS_EXHAUSTED");
        });
    }

    @Test
    void the_settings_bind_and_reach_the_options() {
        enabled().withPropertyValues("dataprism.links.max-per-response=7", "dataprism.links.lifetime=PT20M",
                "dataprism.links.max-length=2048", "dataprism.links.invalid-attempt-limit=3",
                "dataprism.links.invalid-attempt-window=PT1M", "dataprism.links.clock-skew=PT10S",
                "dataprism.links.bind-caller=false", "dataprism.key-resolvers.max-targets=16",
                "dataprism.key-resolvers.timeout=PT5S", "dataprism.key-resolvers.budget-cost=2").run(result -> {
                    assertThat(result).hasNotFailed();
                    OrchestrationOptions options = result.getBean(OrchestrationOptions.class);
                    assertThat(options.links().get().maxPerResponse()).isEqualTo(7);
                    assertThat(options.follow().get().maxTargets()).isEqualTo(16);
                    assertThat(options.follow().get().resolverTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(options.follow().get().budgetCost()).isEqualTo(2);
                    LinkSettings settings = codecSettings(result.getBean(LinkCodec.class));
                    assertThat(settings.lifetime()).contains(Duration.ofMinutes(20));
                    assertThat(settings.maxLength()).isEqualTo(2048);
                    assertThat(settings.clockSkew()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(settings.bindCaller()).isFalse();
                    // The served follow schema bounds the link by the configured length, not the default.
                    McpSchema.Tool follow = result.getBean(McpSyncServer.class).listTools().stream()
                            .filter(tool -> tool.name().equals("follow")).findFirst().orElseThrow();
                    assertThat(follow.inputSchema().toString()).contains("maxLength=2048")
                            .doesNotContain("maxLength=1024");
                });
    }

    private static LinkSettings codecSettings(LinkCodec codec) throws ReflectiveOperationException {
        java.lang.reflect.Field field = LinkCodec.class.getDeclaredField("settings");
        field.setAccessible(true);
        return (LinkSettings) field.get(codec);
    }

    @Test
    void the_lifetime_may_be_scope() {
        enabled().withPropertyValues("dataprism.links.lifetime=scope").run(result -> assertThat(result).hasNotFailed());
    }

    @ParameterizedTest
    @CsvSource({"lifetime, PT0S, INVALID_LINK_LIFETIME", "lifetime, PT0.5S, INVALID_LINK_LIFETIME",
            "lifetime, -PT1M, INVALID_LINK_LIFETIME", "lifetime, soon, INVALID_LINK_LIFETIME",
            "max-per-response, 0, INVALID_LINK_LIMIT", "max-per-response, 501, INVALID_LINK_LIMIT",
            "max-per-response, many, INVALID_LINK_LIMIT", "max-length, 615, INVALID_LINK_LIMIT",
            "max-length, 4097, INVALID_LINK_LIMIT", "invalid-attempt-limit, 0, INVALID_LINK_LIMIT",
            "invalid-attempt-limit, -4, INVALID_LINK_LIMIT", "invalid-attempt-window, PT0S, INVALID_LINK_LIMIT",
            "invalid-attempt-window, -PT3M, INVALID_LINK_LIMIT", "clock-skew, PT31S, INVALID_LINK_LIMIT",
            "clock-skew, -PT1S, INVALID_LINK_LIMIT"})
    void a_value_out_of_range_refuses_startup_with_its_code_naming_only_the_property(String key, String value,
                                                                                      String code) {
        // With links off as well as on: a bad value is not waiting to be switched on.
        for (WebApplicationContextRunner runner : List.of(context, enabled())) {
            runner.withPropertyValues("dataprism.links." + key + "=" + value).run(result -> {
                assertThat(result).hasFailed();
                DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
                assertThat(refusal.code()).isEqualTo(code);
                assertThat(refusal.getMessage()).contains("dataprism.links." + key);
                if (value.length() > 3) {
                    assertThat(refusal.getMessage()).doesNotContain(value);
                }
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"max-per-response, 1", "max-per-response, 500", "max-length, 616", "max-length, 4096",
            "invalid-attempt-limit, 1", "clock-skew, PT0S", "clock-skew, PT30S", "lifetime, PT1S"})
    void the_edges_of_every_range_are_accepted(String key, String value) {
        enabled().withPropertyValues("dataprism.links." + key + "=" + value, "dataprism.paging.lifetime=PT1S")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void links_enabled_without_a_link_key_refuses() {
        context.withPropertyValues("dataprism.links.enabled=true").run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("LINK_KEY_UNRESOLVED");
            assertThat(refusal.getMessage()).contains("dataprism.links.key-id");
        });
    }

    @Test
    void a_paging_lifetime_above_the_link_lifetime_refuses_only_while_links_are_enabled() {
        enabled().withPropertyValues("dataprism.links.lifetime=PT10M", "dataprism.paging.lifetime=PT11M")
                .run(result -> {
                    assertThat(result).hasFailed();
                    DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
                    assertThat(refusal.code()).isEqualTo("INVALID_PAGING_SETTING");
                    assertThat(refusal.getMessage()).contains("dataprism.paging.lifetime")
                            .contains("dataprism.links.lifetime").doesNotContain("PT11M");
                });
        enabled().withPropertyValues("dataprism.links.lifetime=PT10M", "dataprism.paging.lifetime=PT10M")
                .run(result -> assertThat(result).hasNotFailed());
        context.withPropertyValues(KEY, KEY_REF, "dataprism.links.lifetime=PT10M", "dataprism.paging.lifetime=PT11M")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @ParameterizedTest
    @CsvSource({"get_entity_context", "list_sources"})
    void an_issuing_tool_that_needs_approval_refuses_unless_follow_does_too(String tool) {
        enabled().withPropertyValues(OPERATOR).withPropertyValues("dataprism.oversight.approval-required-tools[0]=" + tool).run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("FOLLOW_NOT_APPROVAL_GATED");
            assertThat(refusal.getMessage()).contains("dataprism.oversight.approval-required-tools")
                    .doesNotContain(tool);
        });
        enabled().withPropertyValues(OPERATOR).withPropertyValues("dataprism.oversight.approval-required-tools[0]=" + tool,
                "dataprism.oversight.approval-required-tools[1]=follow")
                .run(result -> assertThat(result).hasNotFailed());
        // Links off: follow does not exist, so there is nothing to gate.
        context.withPropertyValues(OPERATOR).withPropertyValues("dataprism.oversight.approval-required-tools[0]=" + tool)
                .run(result -> assertThat(result).hasNotFailed());
    }

    static List<String> names(McpSyncServer server) {
        return server.listTools().stream().map(McpSchema.Tool::name).toList();
    }

    static DataPrismConfigurationException refusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e) {
                return e;
            }
        }
        throw new AssertionError("no DataPrismConfigurationException in the cause chain", failure);
    }
}
