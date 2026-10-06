package io.github.aindriub.dataprism.example.http;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.example.DataPrismAssembly;
import io.github.aindriub.dataprism.mcp.CompareEntitySourcesTool;
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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A source whose payload carries a property the model does not declare, named
 * like personal data (task 118). The key is synthetic, at a reserved domain.
 */
final class UndeclaredKeyFixture {

    static final String TOKEN = "zzUndeclaredKeyQx7";
    static final String KEY = TOKEN + "@example.com";
    static final String NAME_VALUE = "Unlisted Fixture Person";

    private UndeclaredKeyFixture() {
    }

    /** The keyed property repeats a classified value, so a pass-through profile trips the leak check. */
    @LlmExposedModel
    record Open(@InternalIdentifier String id,
                @SensitiveData(classifications = DataClassification.PII,
                        namespace = PrivacyNamespace.PERSON_NAME,
                        suggestedAction = PrivacyAction.SYNTHESIZE)
                String name,
                @NonSensitive(reason = "carrier for undeclared properties; none are declared") @JsonAnyGetter
                Map<String, Object> extra) {
    }

    /**
     * One undeclared property beside one field that is declared on the model but never
     * annotated. Not {@code @LlmExposedModel}: the annotation processor would refuse to
     * compile the unannotated field, so {@link #mixedResolver()} exposes it by hand.
     */
    record Mixed(String id, String unreviewedNote, @JsonAnyGetter Map<String, Object> extra) {
    }

    static FieldMetadataResolver mixedResolver() {
        return new FieldMetadataResolver() {
            @Override
            public List<FieldMetadata> resolve(Class<?> type) {
                return List.of(
                        new FieldMetadata("id", true, FieldMetadata.SELF, List.of(), PrivacyNamespace.NONE,
                                null, "", null, String.class, null),
                        new FieldMetadata("unreviewedNote", false, null, List.of(), PrivacyNamespace.NONE,
                                null, "", null, String.class, null));
            }

            @Override
            public boolean exposed(Class<?> type) {
                return type == Mixed.class;
            }
        };
    }

    /** A different synthetic key for the successful-result scan (task 124). */
    static final String RESULT_TOKEN = "zzResultKeyWv4";
    static final String RESULT_KEY = RESULT_TOKEN + "@example.com";

    /** The undeclared property's value is benign, so a pass-through run still succeeds. */
    @LlmExposedModel
    record Benign(@InternalIdentifier String id,
                  @NonSensitive(reason = "carrier for undeclared properties; none are declared") @JsonAnyGetter
                  Map<String, Object> extra) {
    }

    static DataSourceAdapter<Benign> benignAdapter() {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "benign-api";
            }

            @Override
            public Class<Benign> responseType() {
                return Benign.class;
            }

            @Override
            public Benign fetch(DataRequest request) {
                return new Benign("123", Map.of(RESULT_KEY, "benign-note"));
            }
        };
    }

    static DataSourceAdapter<Open> adapter() {
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "open-api";
            }

            @Override
            public Class<Open> responseType() {
                return Open.class;
            }

            @Override
            public Open fetch(DataRequest request) {
                return new Open("123", NAME_VALUE, Map.of(KEY, NAME_VALUE));
            }
        };
    }

    static Map<String, PrivacyProfile> profiles(PrivacyProfile.UnclassifiedBehaviour behaviour) {
        return Map.of("DEFAULT", new PrivacyProfile("DEFAULT", behaviour,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT))));
    }

    /**
     * Drives both tools against the source under {@code behaviour}, writing every
     * audit event to {@code sink}, and returns each result's whole text.
     */
    static List<String> run(PrivacyProfile.UnclassifiedBehaviour behaviour, AuditSink sink, Clock clock) {
        String purpose = "demonstration";
        String role = "investigator";
        DataPrismAssembly assembly = new DataPrismAssembly(List.of(adapter()), clock, sink, "DEFAULT", "en",
                profiles(behaviour));
        AuditRecorder toolAudit = new AuditRecorder(sink, clock, "undeclared-key-mcp");
        SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(purpose)));
        GetEntityContextTool get = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, clock);
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(assembly.orchestrator(),
                authorizationService, scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(),
                toolAudit, clock);
        AuthenticatedCaller caller = new AuthenticatedCaller(
                "undeclared-key-principal", "undeclared-key-client", Set.of(role), purpose, "CASE-UK-1", null);
        McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                "undeclared-key-session", null, null, null,
                McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));

        List<String> results = new ArrayList<>();
        results.add(whole(get.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")))));
        results.add(whole(compare.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                CompareEntitySourcesTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123")))));
        return results;
    }

    /** As {@link #run}, but against {@link #benignAdapter()} and returning the results themselves. */
    static List<McpSchema.CallToolResult> runBenign(PrivacyProfile.UnclassifiedBehaviour behaviour,
                                                    AuditSink sink, Clock clock) {
        String purpose = "demonstration";
        String role = "investigator";
        DataPrismAssembly assembly = new DataPrismAssembly(List.of(benignAdapter()), clock, sink, "DEFAULT", "en",
                profiles(behaviour));
        AuditRecorder toolAudit = new AuditRecorder(sink, clock, "result-key-mcp");
        SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(purpose)));
        GetEntityContextTool get = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, clock);
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(assembly.orchestrator(),
                authorizationService, scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(),
                toolAudit, clock);
        AuthenticatedCaller caller = new AuthenticatedCaller(
                "result-key-principal", "result-key-client", Set.of(role), purpose, "CASE-RK-1", null);
        McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                "result-key-session", null, null, null,
                McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));

        List<McpSchema.CallToolResult> results = new ArrayList<>();
        results.add(get.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123"))));
        results.add(compare.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                CompareEntitySourcesTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "123"))));
        return results;
    }

    private static String whole(McpSchema.CallToolResult result) {
        return result.toString();
    }
}
