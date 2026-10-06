package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.hazelcast.HazelcastApprovalStore;
import io.github.aindriub.dataprism.hazelcast.HazelcastCallerRateLimiter;
import io.github.aindriub.dataprism.hazelcast.HazelcastOversightState;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dataprism.oversight.*} and {@code dataprism.operator.*}: bound, validated, and carried
 * through to the Spring-built MCP server rather than only parsed.
 */
class OversightConfigurationTest {

    static final List<AuditEvent> AUDITED = new CopyOnWriteArrayList<>();

    private static final String[] OPERATOR = {
            "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
            "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=dataprism.operate"};

    @AfterEach
    void shutDownEveryClusterMemberThisTestStarted() {
        Hazelcast.shutdownAll();
        AUDITED.clear();
    }

    /** Every property a protected deployment needs, for the topology named. */
    static String[] base(String topology) {
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
                "dataprism.metrics.sink=micrometer", "dataprism.hazelcast.topology=" + topology,
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    static WebApplicationContextRunner runner(String topology, String... extra) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(RecordingIntegrations.class)
                .withPropertyValues(base(topology))
                .withPropertyValues(extra);
    }

    static String[] concat(String[] first, String... second) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(second));
        return all.toArray(String[]::new);
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingIntegrations extends DataPrismAutoConfigurationTest.ReviewedHttpIntegrations {
        @Override @Bean AuditSink audit() { return AUDITED::add; }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }

    private static void refuses(String code, String... properties) {
        runner("single-node", properties).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith(code + ":");
        });
    }

    // ---- refusal codes -------------------------------------------------------------------------

    @Test void refuses_an_unknown_approval_required_tool() {
        refuses("UNKNOWN_OVERSIGHT_TOOL", concat(OPERATOR, "dataprism.oversight.approval-required-tools[0]=reidentify"));
    }
    @Test void refuses_a_non_positive_rate_limit() {
        refuses("INVALID_OVERSIGHT_LIMIT", concat(OPERATOR, "dataprism.oversight.caller-rate-limit.requests=0"));
    }
    @Test void refuses_a_non_positive_rate_limit_window() {
        refuses("INVALID_OVERSIGHT_LIMIT", concat(OPERATOR, "dataprism.oversight.caller-rate-limit.window=PT0S"));
    }
    @Test void refuses_a_non_positive_oversight_approval_ttl() {
        refuses("INVALID_OVERSIGHT_LIMIT", concat(OPERATOR, "dataprism.oversight.approval-ttl=PT0S"));
    }
    @Test void refuses_a_non_positive_oversight_max_pending_per_requester() {
        refuses("INVALID_OVERSIGHT_LIMIT", concat(OPERATOR, "dataprism.oversight.max-pending-per-requester=0"));
    }
    @Test void refuses_approval_required_tools_without_the_operator_surface() {
        refuses("OVERSIGHT_REQUIRES_OPERATOR_SURFACE", "dataprism.oversight.approval-required-tools[0]=get_entity_context");
    }
    @Test void refuses_a_rate_limit_without_the_operator_surface() {
        refuses("OVERSIGHT_REQUIRES_OPERATOR_SURFACE", "dataprism.oversight.caller-rate-limit.requests=10");
    }
    @Test void refuses_an_operator_surface_without_a_port() {
        refuses("MISSING_OPERATOR_SECURITY", "dataprism.operator.enabled=true",
                "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=s");
    }
    @Test void refuses_an_operator_surface_without_a_required_audience() {
        refuses("MISSING_OPERATOR_SECURITY", "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
                "dataprism.operator.required-scope=s");
    }
    @Test void refuses_an_operator_surface_without_a_required_scope() {
        refuses("MISSING_OPERATOR_SECURITY", "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
                "dataprism.operator.required-audience=operator");
    }
    @Test void refuses_an_operator_port_equal_to_the_server_port() {
        refuses("OPERATOR_PORT_SHARED", "server.port=9443", "dataprism.operator.enabled=true",
                "dataprism.operator.port=9443", "dataprism.operator.required-audience=operator",
                "dataprism.operator.required-scope=s");
    }
    @Test void refuses_an_operator_port_equal_to_the_default_server_port() {
        refuses("OPERATOR_PORT_SHARED", "dataprism.operator.enabled=true", "dataprism.operator.port=8080",
                "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=s");
    }

    // ---- defaults ------------------------------------------------------------------------------

    @Test void binds_the_documented_defaults() {
        runner("single-node").run(result -> {
            assertThat(result).hasNotFailed();
            DataPrismProperties p = result.getBean(DataPrismProperties.class);
            assertThat(p.getOversight().getApprovalRequiredTools()).isEmpty();
            assertThat(p.getOversight().getApprovalTtl()).isEqualTo(java.time.Duration.ofMinutes(15));
            assertThat(p.getOversight().getCallerRateLimit().getRequests()).isNull();
            assertThat(p.getOversight().getCallerRateLimit().getWindow()).isEqualTo(java.time.Duration.ofMinutes(1));
            assertThat(p.getOversight().getMaxPendingPerRequester()).isEqualTo(5);
            assertThat(p.getOperator().isEnabled()).isFalse();
            OversightPolicy policy = result.getBean(OversightPolicy.class);
            assertThat(policy.maxPendingPerRequester()).isEqualTo(5);
            assertThat(policy.approvalRequiredTools()).isEmpty();
            assertThat(policy.callerRequestLimit()).isEmpty();
        });
    }

    // ---- state selection -----------------------------------------------------------------------

    @Test void embedded_topology_uses_the_hazelcast_oversight_state() {
        runner("embedded").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(OversightState.class)).isInstanceOf(HazelcastOversightState.class);
            assertThat(result.getBean(ApprovalStore.class)).isInstanceOf(HazelcastApprovalStore.class);
            assertThat(result.getBean(CallerRateLimiter.class)).isInstanceOf(HazelcastCallerRateLimiter.class);
        });
    }

    @Test void single_node_topology_uses_the_in_memory_oversight_state() {
        runner("single-node").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(OversightState.class)).isInstanceOf(InMemoryOversightState.class);
            assertThat(result.getBean(ApprovalStore.class)).isInstanceOf(InMemoryApprovalStore.class);
            assertThat(result.getBean(CallerRateLimiter.class)).isInstanceOf(InMemoryCallerRateLimiter.class);
        });
    }

    // ---- admission through the Spring-built server ---------------------------------------------

    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller("principal-1", "client-1",
            Set.of("investigator"), "investigation", "case-1", null);
    private static final Map<String, Object> ARGS = Map.of("entityType", "CUSTOMER", "subjectId", "123");

    /** Calls a tool on the Spring-built server's own registered handler, as the transport would. */
    static McpSchema.CallToolResult call(AssertableWebApplicationContext context, String tool,
            Map<String, Object> arguments) {
        McpSyncServer server = context.getBean(McpSyncServer.class);
        @SuppressWarnings("unchecked")
        List<McpServerFeatures.AsyncToolSpecification> tools = (List<McpServerFeatures.AsyncToolSpecification>)
                ReflectionTestUtils.getField(server.getAsyncServer(), "tools");
        McpServerFeatures.AsyncToolSpecification specification = tools.stream()
                .filter(t -> t.tool().name().equals(tool)).findFirst().orElseThrow();
        McpTransportContext transport = McpTransportContext.create(
                Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, CALLER));
        McpAsyncServerExchange exchange = new McpAsyncServerExchange("session-1", null, null, null, transport);
        return specification.callHandler().apply(exchange, new McpSchema.CallToolRequest(tool, arguments)).block();
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static void run(String[] properties, Consumer<AssertableWebApplicationContext> assertions) {
        runner("single-node", properties).run(result -> {
            assertThat(result).hasNotFailed();
            assertions.accept(result);
        });
    }

    @Test void an_approval_required_tool_is_refused_through_the_spring_built_server() {
        run(concat(OPERATOR, "dataprism.oversight.approval-required-tools[0]=get_entity_context"), context -> {
            McpSchema.CallToolResult result = call(context, "get_entity_context", ARGS);
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).startsWith("APPROVAL_REQUIRED approvalId=");
        });
    }

    @Test void a_paused_tool_is_refused_through_the_spring_built_server() {
        run(new String[0], context -> {
            context.getBean(OversightState.class).pauseTool("get_entity_context");
            McpSchema.CallToolResult result = call(context, "get_entity_context", ARGS);
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).isEqualTo("TOOL_PAUSED");
        });
    }

    @Test void a_second_pending_request_over_the_cap_is_refused_and_audited() {
        run(concat(OPERATOR, "dataprism.oversight.approval-required-tools[0]=get_entity_context",
                "dataprism.oversight.max-pending-per-requester=1"), context -> {
            assertThat(text(call(context, "get_entity_context", ARGS))).startsWith("APPROVAL_REQUIRED");
            McpSchema.CallToolResult second = call(context, "get_entity_context",
                    Map.of("entityType", "CUSTOMER", "subjectId", "456"));
            assertThat(second.isError()).isTrue();
            assertThat(text(second)).startsWith("TOO_MANY_PENDING");
            assertThat(AUDITED).anySatisfy(event -> assertThat(event.policyDecision()).isEqualTo("DENY:TOO_MANY_PENDING"));
        });
    }

    @Test void the_tool_list_is_the_two_existing_tools() {
        run(new String[0], context -> assertThat(context.getBean(McpSyncServer.class).listTools())
                .extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("get_entity_context", "compare_entity_sources"));
    }
}
