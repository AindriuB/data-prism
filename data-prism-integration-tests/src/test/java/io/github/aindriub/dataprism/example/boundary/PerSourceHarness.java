package io.github.aindriub.dataprism.example.boundary;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.SourceTree;
import io.github.aindriub.dataprism.example.http.UndeclaredKeyFixture;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.mcp.ToolOptions;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the real pipeline (real scrubber, real validators, the real tool and its mapper) over invented
 * sources, with source names exposed so a test can read each source's entry by its own name.
 */
final class PerSourceHarness {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
    private static final String PURPOSE = "demonstration";
    private static final String ROLE = "investigator";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PerSourceHarness() {
    }

    /** A well-formed record: every field classified. */
    @LlmExposedModel
    public record Plain(
            @InternalIdentifier String id,
            @NonSensitive(reason = "Enumerated test state") String status,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String name) {
    }

    /** Refuses in validation: {@code note} passes the scrub as declared non-sensitive but is an address shape. */
    @LlmExposedModel
    public record Shaped(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String badName,
            @NonSensitive(reason = "Test field") String note) {
    }

    /** The same shape as {@link #scrubRefusing} and {@link Shaped} without the offending field. */
    @LlmExposedModel
    public record Clean(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String badName) {
    }

    /** Carries another source's sensitive raw value in a field declared non-sensitive. */
    @LlmExposedModel
    public record Leaky(
            @InternalIdentifier String id,
            @NonSensitive(reason = "Test field") String note) {
    }

    static final String BAD_NAME = "Zelda Quigley";
    static final String BAD_FIELD = "badName";
    static final String STRAY_KEY = "stray.key@example.invalid";
    static final String STRAY_VALUE = "stray-marker-value";
    static final String NOTE_VALUE = "shape.marker@example.invalid";

    static <T> DataSourceAdapter<T> source(String name, Class<T> type, T record) {
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
                return record;
            }
        };
    }

    static DataSourceAdapter<?> plain(String name, String status, String personName) {
        return source(name, Plain.class, new Plain("1", status, personName));
    }

    static DataSourceAdapter<?> empty(String name) {
        return source(name, Plain.class, null);
    }

    /**
     * Refuses in scrub: the payload carries a property the model does not declare, and the default profile
     * fails the request on an unclassified field. The key is itself identifying-looking, as a payload key can be.
     * A record source can no longer carry undeclared keys (task 288, OD-288-1 (a)), so this is a configured JSON
     * source: the catalogue declares {@code id} and {@code badName}, and the body adds the stray key.
     */
    static DataSourceAdapter<?> scrubRefusing(String name) {
        ObjectNode body = SourceTree.newObject();
        body.put("id", "1");
        body.put(BAD_FIELD, BAD_NAME);
        body.put(STRAY_KEY, STRAY_VALUE);
        return source(name, ObjectNode.class, body);
    }

    /**
     * Exposes an {@link ObjectNode} root with the fields {@link #scrubRefusing} declares, and every other model as
     * the default resolver does. This is the exposed-tree carrier rather than a configured JSON source because the
     * orchestrator's cross-source oracle ({@code SourceValues.prohibited}) must see the refused source's raw name
     * ("a refused source's raw value stays prohibited"): a configured source computes its prohibited set inside its
     * own engine, against its own catalogue, where the orchestrator cannot read it.
     */
    private static final class ExposedTree implements FieldMetadataResolver {
        private final DefaultFieldMetadataResolver delegate = new DefaultFieldMetadataResolver();

        @Override
        public List<FieldMetadata> resolve(Class<?> type) {
            return type == ObjectNode.class
                    ? List.of(UndeclaredKeyFixture.identifier("id"), UndeclaredKeyFixture.personName(BAD_FIELD))
                    : delegate.resolve(type);
        }

        @Override
        public boolean exposed(Class<?> type) {
            return type == ObjectNode.class || delegate.exposed(type);
        }
    }

    static DataSourceAdapter<?> validationRefusing(String name) {
        return source(name, Shaped.class, new Shaped("1", BAD_NAME, NOTE_VALUE));
    }

    /** An otherwise valid source whose note repeats the refused sources' raw name. */
    static DataSourceAdapter<?> leaky(String name) {
        return source(name, Leaky.class, new Leaky("1", BAD_NAME));
    }

    static DataSourceAdapter<?> clean(String name) {
        return source(name, Clean.class, new Clean("1", BAD_NAME));
    }

    /** One tool call and the orchestrator's audit events for it. */
    record Call(McpSchema.CallToolResult result, List<AuditEvent> events) {

        String text() {
            return ((McpSchema.TextContent) result.content().get(0)).text();
        }

        /** The structured content as the client receives it. */
        String structured() {
            return JSON.writeValueAsString(result.structuredContent());
        }

        JsonNode json() {
            return JSON.readTree(text());
        }

        AuditEvent event() {
            return events.stream().filter(e -> "get_entity_context".equals(e.tool())).reduce((a, b) -> b)
                    .orElseThrow();
        }
    }

    static Call call(List<DataSourceAdapter<?>> adapters) {
        List<AuditEvent> events = new ArrayList<>();
        UndeclaredKeyFixture.Assembly assembly = new UndeclaredKeyFixture.Assembly(adapters, new ExposedTree(), CLOCK,
                events::add, UndeclaredKeyFixture.defaultProfiles());
        AuditRecorder toolAudit = new AuditRecorder(e -> { }, CLOCK, "per-source-mcp");
        SecurityPolicy policy = new SecurityPolicy(Set.of(PURPOSE), Map.of(ROLE,
                Set.of(Capability.GET_ENTITY_CONTEXT, Capability.EXPOSE_SOURCE_NAMES)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(),
                Duration.ofHours(8), new PurposeValidator(Set.of(PURPOSE)), CaseAuthorizer.tokenTrusted());
        GetEntityContextTool tool = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, PrivacyMetrics.none(), toolAudit, CLOCK, null,
                ToolOptions.defaults().noAdmission().build());
        AuthenticatedCaller caller = new AuthenticatedCaller(
                "per-source-principal", "per-source-client", Set.of(ROLE), PURPOSE, "CASE-PER-SOURCE", null);
        McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                "per-source-session", null, null, null,
                McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));
        McpSchema.CallToolResult result = tool.specification().callHandler().apply(exchange,
                new McpSchema.CallToolRequest(GetEntityContextTool.NAME,
                        Map.of("entityType", "THING", "subjectId", "1")));
        return new Call(result, events);
    }

    /** Every string leaf of every entity the response carries, in either response shape. */
    static Set<String> entityStrings(JsonNode response) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (response.has("entity")) {
            collect(response.get("entity"), out);
        }
        JsonNode sources = response.get("sources");
        if (sources != null) {
            sources.forEachEntry((name, entry) -> {
                if (entry.isObject() && entry.has("entity")) {
                    collect(entry.get("entity"), out);
                }
            });
        }
        return out;
    }

    private static void collect(JsonNode node, Set<String> out) {
        if (node.isString()) {
            out.add(node.asString());
        } else if (node.isObject()) {
            node.forEachEntry((k, v) -> collect(v, out));
        } else if (node.isArray()) {
            node.forEach(v -> collect(v, out));
        }
    }
}
