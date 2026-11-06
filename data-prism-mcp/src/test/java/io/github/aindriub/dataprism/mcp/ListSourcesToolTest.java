package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueries;
import io.github.aindriub.dataprism.orchestration.catalog.PagingSettings;
import io.github.aindriub.dataprism.orchestration.catalog.RealNamesPolicy;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceVisibility;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code list_sources} as a tool: authorisation, admission, audit, paging and the served schema. */
class ListSourcesToolTest {

    static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-250-test-key-not-for-any-real-data-32b");
    static final AuthenticatedCaller CALLER =
            new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration", "case-1", null);

    static final class CountingMetrics implements PrivacyMetrics {
        final Map<Metric, Integer> counts = new EnumMap<>(Metric.class);

        @Override
        public void increment(Metric metric) {
            counts.merge(metric, 1, Integer::sum);
        }

        @Override
        public void increment(Metric metric, String sourceName) {
            increment(metric);
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
        }
    }

    static final class MapKeys implements SecretKeyProvider {
        @Override
        public byte[] secret(String keyId) {
            return switch (keyId) {
                case "pseudo-key" -> "pseudonymisation-key-0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
                case "link-key" -> "link-key-material-0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII);
                default -> throw new IllegalStateException("unknown key");
            };
        }
    }

    record Thing(String id) {
    }

    /** One server-side environment: sources, a catalog, both tools and the audit events they wrote. */
    static final class Env {
        final List<AuditEvent> audited = new ArrayList<>();
        final AuditRecorder audit = new AuditRecorder(audited::add, FIXED, "test-mcp");
        final CountingMetrics metrics = new CountingMetrics();
        final CatalogQueries queries;
        final ListSourcesTool list;
        final DescribeSourceTool describe;
        final List<DataSourceAdapter<?>> adapters = new ArrayList<>();

        Env(Set<String> capabilities, int sources, boolean links, ToolOptions options, int pageSize) {
            this(capabilities, sources, links, options, pageSize, i -> String.format("src-%03d", i), "state");
        }

        Env(Set<String> capabilities, int sources, boolean links, ToolOptions options, int pageSize,
            IntFunction<String> naming, String statePath) {
            for (int i = 1; i <= sources; i++) {
                String name = naming.apply(i);
                adapters.add(new DataSourceAdapter<Thing>() {
                    @Override
                    public String sourceName() {
                        return name;
                    }

                    @Override
                    public Class<Thing> responseType() {
                        return Thing.class;
                    }

                    @Override
                    public Thing fetch(DataRequest request) {
                        throw new AssertionError("the catalog tools never call a source");
                    }
                });
            }
            ScrubbingEngine engine = new ScrubbingEngine() {
                @Override
                public ScrubResult scrub(Object source, PrivacyContext context) {
                    throw new AssertionError("the catalog tools never scrub");
                }

                @Override
                public Optional<ModelDescription> describe(Class<?> model, PrivacyContext context, boolean links) {
                    return Optional.of(new ModelDescription(List.of(
                            new FieldProtection("id", FieldProtection.Protection.ABSENT, null, "thing-ref"),
                            new FieldProtection(statePath, FieldProtection.Protection.VERBATIM, null, null)),
                            ModelDescription.Undeclared.REFUSE));
                }
            };
            LinkKeys keys = new LinkKeys(new MapKeys(), "link-key", "pseudo-key");
            LinkCodec codec = new LinkCodec(
                    new LinkSettings("link-key", Optional.empty(), 1024, true, Duration.ofSeconds(30)), keys, FIXED);
            queries = new CatalogQueries(SourceCatalog.build(adapters, Map.of()), SourceVisibility.allowAll(),
                    new SourceAliasing(new HmacValueTokenSource(KEYS)), new RealNamesPolicy(List.of("*")),
                    new ContextOrchestrator() {
                        @Override
                        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacy,
                                                            InvestigationContext caller) {
                            throw new AssertionError("the catalog tools never build context");
                        }

                        @Override
                        public Optional<ModelDescription> describe(String sourceName, PrivacyContext privacy,
                                                                   boolean links) {
                            return adapters.stream().filter(a -> a.sourceName().equals(sourceName)).findFirst()
                                    .flatMap(a -> engine.describe(sourceName, a.responseType(), privacy, links));
                        }
                    }, List.of(new SensitivePatternValidator()),
                    new PagingSettings(pageSize, 100, Duration.ofMinutes(15)),
                    links ? new CatalogQueries.PageLinks(codec, keys, new LinkAttemptGate(codec,
                            new InMemoryLinkAttemptLimiter(5, Duration.ofMinutes(10), FIXED))) : null,
                    true, metrics, FIXED);
            SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"), Map.of("investigator", capabilities));
            AuthorizationService authz = new AuthorizationService(security, "DEFAULT",
                    PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
            ScopeResolver scopes = new ScopeResolver(
                    PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                    Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
            list = new ListSourcesTool(queries, 1024, authz, scopes, metrics, audit, FIXED, null, options);
            describe = new DescribeSourceTool(queries, authz, scopes, metrics, audit, FIXED, null, options);
        }

        Env(Set<String> capabilities, int sources) {
            this(capabilities, sources, true, ToolOptions.defaults().noAdmission().build(), 2);
        }
    }

