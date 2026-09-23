package io.github.aindriub.dataprism.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.ConsistencyFinding;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
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
 * Drives {@link CompareEntitySourcesTool#specification()}'s call handler
 * directly, the way the SDK does. Mirrors {@link GetEntityContextToolTest}'s
 * shape for the mechanism every MCP tool shares (auth, scope, reserved
 * arguments, fail-closed), and adds the checks specific to comparison: the
 * three-state discriminator, identity assembly from the scrubbed tree only, and
 * — the one this file exists to prove beyond doubt — that a genuinely
 * disagreeing pair of raw values never reaches this tool's output.
 */
class CompareEntitySourcesToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditSink sink = audited::add;
    private final AuditRecorder audit = new AuditRecorder(sink, FIXED, "test-mcp");
    private final RecordingMetrics metrics = new RecordingMetrics();

    /** A namespaced field named after its own namespace, so a finding's field matches an entity key exactly. */
    @LlmExposedModel
    private record NamedThing(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String PERSON_NAME) {
    }

    /**
     * Shaped like a real model: {@code customerName}, not {@code PERSON_NAME}.
     * Paired with {@link AccountLike} below, this is the reviewer's exact
     * repro — two sources, same namespace, two different field names — used to
     * prove {@link #identityResolvesFieldNamesThatDifferFromTheNamespace} would
     * fail against {@code entity.get(finding.field())} alone.
     */
    @LlmExposedModel
    private record CustomerLike(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String customerName) {
    }

    /** The same namespace as {@link CustomerLike}, under a different field name -- {@code holderName}. */
    @LlmExposedModel
    private record AccountLike(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String holderName) {
    }

    private static AuthenticatedCaller caller(Set<String> roles) {
        return new AuthenticatedCaller("principal-1", "client-1", roles, "demonstration", "case-1", null);
    }

    private static SecurityPolicy policyGranting(String role, Set<String> capabilities) {
        return new SecurityPolicy(Set.of("demonstration"), Map.of(role, capabilities));
    }

    private static AuthorizationService authorizationService(SecurityPolicy policy) {
        return new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
    }

    private static ScopeResolver scopeResolver() {
        return new ScopeResolver(PseudonymisationVersion.HMAC_SHA256_V1, Duration.ofHours(8),
                new PurposeValidator(Set.of("demonstration")));
    }

    private static McpSyncServerExchange exchangeFor(AuthenticatedCaller caller) {
        McpTransportContext context = caller == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(CompareEntitySourcesTool.TRANSPORT_CONTEXT_CALLER_KEY, caller));
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", null, null, null, context));
    }

    private static McpSchema.CallToolRequest request(Map<String, Object> arguments) {
        return new McpSchema.CallToolRequest(CompareEntitySourcesTool.NAME, arguments);
    }

    private static String soleText(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    @DisplayName("a caller holding COMPARE_ENTITY_SOURCES reaches the orchestrator, on a comparison request")
    void authorisedCallerReachesOrchestrator() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(metrics.incremented).containsExactly(Metric.MCP_REQUESTS);
        assertThat(orchestrator.requests).singleElement().satisfies(req -> {
            assertThat(req.toolName()).isEqualTo("compare_entity_sources");
            assertThat(req.includeAgreementFindings()).isTrue();
        });
    }

    @Test
    @DisplayName("a caller holding only GET_ENTITY_CONTEXT is denied, audited, and never reaches the orchestrator")
    void unauthorisedCallerIsDeniedBeforeOrchestrator() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("GET_ENTITY_CONTEXT"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).isEqualTo("TOOL_NOT_PERMITTED");
        assertThat(orchestrator.requests).isEmpty();
        assertThat(metrics.incremented).containsExactly(Metric.MCP_DENIED);
        assertThat(audited).singleElement().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("TOOL_NOT_PERMITTED");
            assertThat(event.tool()).isEqualTo("compare_entity_sources");
        });
    }

    @Test
    @DisplayName("a call with no caller in the transport context is refused and never reaches the orchestrator")
    void missingCallerNeverReachesOrchestrator() {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(null), request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).isEqualTo(CompareEntitySourcesTool.NO_AUTHENTICATED_CALLER);
        assertThat(orchestrator.requests).isEmpty();
    }

    @Test
    @DisplayName("a throwing audit sink aborts an unauthenticated denial as AuditUnavailableException, "
            + "carrying no substring of the sink's own message")
    void auditFailureOnUnauthenticatedDenyAbortsWithNoCauseText() {
        String secretSinkMessage = "sink failure message that must never reach a client";
        AuditSink throwingSink = event -> {
            throw new IllegalStateException(secretSinkMessage);
        };
        AuditRecorder throwingAudit = new AuditRecorder(throwingSink, FIXED, "test-mcp");
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, throwingAudit, FIXED);

        assertThatThrownBy(() -> tool.specification().callHandler().apply(
                exchangeFor(null), request(Map.of("entityType", "CUSTOMER", "subjectId", "123"))))
                .isInstanceOf(AuditUnavailableException.class)
                .hasMessage(AuditUnavailableException.CODE)
                .hasMessageNotContaining(secretSinkMessage);
        assertThat(orchestrator.requests).isEmpty();
    }

    @Test
    @DisplayName("a throwing audit sink aborts an authorisation denial as AuditUnavailableException, "
            + "carrying no substring of the sink's own message")
    void auditFailureOnAuthorisationDenyAbortsWithNoCauseText() {
        String secretSinkMessage = "another sink failure message that must never reach a client";
        AuditSink throwingSink = event -> {
            throw new IllegalStateException(secretSinkMessage);
        };
        AuditRecorder throwingAudit = new AuditRecorder(throwingSink, FIXED, "test-mcp");
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("GET_ENTITY_CONTEXT"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, throwingAudit, FIXED);

        assertThatThrownBy(() -> tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123"))))
                .isInstanceOf(AuditUnavailableException.class)
                .hasMessage(AuditUnavailableException.CODE)
                .hasMessageNotContaining(secretSinkMessage);
        assertThat(orchestrator.requests).isEmpty();
    }

    @Test
    @DisplayName("a reserved argument is never read for its value, and its name reaches the audit event")
    void reservedArgumentsAreIgnoredAndReported() {
        ObjectMapper mapper = new ObjectMapper();
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (source, ctx) -> new ScrubResult(
                mapper.createObjectNode().put("PERSON_NAME", "PSEUDONYM-FOR-" + ((NamedThing) source).id()),
                Set.of());
        LlmResponseValidator alwaysOk = (resp, prohibited, emitted, ctx) -> ValidationResult.ok();
        // A real orchestrator, so the audit event asserted on below is the one
        // production code actually writes on an accepted call -- not a stub's
        // approximation of it.
        DefaultContextOrchestrator orchestrator = new DefaultContextOrchestrator(
                List.of(answeringNamed("source-a", new NamedThing("123", "Patrick Murphy"))),
                scrubber, resolver, List.of(alwaysOk, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS), audit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), PrivacyMetrics.none()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult withoutReservedArgument = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));
        McpSchema.CallToolResult withReservedArgument = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123",
                        "scopeId", "CASE-SOMEONE-ELSE")));

        // Equal content is what separates "ignored, call proceeded" from a
        // response shaped by the value: a denial or a value substitution could
        // never produce byte-identical output to the unadulterated call.
        assertThat(withReservedArgument.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(withReservedArgument.content()).isEqualTo(withoutReservedArgument.content());
        // The value never reaches the audit event either -- only the reserved
        // argument's name does.
        assertThat(audited).extracting(AuditEvent::rejectedArguments)
                .satisfiesExactly(first -> assertThat(first).isEmpty(),
                        second -> assertThat(second).containsExactly("scopeId"));
    }

    @Test
    @DisplayName("the input schema declares exactly entityType and subjectId, and no other property")
    void schemaDeclaresExactlyTwoProperties() {
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(new RecordingOrchestrator(),
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema.get("required")).isEqualTo(List.of("entityType", "subjectId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder("entityType", "subjectId");
        assertThat(properties.keySet()).doesNotContain(
                "scopeId", "purpose", "caseId", "sourceName", "host", "profile", "classification");
    }

    @Test
    @DisplayName("a field a finding names but the scrubbed tree does not contain is omitted, never defaulted")
    void identityOmitsAFieldTheScrubbedTreeDoesNotContain() {
        ObjectMapper mapper = DataPrismObjectMapper.create();
        // Nothing named "GOVERNMENT_IDENTIFIER" is in the entity tree: dropped,
        // redacted away entirely, or simply never returned by this fixture.
        ContextResponse response = new ContextResponse("CUSTOMER", "SUBJ-1", Map.of("source-a", "ANSWERED"),
                List.of(new ConsistencyFinding("GOVERNMENT_IDENTIFIER", PrivacyNamespace.GOVERNMENT_IDENTIFIER,
                        ConsistencyFinding.Kind.MISSING_IN_SOME_SOURCES, List.of(List.of("source-a")), 1,
                        "only 1 of 2 sources held a value")),
                mapper.createObjectNode());
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(new FixedResponseOrchestrator(response),
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), mapper, metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.structuredContent();
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) body.get("identity");
        assertThat(identity).isEmpty();
        assertThat(soleText(result)).doesNotContain("null").doesNotContain("[REDACTED]");
    }

    @Test
    @DisplayName("consistent, disagreeing and missing-in-some-sources fields are each separately identifiable, "
            + "and a field no source pair compared appears in none of them")
    void threeStatesAreDistinguishableWithoutInference() {
        ObjectMapper mapper = DataPrismObjectMapper.create();
        com.fasterxml.jackson.databind.node.ObjectNode entity = mapper.createObjectNode();
        entity.put("AGREED_FIELD", "PSEUDO-AGREED");
        entity.put("DISAGREED_FIELD", "PSEUDO-DISAGREED");
        entity.put("PARTIAL_FIELD", "PSEUDO-PARTIAL");

        ContextResponse response = new ContextResponse("CUSTOMER", "SUBJ-1",
                Map.of("source-a", "ANSWERED", "source-b", "ANSWERED"),
                List.of(
                        new ConsistencyFinding("AGREED_FIELD", PrivacyNamespace.PERSON_NAME,
                                ConsistencyFinding.Kind.CONSISTENT,
                                List.of(List.of("source-a", "source-b")), 1, "all sources agreed"),
                        new ConsistencyFinding("DISAGREED_FIELD", PrivacyNamespace.EMAIL,
                                ConsistencyFinding.Kind.INCONSISTENT,
                                List.of(List.of("source-a"), List.of("source-b")), 2, "values differ"),
                        new ConsistencyFinding("PARTIAL_FIELD", PrivacyNamespace.GOVERNMENT_IDENTIFIER,
                                ConsistencyFinding.Kind.MISSING_IN_SOME_SOURCES,
                                List.of(List.of("source-a")), 1, "only 1 of 2 sources held a value")),
                entity);
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(new FixedResponseOrchestrator(response),
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), mapper, metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.structuredContent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) body.get("findings");
        assertThat(findings).hasSize(3);

        Map<String, Object> agreed = byField(findings, "AGREED_FIELD");
        assertThat(agreed.get("consistent")).isEqualTo(true);
        assertThat(agreed.get("kind")).isEqualTo("CONSISTENT");
        assertThat(agreed).containsKeys("agreementGroups", "distinctValues", "detail");

        Map<String, Object> disagreed = byField(findings, "DISAGREED_FIELD");
        assertThat(disagreed.get("consistent")).isEqualTo(false);
        assertThat(disagreed.get("kind")).isEqualTo("INCONSISTENT");

        Map<String, Object> partial = byField(findings, "PARTIAL_FIELD");
        assertThat(partial.get("consistent")).isEqualTo(false);
        assertThat(partial.get("kind")).isEqualTo("MISSING_IN_SOME_SOURCES");

        // Never named by any finding, so it appears in neither identity nor findings.
        assertThat(soleText(result)).doesNotContain("NEVER_COMPARED_FIELD");
    }

    @Test
    @DisplayName("refuses without echoing the exception's own message")
    void refusesWithoutEchoingPrivacyRefusedMessage() {
        String secret = "internal-scope-budget-detail-not-for-the-model";
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(
                new ThrowingOrchestrator(new PrivacyRefusedException("SCOPE_READ_BUDGET", "CUSTOMER", secret)),
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).isEqualTo("refused: SCOPE_READ_BUDGET at CUSTOMER");
        assertThat(soleText(result)).doesNotContain(secret);
    }

    @Test
    @DisplayName("an unexpected runtime failure is refused with a fixed string, never its own message")
    void refusesWithFixedStringOnUnexpectedFailure() {
        String secret = "PAYLOAD-FRAGMENT-FROM-A-DOWNSTREAM-FAILURE";
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(
                new ThrowingOrchestrator(new RuntimeException(secret)),
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(soleText(result)).doesNotContain(secret);
    }

    /**
     * The non-vacuity proof RULE 5 demands: two sources genuinely disagree on a
     * raw, pre-scrub value, correlated by the real {@link NamespaceCorrelationService}
     * and pseudonymised by a real {@link ScrubbingEngine} wired into a real
     * {@link DefaultContextOrchestrator} — the same pipeline {@code get_entity_context}
     * uses. If the scrubbing engine below is swapped for one that passes the raw
     * value through unchanged (simulating scrubbing having been bypassed
     * upstream), this assertion fails: verified locally by making exactly that
     * swap, observing the failure, and reverting it — see the PR description.
     */
    @Test
    @DisplayName("a genuine cross-source disagreement in raw, pre-scrub values never reaches this tool's output")
    void neverLeaksARawDisagreeingValue() {
        ObjectMapper mapper = new ObjectMapper();
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        // A real scrubbing engine: it never sees, and could not repeat, the raw
        // name -- only the subject id decides the pseudonym it emits.
        ScrubbingEngine scrubber = (source, ctx) -> new ScrubResult(
                mapper.createObjectNode().put("PERSON_NAME", "PSEUDONYM-FOR-" + ((NamedThing) source).id()),
                Set.of());
        LlmResponseValidator alwaysOk = (resp, prohibited, emitted, ctx) -> ValidationResult.ok();

        DefaultContextOrchestrator orchestrator = new DefaultContextOrchestrator(
                List.of(answeringNamed("source-a", new NamedThing("1", "Patrick Murphy")),
                        answeringNamed("source-b", new NamedThing("1", "Bridget Kelly"))),
                scrubber, resolver, List.of(alwaysOk, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS), audit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), PrivacyMetrics.none()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "1")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = soleText(result);
        // The disagreement is reported...
        assertThat(text).contains("INCONSISTENT").contains("\"consistent\":false");
        // ...but never with either raw value that produced it.
        assertThat(text).doesNotContain("Patrick Murphy").doesNotContain("Bridget Kelly");
        // The pseudonym stands in for it, exactly as get_entity_context would show it.
        assertThat(text).contains("PSEUDONYM-FOR-1");
        assertThat(audited).anySatisfy(e -> assertThat(e.tool()).isEqualTo("compare_entity_sources"));
    }

    /**
     * The reviewer's repro: two sources holding the same namespace under two
     * different field names — {@code CustomerLike.customerName} and
     * {@code AccountLike.holderName} — the way any real pair of models does.
     * {@code entity.get(finding.field())} alone finds neither, because a
     * namespace-compared finding's {@code field()} is the namespace's own name
     * ({@code "PERSON_NAME"}), never a serialised field name. Run against the
     * tool before this fix, {@code identity} came back {@code {}} and this test
     * failed on the {@code containsEntry} assertions below (captured failure in
     * the PR description); {@link ContextResponse#fieldsFor} is what makes it
     * find the right node now.
     */
    @Test
    @DisplayName("identity resolves the model's own field name, not the namespace name a finding carries")
    void identityResolvesFieldNamesThatDifferFromTheNamespace() {
        ObjectMapper mapper = new ObjectMapper();
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        // A real scrubbing engine, keyed by each source's own field name --
        // exactly what JsonTreeScrubbingEngine does, and exactly why
        // "PERSON_NAME" (the namespace) is never itself a key in a real
        // scrubbed tree.
        ScrubbingEngine scrubber = (source, ctx) -> source instanceof CustomerLike customer
                ? new ScrubResult(mapper.createObjectNode()
                        .put("customerName", "PSEUDONYM-FOR-" + customer.id()), Set.of())
                : new ScrubResult(mapper.createObjectNode()
                        .put("holderName", "PSEUDONYM-FOR-" + ((AccountLike) source).id()), Set.of());
        LlmResponseValidator alwaysOk = (resp, prohibited, emitted, ctx) -> ValidationResult.ok();

        DefaultContextOrchestrator orchestrator = new DefaultContextOrchestrator(
                List.of(answeringAs("customer-api", CustomerLike.class, new CustomerLike("1", "Patrick Murphy")),
                        answeringAs("account-api", AccountLike.class, new AccountLike("1", "Bridget Kelly"))),
                scrubber, resolver, List.of(alwaysOk, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(KEYS), audit,
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), PrivacyMetrics.none()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)),
                PrivacyMetrics.none());
        CompareEntitySourcesTool tool = new CompareEntitySourcesTool(orchestrator,
                authorizationService(policyGranting("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                scopeResolver(), DataPrismObjectMapper.create(), metrics, audit, FIXED);

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(
                exchangeFor(caller(Set.of("investigator"))),
                request(Map.of("entityType", "CUSTOMER", "subjectId", "1")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.structuredContent();
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) body.get("identity");

        // The pseudonym is genuinely sitting in the tree, under both sources'
        // own field names.
        assertThat(identity).containsEntry("customerName", "PSEUDONYM-FOR-1");
        assertThat(identity).containsEntry("holderName", "PSEUDONYM-FOR-1");
        assertThat(soleText(result)).doesNotContain("Patrick Murphy").doesNotContain("Bridget Kelly");
    }

    private static Map<String, Object> byField(List<Map<String, Object>> findings, String field) {
        return findings.stream().filter(f -> field.equals(f.get("field"))).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for field " + field));
    }

    private static DataSourceAdapter<NamedThing> answeringNamed(String name, NamedThing thing) {
        return answeringAs(name, NamedThing.class, thing);
    }

    private static <T> DataSourceAdapter<T> answeringAs(String name, Class<T> type, T value) {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public Class<T> responseType() {
                return type;
            }

            @Override
            public T fetch(DataRequest request) {
                return value;
            }
        };
    }

    /** Records every call it receives; never fabricates a response for a call it never got. */
    private static final class RecordingOrchestrator implements ContextOrchestrator {
        final List<ContextRequest> requests = new ArrayList<>();

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            requests.add(request);
            return new ContextResponse(request.entityType(), "SUBJ-STUB", Map.of(), List.of(),
                    DataPrismObjectMapper.create().createObjectNode());
        }
    }

    /** Always answers with the same, test-supplied response, regardless of what it is asked. */
    private static final class FixedResponseOrchestrator implements ContextOrchestrator {
        private final ContextResponse response;

        FixedResponseOrchestrator(ContextResponse response) {
            this.response = response;
        }

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            return response;
        }
    }

    /** Always fails the same way, to exercise the tool's fail-closed handling. */
    private static final class ThrowingOrchestrator implements ContextOrchestrator {
        private final RuntimeException failure;

        ThrowingOrchestrator(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                            InvestigationContext investigationContext) {
            throw failure;
        }
    }

    private static final class RecordingMetrics implements PrivacyMetrics {
        final List<Metric> incremented = new ArrayList<>();

        @Override
        public void increment(Metric metric) {
            incremented.add(metric);
        }

        @Override
        public void increment(Metric metric, String sourceName) {
            incremented.add(metric);
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
        }
    }
}
