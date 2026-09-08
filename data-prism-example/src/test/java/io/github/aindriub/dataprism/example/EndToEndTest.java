package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole thread: tool call, fetch, scrub, validate, audit, serialise.
 *
 * <p>Drives the tool's own call handler rather than a stdio subprocess, so the
 * assertions are about the pipeline rather than about JSON-RPC framing. The
 * transport is exercised separately by running the application.
 */
class EndToEndTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditSink sink = audited::add;
    private final DataPrismAssembly assembly =
            new DataPrismAssembly(List.of(new StubCustomerAdapter()), FIXED, sink);

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return call(assembly, arguments);
    }

    private static McpSchema.CallToolResult call(DataPrismAssembly assembly,
                                                 Map<String, Object> arguments) {
        var tool = new GetEntityContextTool(assembly.orchestrator(), assembly::privacyContext,
                DataPrismObjectMapper.create());
        return tool.specification().callHandler()
                .apply(null, new McpSchema.CallToolRequest(GetEntityContextTool.NAME, arguments));
    }

    @Test
    @DisplayName("a known subject comes back pseudonymised, with nothing raw in it")
    void returnsPseudonymisedContext() {
        McpSchema.CallToolResult result = call(Map.of("entityType", "CUSTOMER", "subjectId", "123"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String body = result.content().toString();

        assertThat(body)
                .doesNotContain("Patrick Murphy")
                .doesNotContain("patrick.murphy@example.invalid")
                .doesNotContain("\"123\"");
        assertThat(body).contains("[REDACTED]").contains("ACTIVE");
    }

    @Test
    @DisplayName("the same subject reads the same in every call within a scope")
    void pseudonymIsStableWithinScope() {
        String first = call(Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();
        String second = call(Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("two subjects do not read as the same person")
    void distinctSubjectsReadDifferently() {
        String one = call(Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();
        String two = call(Map.of("entityType", "CUSTOMER", "subjectId", "456")).content().toString();

        assertThat(two).isNotEqualTo(one);
    }

    @Test
    @DisplayName("every call is audited, and the audit holds no raw identifier")
    void auditsWithoutRawIdentifiers() {
        call(Map.of("entityType", "CUSTOMER", "subjectId", "123"));

        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("ALLOW");
            assertThat(event.tool()).isEqualTo("get_entity_context");
            assertThat(event.sourceSystems()).containsExactly("customer-api:ANSWERED");
            assertThat(event.subjectPseudonym()).startsWith("SUBJ-").isNotEqualTo("123");
            assertThat(event.toString())
                    .doesNotContain("Patrick Murphy")
                    .doesNotContain("patrick.murphy@example.invalid");
            // The fingerprint correlates two requests for the same subject
            // without being reversible by anyone holding a list of likely ids.
            assertThat(event.parameterFingerprint()).doesNotContain("123").hasSize(24);
        });
    }

    @Test
    @DisplayName("an unknown subject is refused rather than answered emptily")
    void refusesUnknownSubject() {
        McpSchema.CallToolResult result = call(Map.of("entityType", "CUSTOMER", "subjectId", "nope"));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(audited).singleElement()
                .satisfies(event -> assertThat(event.policyDecision()).isEqualTo("DENY"));
    }

    @Test
    @DisplayName("a caller cannot pass its own scope or purpose")
    void ignoresCallerSuppliedContext() {
        call(Map.of("entityType", "CUSTOMER", "subjectId", "123",
                "scopeId", "CASE-SOMEONE-ELSE", "purpose", "whatever"));

        // The extra arguments are not read at all. Scope comes from the session,
        // which is what stops one investigation reaching another's pseudonyms.
        assertThat(audited).singleElement()
                .satisfies(event -> assertThat(event.scopeId()).isEqualTo("CASE-DEMO-1"));
    }

    @Test
    @DisplayName("changing the profile changes the outcome, with no code change")
    void profileDecidesRatherThanTheAnnotation() {
        // CustomerDto suggests SYNTHESIZE for the name. Under DEFAULT that
        // stands; under STRICT the operator's rule is stricter and wins. This is
        // the property the specification asks for: privacy decisions are
        // configuration, not a recompile.
        String underDefault = call(assembly,
                Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();
        String underStrict = call(
                new DataPrismAssembly(List.of(new StubCustomerAdapter()), FIXED, sink, "STRICT"),
                Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();

        assertThat(underDefault).doesNotContain("Patrick Murphy");
        assertThat(underStrict).doesNotContain("Patrick Murphy");
        assertThat(underDefault).as("DEFAULT synthesises a readable name").contains("(");
        assertThat(underStrict).as("STRICT redacts it instead").doesNotContain("(");
        assertThat(underStrict).isNotEqualTo(underDefault);
    }

    @Test
    @DisplayName("the pipeline refuses rather than returning an unscrubbed record")
    void orchestratorRefusesUnexposedModel() {
        var assembly = new DataPrismAssembly(
                List.of(new UnexposedAdapter()), FIXED, sink);

        assertThatThrownBy(() -> assembly.orchestrator().buildContext(
                new io.github.aindriub.dataprism.orchestration.ContextRequest("THING", "1"),
                assembly.privacyContext()))
                .isInstanceOf(PrivacyRefusedException.class);
    }

    /** A source whose type was never approved for exposure. */
    private static final class UnexposedAdapter
            implements io.github.aindriub.dataprism.core.DataSourceAdapter<UnexposedAdapter.Record> {

        record Record(String id, String secret) {
        }

        @Override
        public String sourceName() {
            return "unexposed";
        }

        @Override
        public Class<Record> responseType() {
            return Record.class;
        }

        @Override
        public Record fetch(io.github.aindriub.dataprism.core.DataRequest request) {
            return new Record("1", "should never be emitted");
        }
    }
}
