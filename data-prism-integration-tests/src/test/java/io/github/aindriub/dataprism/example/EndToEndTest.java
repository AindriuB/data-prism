package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole thread: authorisation, scope resolution, fetch, scrub, validate,
 * audit, serialise.
 *
 * <p>Drives the tool's own call handler with a real {@link McpSyncServerExchange}
 * carrying an {@link AuthenticatedCaller} in its transport context, rather than
 * a stdio subprocess, so the assertions are about the pipeline rather than about
 * JSON-RPC framing. The transport is exercised separately by running the
 * application.
 */
class EndToEndTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
    private static final String PURPOSE = "demonstration";
    private static final String ROLE = "investigator";

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditSink sink = audited::add;
    private final DataPrismAssembly assembly =
            new DataPrismAssembly(List.of(new StubCustomerAdapter()), FIXED, sink);
    private final AuditRecorder toolAudit = new AuditRecorder(sink, FIXED, "example-1-mcp");

    private static AuthenticatedCaller caller(String caseId) {
        return new AuthenticatedCaller("investigator-1", "client-1", Set.of(ROLE), PURPOSE, caseId, null);
    }

    private static AuthorizationService authorizationService(String privacyProfile) {
        SecurityPolicy policy = new SecurityPolicy(Set.of(PURPOSE), Map.of(ROLE, Set.of("GET_ENTITY_CONTEXT")));
        return new AuthorizationService(policy, privacyProfile, PrivacyScopeType.INVESTIGATION);
    }

    private static McpSyncServerExchange exchangeFor(AuthenticatedCaller caller) {
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null,
                McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return call(assembly, "DEFAULT", caller("case-1"), arguments);
    }

    private McpSchema.CallToolResult call(DataPrismAssembly assembly, String privacyProfile,
                                          AuthenticatedCaller caller, Map<String, Object> arguments) {
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(PURPOSE)));
        var tool = new GetEntityContextTool(assembly.orchestrator(), authorizationService(privacyProfile),
                scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, FIXED);
        return tool.specification().callHandler()
                .apply(exchangeFor(caller), new McpSchema.CallToolRequest(GetEntityContextTool.NAME, arguments));
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
    @DisplayName("two callers with different case_id claims read the same subject as two different pseudonyms")
    void scopeIsolationHoldsAcrossCallers() {
        String first = call(assembly, "DEFAULT", caller("case-1"),
                Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();
        String second = call(assembly, "DEFAULT", caller("case-2"),
                Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();

        assertThat(first).isNotEqualTo(second);
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
    @DisplayName("a caller cannot pass its own principal, scope, purpose or case id")
    void ignoresCallerSuppliedContext() {
        McpSchema.CallToolResult withoutReservedArguments =
                call(Map.of("entityType", "CUSTOMER", "subjectId", "123"));
        audited.clear();

        McpSchema.CallToolResult withReservedArguments = call(Map.of(
                "entityType", "CUSTOMER", "subjectId", "123",
                "principalId", "someone-else",
                "scopeId", "CASE-SOMEONE-ELSE",
                "purpose", "whatever",
                "caseId", "CASE-SOMEONE-ELSE"));

        // The reserved arguments are not read at all: the call is served on
        // session-derived context rather than refused. Equal content is what
        // separates "ignored, call proceeded" from "refused" -- a denial
        // cannot produce the same content as the unadulterated call above.
        assertThat(withReservedArguments.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(withReservedArguments.content()).isEqualTo(withoutReservedArguments.content());

        // Scope comes from the session, which is what stops one investigation
        // reaching another's pseudonyms. The attempt is not merely ignored,
        // though: its names are audited. "case:" + case id is ScopeResolver's
        // own, documented format (see ScopeResolver.scopeId).
        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("ALLOW");
            assertThat(event.scopeId()).isEqualTo("case:case-1");
            assertThat(event.rejectedArguments())
                    .containsExactlyInAnyOrder("principalId", "scopeId", "purpose", "caseId");
        });
    }

    @Test
    @DisplayName("a rejected argument's value never appears in the audit event or the warning it logs")
    void rejectedArgumentValueNeverAppearsAnywhere() {
        // Distinctive enough that a stray copy anywhere is unmistakably this
        // value and not, say, a coincidental substring of something legitimate.
        String distinctiveValue = "ZQ7-NEVER-LOG-THIS-VALUE-4471";

        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            call(Map.of("entityType", "CUSTOMER", "subjectId", "123", "scopeId", distinctiveValue));
        } finally {
            System.setErr(originalErr);
        }
        String logged = captured.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(logged).contains("WARN").contains("scopeId").doesNotContain(distinctiveValue);
        // Logged once, not once per pipeline stage that happens to see it.
        assertThat(logged.split("WARN", -1).length - 1).isEqualTo(1);

        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.rejectedArguments()).containsExactly("scopeId");
            assertThat(event.toString()).doesNotContain(distinctiveValue);
        });
    }

    @Test
    @DisplayName("changing the profile changes the outcome, with no code change")
    void profileDecidesRatherThanTheAnnotation() {
        // CustomerDto suggests SYNTHESIZE for the name. Under DEFAULT that
        // stands; under STRICT the operator's rule is stricter and wins. This is
        // the property the specification asks for: privacy decisions are
        // configuration, not a recompile.
        String underDefault = call(assembly, "DEFAULT", caller("case-1"),
                Map.of("entityType", "CUSTOMER", "subjectId", "123")).content().toString();
        String underStrict = call(assembly, "STRICT", caller("case-1"),
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
                io.github.aindriub.dataprism.orchestration.ContextRequest.of("THING", "1"),
                assembly.privacyContext(), assembly.investigationContext()))
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