    static McpSchema.CallToolResult call(McpServerFeatures.SyncToolSpecification spec, Map<String, Object> arguments) {
        return callAs(spec, CALLER, arguments);
    }

    static McpSchema.CallToolResult callAs(McpServerFeatures.SyncToolSpecification spec, AuthenticatedCaller caller,
                                           Map<String, Object> arguments) {
        Map<String, Object> context = caller == null ? Map.of()
                : Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller);
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
                spec.callHandler();
        return handler.apply(new McpSyncServerExchange(new McpAsyncServerExchange("s", null, null, null,
                McpTransportContext.create(context))), new McpSchema.CallToolRequest(spec.tool().name(), arguments));
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    static List<String> labels(McpSchema.CallToolResult result) {
        List<Map<String, Object>> sources = (List<Map<String, Object>>) ((Map<String, Object>) result.structuredContent())
                .get("sources");
        return sources.stream().map(s -> (String) s.get("source")).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> page(McpSchema.CallToolResult result) {
        return (Map<String, Object>) ((Map<String, Object>) result.structuredContent()).get("page");
    }

    static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    static final Set<String> LIST_AND_NAMES = Set.of("LIST_SOURCES", "EXPOSE_SOURCE_NAMES");

    // ---- authorisation and admission ----

    @Test
    @DisplayName("a role without LIST_SOURCES gets TOOL_NOT_PERMITTED, audited under list_sources")
    void withoutTheCapabilityIsRefused() {
        Env env = new Env(Set.of("GET_ENTITY_CONTEXT"), 3);

        McpSchema.CallToolResult result = call(env.list.specification(), args());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("TOOL_NOT_PERMITTED");
        assertThat(env.audited).hasSize(1);
        assertThat(env.audited.get(0).tool()).isEqualTo("list_sources");
        assertThat(env.audited.get(0).policyDecision()).isEqualTo("DENY:TOOL_NOT_PERMITTED");
    }

    @Test
    @DisplayName("a call with no authenticated caller is refused and audited")
    void unauthenticatedIsRefused() {
        Env env = new Env(Set.of("LIST_SOURCES"), 3);

        McpSchema.CallToolResult result = callAs(env.list.specification(), null, args());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("NO_AUTHENTICATED_CALLER");
        assertThat(env.audited).singleElement().satisfies(event -> {
            assertThat(event.tool()).isEqualTo("list_sources");
            assertThat(event.policyDecision()).isEqualTo("DENY:NO_AUTHENTICATED_CALLER");
        });
    }

    @Test
    @DisplayName("admission runs under the tool's own name: approval required for list_sources only")
    void admissionUsesTheOwnToolName() {
        InMemoryApprovalStore approvals = new InMemoryApprovalStore();
        ToolAdmission admission = new ToolAdmission(new InMemoryOversightState(), approvals,
                new InMemoryCallerRateLimiter(), new OversightPolicy(Set.of("list_sources"), OptionalInt.empty(),
                Duration.ofMinutes(1), Duration.ofHours(1)), FIXED);
        Env env = new Env(Set.of("LIST_SOURCES", "DESCRIBE_SOURCE"), 3, true,
                ToolOptions.defaults().admission(admission, new ParameterFingerprinter(KEYS)).build(), 2);

        McpSchema.CallToolResult listed = call(env.list.specification(), args());
        McpSchema.CallToolResult described = call(env.describe.specification(), args("source", "nothing"));

        assertThat(text(listed)).startsWith("APPROVAL_REQUIRED approvalId=");
        assertThat(text(described)).isEqualTo("SOURCE_NOT_AVAILABLE");
    }

    private static Env approvalEnv(InMemoryApprovalStore approvals, String... tools) {
        ToolAdmission admission = new ToolAdmission(new InMemoryOversightState(), approvals,
                new InMemoryCallerRateLimiter(), new OversightPolicy(Set.of(tools), OptionalInt.empty(),
                Duration.ofMinutes(1), Duration.ofHours(1)), FIXED);
        return new Env(Set.of("LIST_SOURCES", "DESCRIBE_SOURCE", "EXPOSE_SOURCE_NAMES"), 7, true,
                ToolOptions.defaults().admission(admission, new ParameterFingerprinter(KEYS)).build(), 2);
    }

    /** Calls, and if an approval is required approves it as approver-9 and calls again. */
    private static McpSchema.CallToolResult approvedCall(Env env, InMemoryApprovalStore approvals,
                                                         McpServerFeatures.SyncToolSpecification spec,
                                                         Map<String, Object> arguments) {
        McpSchema.CallToolResult first = call(spec, arguments);
        assertThat(text(first)).startsWith("APPROVAL_REQUIRED approvalId=");
        approvals.approve(env.audited.get(env.audited.size() - 1).approvalId(), "approver-9", FIXED.instant());
        return call(spec, arguments);
    }

    @Test
    @DisplayName("an approval covers exactly the page request it was granted for, not another link or the first page")
    void approvalIsBoundToTheExactPageToken() {
        InMemoryApprovalStore approvals = new InMemoryApprovalStore();
        Env env = approvalEnv(approvals, "list_sources");
        var spec = env.list.specification();
        McpSchema.CallToolResult p1 = approvedCall(env, approvals, spec, args());
        String linkA = (String) page(p1).get("next");
        McpSchema.CallToolResult p2 = approvedCall(env, approvals, spec, args("page", linkA));
        String linkB = (String) page(p2).get("next");
        assertThat(linkB).isNotEqualTo(linkA);

        // approve link A without spending it, then present link B, the first page and link A
        call(spec, args("page", linkA));
        approvals.approve(env.audited.get(env.audited.size() - 1).approvalId(), "approver-9", FIXED.instant());

        assertThat(text(call(spec, args("page", linkB)))).startsWith("APPROVAL_REQUIRED approvalId=");
        assertThat(text(call(spec, args()))).startsWith("APPROVAL_REQUIRED approvalId=");
        McpSchema.CallToolResult retried = call(spec, args("page", linkA));
        assertThat(retried.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(labels(retried)).isNotEmpty();
    }

    @Test
    @DisplayName("an approval for a continuation does not cover the first page")
    void continuationApprovalDoesNotCoverTheFirstPage() {
        InMemoryApprovalStore approvals = new InMemoryApprovalStore();
        Env env = approvalEnv(approvals, "list_sources");
        var spec = env.list.specification();
        String linkA = (String) page(approvedCall(env, approvals, spec, args())).get("next");
        call(spec, args("page", linkA));
        approvals.approve(env.audited.get(env.audited.size() - 1).approvalId(), "approver-9", FIXED.instant());

        assertThat(text(call(spec, args()))).startsWith("APPROVAL_REQUIRED approvalId=");
    }

    @Test
    @DisplayName("an approved list_sources call audits the approval id and the approver id")
    void approvedListAuditsApproval() {
        InMemoryApprovalStore approvals = new InMemoryApprovalStore();
        Env env = approvalEnv(approvals, "list_sources");

        approvedCall(env, approvals, env.list.specification(), args());

        AuditEvent allow = env.audited.get(env.audited.size() - 1);
        assertThat(allow.policyDecision()).isEqualTo("ALLOW");
        assertThat(allow.approvalId()).isEqualTo(env.audited.get(env.audited.size() - 2).approvalId()).isNotBlank();
        assertThat(allow.approverId()).isEqualTo("approver-9");
    }

    @Test
    @DisplayName("an approved describe_source call audits the approval id and the approver id")
    void approvedDescribeAuditsApproval() {
        InMemoryApprovalStore approvals = new InMemoryApprovalStore();
        Env env = approvalEnv(approvals, "describe_source");

        approvedCall(env, approvals, env.describe.specification(), args("source", "src-001"));

        AuditEvent allow = env.audited.get(env.audited.size() - 1);
        assertThat(allow.policyDecision()).isEqualTo("ALLOW");
        assertThat(allow.approvalId()).isEqualTo(env.audited.get(env.audited.size() - 2).approvalId()).isNotBlank();
        assertThat(allow.approverId()).isEqualTo("approver-9");
    }

    // ---- the answer and its audit ----

    @Test
    @DisplayName("a permitted call returns the first page and writes one audit event with real names")
    void successAndAudit() {
        Env env = new Env(LIST_AND_NAMES, 5);

        McpSchema.CallToolResult result = call(env.list.specification(), args());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(labels(result)).containsExactly("src-001", "src-002");
        assertThat(page(result)).containsEntry("last", false).containsEntry("size", 2).containsKeys("next", "expiresAt");
        assertThat(result.meta()).containsKey(ToolCalls.CORRELATION_ID_META_KEY);
        assertThat(env.audited).hasSize(1);
        AuditEvent event = env.audited.get(0);
        assertThat(event.tool()).isEqualTo("list_sources");
        assertThat(event.policyDecision()).isEqualTo("ALLOW");
        assertThat(event.sourceSystems()).containsExactlyInAnyOrder("src-001", "src-002");
        assertThat(event.fieldDispositions()).containsEntry("names", "REAL");
        assertThat(event.correlationId()).isEqualTo(result.meta().get(ToolCalls.CORRELATION_ID_META_KEY));
        assertThat(env.metrics.counts.get(Metric.PAGING_PAGES)).isEqualTo(1);
    }

    @Test
    @DisplayName("without EXPOSE_SOURCE_NAMES the labels are aliases and the audit still holds the real names")
    void aliasesForTheCallerAndRealNamesInTheAudit() {
        Env env = new Env(Set.of("LIST_SOURCES"), 3, false, ToolOptions.defaults().noAdmission().build(), 2);

        McpSchema.CallToolResult result = call(env.list.specification(), args());

        assertThat(((Map<?, ?>) result.structuredContent()).get("labels")).isEqualTo("ALIAS");
        assertThat(labels(result)).hasSize(3).doesNotContain("src-001", "src-002", "src-003");
        assertThat(text(result)).doesNotContain("src-00");
        assertThat(env.audited.get(0).sourceSystems()).containsExactlyInAnyOrder("src-001", "src-002", "src-003");
        assertThat(env.audited.get(0).fieldDispositions()).containsEntry("names", "ALIAS");
    }

    @Test
    @DisplayName("the next page resumes the walk; a stale link is refused as LINK_INVALID and audited with its reason")
    void walkAndRefusal() {
        Env env = new Env(LIST_AND_NAMES, 5);
        McpSchema.CallToolResult first = call(env.list.specification(), args());
        String next = (String) page(first).get("next");

        McpSchema.CallToolResult second = call(env.list.specification(), args("page", next));
        McpSchema.CallToolResult filtered = call(env.list.specification(), args("page", next, "entityType", "PARCEL"));

        assertThat(labels(second)).containsExactly("src-003", "src-004");
        assertThat(filtered.isError()).isTrue();
        assertThat(text(filtered)).isEqualTo("The link is not valid.");
        AuditEvent refused = env.audited.get(env.audited.size() - 1);
        assertThat(refused.policyDecision()).isEqualTo("DENY:LINK_INVALID");
        assertThat(refused.fieldDispositions()).containsEntry("link", "REFUSED:LINK_FILTER_MISMATCH")
                .containsEntry("names", "REAL");
        assertThat(refused.sourceSystems()).isEmpty();
    }

    @Test
    @DisplayName("no client-sent filter, label or page token appears in any audit record or result text")
    void noClientTextIsRecorded() {
        Env env = new Env(LIST_AND_NAMES, 5);
        String token = "SENTINEL-TOKEN-AAAAAAAAAAAAAAAAAAAAAAAA";

        McpSchema.CallToolResult result = call(env.list.specification(),
                args("keyNamespace", "sentinel-ns", "page", token, "entityType", "SENTINEL_TYPE"));

        assertThat(text(result)).doesNotContain("SENTINEL").doesNotContain("sentinel-ns");
        assertThat(env.audited.toString()).doesNotContain("SENTINEL").doesNotContain("sentinel-ns");
        assertThat(env.audited.get(0).entityType()).isEqualTo(AuditedEntityTypes.UNREGISTERED);
    }

    @Test
    @DisplayName("an entityType filter is audited only when it is a registered value")
    void registeredEntityTypeFilterIsAudited() {
        Env env = new Env(LIST_AND_NAMES, 2, true, ToolOptions.defaults().noAdmission()
                .entityTypes(AuditedEntityTypes.of(List.of("PARCEL"))).build(), 2);

        call(env.list.specification(), args("entityType", "PARCEL"));
        call(env.list.specification(), args("entityType", "OTHER"));

        assertThat(env.audited.get(0).entityType()).isEqualTo("PARCEL");
        assertThat(env.audited.get(1).entityType()).isEqualTo(AuditedEntityTypes.UNREGISTERED);
    }

    @Test
    @DisplayName("a page link refusal says what the design says, not the bare code")
    void linkRefusalText() {
        Env env = new Env(LIST_AND_NAMES, 5);
        String next = (String) page(call(env.list.specification(), args())).get("next");

        McpSchema.CallToolResult refused = call(env.list.specification(),
                args("page", next, "entityType", "PARCEL"));

        assertThat(refused.isError()).isTrue();
        assertThat(text(refused)).isEqualTo("The link is not valid.");
        assertThat(env.audited.get(env.audited.size() - 1).policyDecision()).isEqualTo("DENY:LINK_INVALID");
    }

    @Test
    @DisplayName("output the served schema rejects is refused and audited as a refusal, never ALLOW")
    void outputOverTheSchemaIsRefusedBeforeTheAudit() {
        DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
        Env longPath = new Env(Set.of("DESCRIBE_SOURCE", "EXPOSE_SOURCE_NAMES"), 1, true,
                ToolOptions.defaults().noAdmission().build(), 2, i -> "ledger", "a".repeat(69));
        Env longName = new Env(LIST_AND_NAMES, 1, true, ToolOptions.defaults().noAdmission().build(), 2,
                i -> "n".repeat(129), "state");

        McpSchema.CallToolResult described = call(longPath.describe.specification(), args("source", "ledger"));
        McpSchema.CallToolResult listed = call(longName.list.specification(), args());

        for (Object[] c : new Object[][] {{described, longPath}, {listed, longName}}) {
            McpSchema.CallToolResult result = (McpSchema.CallToolResult) c[0];
            Env env = (Env) c[1];
            // Whatever is served must pass the schema the SDK applies; here nothing structured is served.
            assertThat(result.isError()).isTrue();
            assertThat(result.structuredContent()).isNull();
            assertThat(env.audited).hasSize(1);
            assertThat(env.audited.get(0).policyDecision()).startsWith("DENY:").isNotEqualTo("ALLOW");
        }

        // At the cap the answer is served, and the served schema agrees.
        Env atCap = new Env(Set.of("DESCRIBE_SOURCE", "EXPOSE_SOURCE_NAMES"), 1, true,
                ToolOptions.defaults().noAdmission().build(), 2, i -> "n".repeat(128), "a".repeat(64));
        McpServerFeatures.SyncToolSpecification spec = atCap.describe.specification();
        McpSchema.CallToolResult served = call(spec, args("source", "n".repeat(128)));
        assertThat(served.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(validator.validate(spec.tool().outputSchema(), served.structuredContent()).valid()).isTrue();
        assertThat(atCap.audited.get(0).policyDecision()).isEqualTo("ALLOW");
    }

    @Test
    @DisplayName("an argument that breaks the schema is refused without echoing it, and nothing is listed")
    void invalidArguments() {
        Env env = new Env(LIST_AND_NAMES, 5);

        for (Map<String, Object> bad : List.of(args("pageSize", 0), args("pageSize", "2"), args("pageSize", 1.5),
                args("entityType", "bad type"), args("keyNamespace", "UPPER"), args("page", 12))) {
            McpSchema.CallToolResult result = call(env.list.specification(), bad);
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).isEqualTo("list_sources arguments are not valid");
        }
        assertThat(env.audited).isEmpty();
    }

    @Test
    @DisplayName("reserved arguments are ignored and audited by name")
    void reservedArgumentsAreAudited() {
        Env env = new Env(LIST_AND_NAMES, 3);

        call(env.list.specification(), args("scopeId", "other-scope", "principalId", "someone"));

        assertThat(env.audited.get(0).rejectedArguments()).contains("scopeId", "principalId");
        assertThat(env.audited.get(0).principalId()).isEqualTo("principal-1");
    }

    @Test
    @DisplayName("an audit sink that fails withholds the answer")
    void auditFailureWithholdsTheAnswer() {
        Env env = new Env(LIST_AND_NAMES, 3);
        AuditRecorder failing = new AuditRecorder(event -> {
            throw new IllegalStateException("sink down at /var/secret/path");
        }, FIXED, "test-mcp");
        ListSourcesTool tool = new ListSourcesTool(env.queries, 1024,
                new AuthorizationService(new SecurityPolicy(Set.of("demonstration"), Map.of("investigator", LIST_AND_NAMES)),
                        "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted()),
                new ScopeResolver(PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                        Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted()),
                PrivacyMetrics.none(), failing, FIXED, null, ToolOptions.defaults().noAdmission().build());

        McpSchema.CallToolResult result = call(tool.specification(), args());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("refused: AUDIT_UNAVAILABLE");
        assertThat(result.structuredContent()).isNull();
    }

    // ---- the served schema ----

    @Test
    @DisplayName("the structured result validates against the served output schema, every page")
    void resultMatchesTheOutputSchema() {
        Env env = new Env(LIST_AND_NAMES, 5);
        McpServerFeatures.SyncToolSpecification spec = env.list.specification();
        DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
        Map<String, Object> outputSchema = spec.tool().outputSchema();

        McpSchema.CallToolResult result = call(spec, args());
        while (true) {
            assertThat(validator.validate(outputSchema, result.structuredContent()).valid()).isTrue();
            if (Boolean.TRUE.equals(page(result).get("last"))) {
                break;
            }
            result = call(spec, args("page", page(result).get("next")));
        }
    }

    @Test
    @DisplayName("the same two mappers: the tools built by the server wiring use the transport's mapper")
    void toolsShareTheServersMapper() throws Exception {
        Env env = new Env(LIST_AND_NAMES, 3);
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"), Map.of("investigator", LIST_AND_NAMES));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT",
                PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        DataPrismMcpServer.Wiring wiring = DataPrismMcpServer.Wiring.of(
                (request, context, investigation) -> {
                    throw new AssertionError("never called");
                }, authz, scopes, PrivacyMetrics.none(), env.audit, FIXED, null,
                ToolOptions.defaults().noAdmission().build(), env.queries, 1024);

        assertThat(wiring.list()).isNotNull();
        assertThat(wiring.describe()).isNotNull();
        assertThat(mapperOf(wiring.list())).isSameAs(wiring.mapper());
        assertThat(mapperOf(wiring.describe())).isSameAs(wiring.mapper());
        assertThat(wiring.specifications()).extracting(spec -> spec.tool().name())
                .containsExactly("get_entity_context", "compare_entity_sources", "list_sources", "describe_source");
    }

    @Test
    @DisplayName("without a catalog the server registers only the two entity tools")
    void noCatalogNoCatalogTools() {
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"), Map.of("investigator", LIST_AND_NAMES));
        DataPrismMcpServer.Wiring wiring = DataPrismMcpServer.Wiring.of(
                (request, context, investigation) -> {
                    throw new AssertionError("never called");
                }, new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION,
                        ClientAllowlist.unrestricted()),
                new ScopeResolver(PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                        Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted()),
                PrivacyMetrics.none(), new AuditRecorder(e -> { }, FIXED, "t"), FIXED, null,
                ToolOptions.defaults().noAdmission().build());

        assertThat(wiring.specifications()).extracting(spec -> spec.tool().name())
                .containsExactly("get_entity_context", "compare_entity_sources");
    }

    static Object mapperOf(Object tool) throws Exception {
        Class<?> type = tool.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField("mapper");
                field.setAccessible(true);
                return field.get(tool);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("mapper");
    }

    static List<String> names(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> String.format("src-%03d", i)).toList();
    }
}
