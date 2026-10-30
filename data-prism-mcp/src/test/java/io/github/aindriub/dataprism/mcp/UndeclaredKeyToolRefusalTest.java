package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.policy.EffectivePrivacyPolicy;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text an MCP tool hands the model for a refusal never carries a payload
 * key, on either tool and for each refusal code that can reach one (task 118).
 */
class UndeclaredKeyToolRefusalTest {

    /** Synthetic; reserved domain. */
    private static final String KEY = "zzUndeclaredKeyQx7@example.com";
    private static final String TOKEN = "zzUndeclaredKeyQx7";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-118-test-key-not-for-any-real-data-32b");
    private static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);
    private static final Map<String, Object> ARGS = Map.of("entityType", "THING", "subjectId", "1");

    /** The declared fields of the exposed tree. */
    @LlmExposedModel
    record Open(@InternalIdentifier String id) {
    }

    @LlmExposedModel
    record WithEmail(@InternalIdentifier String id, @InternalIdentifier String email) {
    }

    /**
     * Exposes an {@link ObjectNode} root with the declared fields of a record. A record source can no longer
     * carry undeclared keys (task 288, OD-288-1 (a)); a JsonNode root can, as a configured JSON source does.
     */
    static final class ExposedTree implements FieldMetadataResolver {
        private final DefaultFieldMetadataResolver delegate = new DefaultFieldMetadataResolver();
        private final Class<?> declared;

        ExposedTree(Class<?> declared) {
            this.declared = declared;
        }

        @Override
        public List<FieldMetadata> resolve(Class<?> type) {
            return delegate.resolve(type == ObjectNode.class ? declared : type);
        }

        @Override
        public boolean exposed(Class<?> type) {
            return type == ObjectNode.class || delegate.exposed(type);
        }
    }

    /** The tree a record with an any-getter used to write: the declared properties, then the undeclared key. */
    private static ObjectNode tree(Map<String, String> declared, String key, Object value) {
        ObjectNode tree = io.github.aindriub.dataprism.core.engine.SourceTree.newObject();
        declared.forEach(tree::put);
        if (value instanceof Map<?, ?> nested) {
            ObjectNode inner = tree.putObject(key);
            nested.forEach((k, v) -> inner.put((String) k, (String) v));
        } else {
            tree.put(key, (String) value);
        }
        return tree;
    }

    private final List<AuditEvent> audited = new ArrayList<>();

    private static PrivacyPolicyResolver profile(PrivacyProfile.UnclassifiedBehaviour behaviour) {
        return new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", new PrivacyProfile("DEFAULT", behaviour,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)))));
    }

    /** Lets an undeclared property through, then refuses the structure beneath it. */
    private static PrivacyPolicyResolver refusesStructureOnly() {
        AtomicInteger undeclaredCalls = new AtomicInteger();
        return (field, ctx) -> {
            if (field.declared()) {
                return new EffectivePrivacyPolicy(PrivacyAction.REMOVE, field.namespace(), true, "DEFAULT",
                        EffectivePrivacyPolicy.Decided.IDENTIFIER);
            }
            return undeclaredCalls.getAndIncrement() % 2 == 0
                    ? new EffectivePrivacyPolicy(PrivacyAction.PASS_THROUGH, field.namespace(), true, "DEFAULT",
                            EffectivePrivacyPolicy.Decided.UNCLASSIFIED)
                    : EffectivePrivacyPolicy.refuse("DEFAULT");
        };
    }

    private DefaultContextOrchestrator orchestrator(PrivacyPolicyResolver policies, Object value) {
        return orchestrator(policies, Open.class, tree(Map.of("id", "1"), KEY, value));
    }

    private DefaultContextOrchestrator orchestrator(PrivacyPolicyResolver policies, Class<?> declared,
                                                    ObjectNode payload) {
        Class<ObjectNode> type = ObjectNode.class;
        FieldMetadataResolver resolver = new ExposedTree(declared);
        var scrubber = new JsonTreeScrubbingEngine(resolver, policies, (subject, namespace, ctx) -> "synthetic");
        DataSourceAdapter<ObjectNode> adapter = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "open-api";
            }

            @Override
            public Class<ObjectNode> responseType() {
                return type;
            }

            @Override
            public ObjectNode fetch(DataRequest request) {
                return payload;
            }
        };
        return new DefaultContextOrchestrator(List.of(adapter), scrubber, resolver,
                List.of(new RawValueLeakValidator(), new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(audited::add, FIXED, "test-mcp"), new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none(), OrchestrationOptions.defaults());
    }

    private void assertBothToolsRefuse(DefaultContextOrchestrator orchestrator, String code) {
        assertBothToolsRefuse(orchestrator, code, TOKEN);
    }

    private void assertBothToolsRefuse(DefaultContextOrchestrator orchestrator, String code, String forbidden) {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        AuditRecorder toolAudit = new AuditRecorder(audited::add, FIXED, "test-mcp");
        var get = new GetEntityContextTool(orchestrator, authz, scopes, PrivacyMetrics.none(), toolAudit, FIXED, null, ToolOptions.defaults().noAdmission().build());
        var compare = new CompareEntitySourcesTool(orchestrator, authz, scopes, PrivacyMetrics.none(), toolAudit, FIXED, null, ToolOptions.defaults().noAdmission().build());

        var handlers = Map.of(GetEntityContextTool.NAME, get.specification().callHandler(),
                CompareEntitySourcesTool.NAME, compare.specification().callHandler());
        handlers.forEach((name, handler) -> {
            audited.clear();
            McpTransportContext context = McpTransportContext.create(
                    Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, CALLER));
            McpSchema.CallToolResult result = handler.apply(
                    new McpSyncServerExchange(new McpAsyncServerExchange("s", null, null, null, context)),
                    new McpSchema.CallToolRequest(name, ARGS));

            assertThat(result.isError()).as(name).isEqualTo(Boolean.TRUE);
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).as(name).contains(code).doesNotContain(forbidden);
            assertThat(result.toString()).as(name + " whole result").doesNotContain(forbidden);
            assertThat(audited.toString()).as(name + " audit").doesNotContain(forbidden);
        });
    }

    @Test
    @DisplayName("UNKNOWN_FIELD refusal text names the code and not the key, on both tools")
    void unknownField() {
        assertBothToolsRefuse(orchestrator(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST), "v"),
                "UNKNOWN_FIELD");
    }

    @Test
    @DisplayName("VALIDATION_FAILED refusal text names the code and not the key, on both tools")
    void validationFailed() {
        assertBothToolsRefuse(orchestrator(profile(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE),
                "someone.else@example.com"), "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("UNCLASSIFIED_STRUCTURE refusal text names the code and not the key, on both tools")
    void unclassifiedStructure() {
        // A fresh resolver per orchestrator and the alternation inside it make this hold for both tool calls.
        assertBothToolsRefuse(orchestrator(refusesStructureOnly(), Map.of("inner", "v")),
                "UNCLASSIFIED_STRUCTURE");
    }

    @Test
    @DisplayName("VALIDATION_FAILED text carries no digits from a bracketed payload key after a declared name")
    void bracketedDigitKey() {
        var payload = tree(Map.of("id", "1", "email", "plain"), "email[07700900123]",
                "someone.else@example.com");
        assertBothToolsRefuse(orchestrator(profile(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE),
                WithEmail.class, payload), "VALIDATION_FAILED", "07700900123");
    }
}
