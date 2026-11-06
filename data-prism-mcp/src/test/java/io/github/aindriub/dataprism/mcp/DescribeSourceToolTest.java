package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.mcp.ListSourcesToolTest.Env;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.aindriub.dataprism.mcp.ListSourcesToolTest.args;
import static io.github.aindriub.dataprism.mcp.ListSourcesToolTest.call;
import static io.github.aindriub.dataprism.mcp.ListSourcesToolTest.text;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code describe_source} as a tool: authorisation, the one refusal, the audit and the served schema. */
class DescribeSourceToolTest {

    private static final Set<String> DESCRIBE_AND_NAMES = Set.of("DESCRIBE_SOURCE", "EXPOSE_SOURCE_NAMES");

    @Test
    @DisplayName("a role without DESCRIBE_SOURCE gets TOOL_NOT_PERMITTED, audited under describe_source")
    void withoutTheCapabilityIsRefused() {
        Env env = new Env(Set.of("LIST_SOURCES"), 2);

        McpSchema.CallToolResult result = call(env.describe.specification(), args("source", "src-001"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("TOOL_NOT_PERMITTED");
        assertThat(env.audited).singleElement().satisfies(event -> {
            assertThat(event.tool()).isEqualTo("describe_source");
            assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_NOT_PERMITTED");
        });
    }

    @Test
    @DisplayName("a described source returns the schema, validates against it, and audits the real name")
    @SuppressWarnings("unchecked")
    void successAndAudit() {
        Env env = new Env(DESCRIBE_AND_NAMES, 2);
        var spec = env.describe.specification();

        McpSchema.CallToolResult result = call(spec, args("source", "src-002"));

        Map<String, Object> body = (Map<String, Object>) result.structuredContent();
        assertThat(body).containsEntry("labels", "REAL").containsEntry("source", "src-002")
                .containsEntry("undeclared", "REFUSE");
        assertThat((List<Map<String, Object>>) body.get("fields")).extracting(f -> f.get("path"))
                .containsExactly("id", "state");
        assertThat(new DefaultJsonSchemaValidator().validate(spec.tool().outputSchema(), body).valid()).isTrue();
        assertThat(env.audited).singleElement().satisfies(event -> {
            assertThat(event.tool()).isEqualTo("describe_source");
            assertThat(event.policyDecision()).isEqualTo("ALLOW");
            assertThat(event.sourceSystems()).containsExactly("src-002");
            assertThat(event.fieldDispositions()).containsEntry("names", "REAL");
            assertThat(event.entityType()).isEmpty();
        });
    }

    @Test
    @DisplayName("an unknown label and a label of the wrong kind are the identical result; the audit holds no label")
    void oneRefusal() {
        Env alias = new Env(Set.of("DESCRIBE_SOURCE"), 2);
        Env real = new Env(DESCRIBE_AND_NAMES, 2);

        McpSchema.CallToolResult unknown = call(real.describe.specification(), args("source", "SENTINEL-LABEL"));
        // A real name passed while aliases are in effect never resolves.
        McpSchema.CallToolResult wrongKind = call(alias.describe.specification(), args("source", "src-001"));

        assertThat(unknown.isError()).isTrue();
        assertThat(wrongKind.isError()).isTrue();
        assertThat(text(unknown)).isEqualTo("SOURCE_NOT_AVAILABLE").isEqualTo(text(wrongKind));
        assertThat(unknown.structuredContent()).isNull();
        AuditEvent refused = real.audited.get(0);
        assertThat(refused.policyDecision()).isEqualTo("DENY:SOURCE_NOT_AVAILABLE");
        assertThat(refused.fieldDispositions()).containsEntry("catalog", "REFUSED:CATALOG_UNKNOWN_SOURCE");
        assertThat(refused.sourceSystems()).isEmpty();
        assertThat(real.audited.toString()).doesNotContain("SENTINEL-LABEL");
        assertThat(alias.audited.get(0).fieldDispositions()).containsEntry("catalog", "REFUSED:CATALOG_UNKNOWN_SOURCE");
    }

    @Test
    @DisplayName("a label that breaks the schema is refused without echoing it")
    void invalidLabel() {
        Env env = new Env(DESCRIBE_AND_NAMES, 2);

        for (Map<String, Object> bad : List.of(args(), args("source", ""), args("source", 5),
                args("source", "x".repeat(129)))) {
            McpSchema.CallToolResult result = call(env.describe.specification(), bad);
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).isEqualTo("TOOL_INPUT_INVALID");
        }
        assertThat(env.audited).hasSize(4).allSatisfy(event -> {
            assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_INPUT_INVALID");
            assertThat(event.entityType()).isEmpty();
        });
    }

    @Test
    @DisplayName("the response contains no class name, base url or credential reference")
    void nothingBeyondTheVocabulary() {
        Env env = new Env(DESCRIBE_AND_NAMES, 2);

        String body = text(call(env.describe.specification(), args("source", "src-001")));

        assertThat(body).doesNotContain("Thing").doesNotContain("http").doesNotContain("DataSourceAdapter")
                .doesNotContain("credential").doesNotContain("PII");
    }
}
