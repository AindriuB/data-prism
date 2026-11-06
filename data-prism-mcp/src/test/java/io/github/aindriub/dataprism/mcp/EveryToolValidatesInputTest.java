package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tool {@link DataPrismMcpServer.Wiring} registers validates its own input, so an attempt that breaks
 * the served schema is still audited once. A tool added to the wiring without {@link ToolInput} fails here
 * with no edit to this class.
 */
class EveryToolValidatesInputTest {

    static final Set<String> ALL = Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES", "LIST_SOURCES",
            "DESCRIBE_SOURCE", "FOLLOW");

    /** The wiring the server builds, over the catalog fixture of {@link ListSourcesToolTest}. */
    static final class Fixture {
        final ListSourcesToolTest.Env env;
        final DataPrismMcpServer.Wiring wiring;

        Fixture(Set<String> capabilities, AuthenticatedCaller developmentCaller) {
            ToolOptions options = ToolOptions.defaults().noAdmission().build();
            env = new ListSourcesToolTest.Env(capabilities, 3, true, options, 2);
            SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                    Map.of("investigator", capabilities));
            AuthorizationService authz = new AuthorizationService(security, "DEFAULT",
                    PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
            ScopeResolver scopes = new ScopeResolver(
                    PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                    Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")),
                    CaseAuthorizer.tokenTrusted());
            ContextOrchestrator followOnly = FollowToolTest.realOrchestrator(env.audit, FollowToolTest.limiter(100));
            ContextOrchestrator never = new ContextOrchestrator() {
                @Override
                public ContextResponse buildContext(ContextRequest request, PrivacyContext privacy,
                                                    InvestigationContext caller) {
                    throw new AssertionError("an invalid call never reaches the orchestrator");
                }

                @Override
                public ContextResponse follow(io.github.aindriub.dataprism.orchestration.links.FollowRequest request,
                                              PrivacyContext privacy, InvestigationContext caller) {
                    // A call with a link member reaches the gate as a forged link; the real one refuses and audits it.
                    return followOnly.follow(request, privacy, caller);
                }
            };
            wiring = DataPrismMcpServer.Wiring.of(never, authz, scopes, env.metrics, env.audit,
                    ListSourcesToolTest.FIXED, developmentCaller, options, env.queries, 1024, true);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("every declared property sent as a JSON object is refused, with one audit event")
    void everyDeclaredPropertyIsValidated() {
        Fixture fx = new Fixture(ALL, ListSourcesToolTest.CALLER);
        List<String> seen = new ArrayList<>();

        for (McpServerFeatures.SyncToolSpecification spec : fx.wiring.specifications()) {
            Map<String, Object> properties = (Map<String, Object>) spec.tool().inputSchema().get("properties");
            assertThat(properties).as(spec.tool().name()).isNotEmpty();
            for (String property : properties.keySet()) {
                int before = fx.env.audited.size();

                McpSchema.CallToolResult result = ListSourcesToolTest.call(spec, Map.of(property, Map.of()));

                String where = spec.tool().name() + "." + property;
                assertThat(result.isError()).as(where).isTrue();
                assertThat(ListSourcesToolTest.text(result)).as(where)
                        .isIn("TOOL_INPUT_INVALID", "The link is not valid.");
                assertThat(fx.env.audited).as(where).hasSize(before + 1);
                assertThat(fx.env.audited.get(before).policyDecision()).as(where)
                        .isIn("DENY:TOOL_INPUT_INVALID", "DENY:LINK_INVALID");
                seen.add(where);
            }
        }
        assertThat(seen).contains("get_entity_context.targetSources", "compare_entity_sources.subjectId",
                "list_sources.page", "describe_source.source", "follow.link");
    }

    @Test
    @DisplayName("the wiring registers all five tools, so the test above covers them")
    void allFiveToolsAreCovered() {
        Fixture fx = new Fixture(ALL, ListSourcesToolTest.CALLER);

        assertThat(List.of(fx.wiring.specifications()).stream().map(s -> s.tool().name()))
                .containsExactly("get_entity_context", "compare_entity_sources", "list_sources", "describe_source", "follow");
    }
}
