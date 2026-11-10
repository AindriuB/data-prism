package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.mcp.ListSourcesToolTest.Env;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes {@code tools/list} (register section 15, "MCP tools and their input and output schemas"). The server
 * is built by the production factory with the catalog and links on, as a deployment with both serves it, and
 * every tool's name, {@code inputSchema} and {@code outputSchema} (when it has one) are compared, byte for
 * byte, with {@code golden/tools-list.json}: tools in name order, object keys sorted, two-space indent, one
 * final newline. The {@code follow} {@code link} {@code maxLength} is the configured
 * {@code dataprism.links.max-length}, here its default.
 *
 * <p>Regenerate with {@code -Dfreeze.write=<directory>}, which writes {@code tools-list.json} there.
 */
class ToolsListGoldenTest {

    private static final String GOLDEN = "golden/tools-list.json";
    private static final ObjectMapper MAPPER = DataPrismObjectMapper.create();

    @Test
    @DisplayName("tools/list, with links on, is the checked-in golden file byte for byte")
    void toolsListIsTheGoldenFile() throws Exception {
        String actual = render(servedTools());
        String write = System.getProperty("freeze.write");
        if (write != null) {
            Files.createDirectories(Path.of(write));
            Files.writeString(Path.of(write).resolve("tools-list.json"), actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).as(GOLDEN + " (regenerate with -Dfreeze.write=<directory>)").isEqualTo(golden());
    }

    private static List<McpSchema.Tool> servedTools() {
        Env env = new Env(Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES", "LIST_SOURCES",
                "DESCRIBE_SOURCE", "FOLLOW"), 1);
        SecurityPolicy policy = new SecurityPolicy(Set.of("demonstration"), Map.of("developer", Set.of("FOLLOW")));
        AuthorizationService authorization = new AuthorizationService(policy, "DEFAULT",
                PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(PseudonymisationVersion.HMAC_SHA256_V1, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        AuthenticatedCaller caller = new AuthenticatedCaller("stdio-development", "stdio-development",
                Set.of("developer"), "demonstration", "CASE-DEMO-1", null);
        McpSyncServer server = DataPrismMcpServer.stdio(new NeverCalledOrchestrator(), authorization, scopes, caller,
                true, false, PrivacyMetrics.none(), env.audit, ListSourcesToolTest.FIXED,
                ToolOptions.defaults().noAdmission().build(), env.queries,
                ListSourcesTool.DEFAULT_PAGE_MAX_LENGTH, true);
        try {
            return new ArrayList<>(server.listTools());
        } finally {
            server.closeGracefully();
        }
    }

    /** Name, input schema and output schema of each tool, in name order, with object keys sorted. */
    private static String render(List<McpSchema.Tool> tools) {
        List<Object> rendered = new ArrayList<>();
        tools.stream().sorted(Comparator.comparing(McpSchema.Tool::name)).forEach(tool -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("inputSchema", sorted(tool.inputSchema()));
            if (tool.outputSchema() != null) {
                entry.put("outputSchema", sorted(tool.outputSchema()));
            }
            rendered.add(entry);
        });
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("tools", rendered)) + "\n";
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ToolsListGoldenTest::sorted).toList();
        }
        return value;
    }

    private static String golden() throws Exception {
        try (InputStream in = ToolsListGoldenTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(in).as(GOLDEN).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class NeverCalledOrchestrator implements ContextOrchestrator {
        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacy,
                                            InvestigationContext investigation) {
            throw new AssertionError("tools/list never reaches the orchestrator");
        }
    }
}
