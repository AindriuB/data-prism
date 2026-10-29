package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonDataSourceAdapter;
import io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonScrubbingEngine;
import io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonSource;
import io.github.aindriub.dataprism.connectors.rest.RestSource;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.engine.SourceTree;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.mcp.CompareEntitySourcesTool;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A source whose payload carries a property the model does not declare, named
 * like personal data (task 118). The key is synthetic, at a reserved domain.
 *
 * <p>A record source can no longer carry undeclared keys (task 288, OD-288-1 (a)): the engine writes
 * exactly the declared components. So the carrier is a configured JSON source, whose body is a JSON tree
 * read from a loopback upstream and scrubbed by {@link ConfiguredJsonScrubbingEngine} against the
 * source's reviewed catalogue. Each fixture keeps its declared fields, keys, values and profile.
 */
public final class UndeclaredKeyFixture {

    static final String TOKEN = "zzUndeclaredKeyQx7";
    static final String KEY = TOKEN + "@example.com";
    static final String NAME_VALUE = "Unlisted Fixture Person";

    private UndeclaredKeyFixture() {
    }

    // ---- the carrier: a configured JSON source over a loopback upstream ---------------------------

    /** The reserved-domain key must not be the only thing that makes the shared upstream unique. */
    private static final String DEV_KEY = "development-only-key-not-for-any-real-data";

    /** The reviewed identifier field of a configured source: dropped from the response, never emitted. */
    public static FieldMetadata identifier(String name) {
        return new FieldMetadata(name, true, FieldMetadata.SELF, List.of(), PrivacyNamespace.NONE,
                null, "", null, String.class, null);
    }

    /** A reviewed PII name field, synthesised by the profile. */
    public static FieldMetadata personName(String name) {
        return new FieldMetadata(name, false, null, List.of(DataClassification.PII),
                PrivacyNamespace.PERSON_NAME, PrivacyAction.SYNTHESIZE, "", null, String.class, null);
    }

    /** A reviewed field declared non-sensitive. */
    public static FieldMetadata nonSensitive(String name, String reason) {
        return new FieldMetadata(name, false, null, List.of(), PrivacyNamespace.NONE, null, "", reason,
                String.class, null);
    }

    /** A field the catalogue declares but nobody classified. */
    public static FieldMetadata unreviewed(String name) {
        return new FieldMetadata(name, false, null, List.of(), PrivacyNamespace.NONE, null, "", null,
                String.class, null);
    }

    /**
     * A loopback HTTP upstream serving one fixed JSON body per configured source, and the sources' reviewed
     * catalogues. One instance lives for the JVM (daemon threads); registering a source again replaces its body.
     */
    public static final class Upstream {

        private final HttpServer server;
        private final Map<String, String> bodies = new ConcurrentHashMap<>();
        private final Map<String, ConfiguredJsonSource> sources = new ConcurrentHashMap<>();

        public Upstream() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "undeclared-key-upstream");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/", exchange -> {
                String[] segments = exchange.getRequestURI().getPath().split("/");
                String body = segments.length > 1 ? bodies.get(segments[1]) : null;
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        /**
         * A configured JSON source named {@code name} whose upstream answers {@code body} for any subject, with
         * {@code fields} as its reviewed catalogue (exactly one of them the {@code id} identifier).
         */
        public DataSourceAdapter<?> source(String name, ObjectNode body, FieldMetadata... fields) {
            bodies.put(name, body.toString());
            Map<String, FieldMetadata> catalogue = new LinkedHashMap<>();
            for (FieldMetadata field : fields) {
                catalogue.put(field.fieldName(), field);
            }
            RestSource transport = new RestSource(name,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "/" + name + "/{subject}", Duration.ofSeconds(5));
            ConfiguredJsonSource source = new ConfiguredJsonSource(transport, "fixture-v1", "id", catalogue);
            sources.put(name, source);
            // The plain HttpURLConnection factory: a JDK HttpClient would initialise the JVM-wide default SSLContext, which
            // McpHttpEndToEndTest shares a fork with and sets itself.
            RestClient client = RestClient.builder()
                    .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).build();
            return new ConfiguredJsonDataSourceAdapter(source, client);
        }

        /** Every source registered so far, by name. */
        public Map<String, ConfiguredJsonSource> sources() {
            return Map.copyOf(sources);
        }
    }

    /** The one upstream every fixture in this JVM shares. */
    public static final Upstream UPSTREAM = new Upstream();

    /**
     * The pipeline {@code DataPrismAssembly} builds, with {@link ConfiguredJsonScrubbingEngine} in place of the
     * plain engine, so a configured source is scrubbed against its own catalogue. Everything else is the same.
     */
    public static final class Assembly {

        private final ContextOrchestrator orchestrator;
        private final PseudonymisationVersion pseudonymisationVersion;

        public Assembly(List<DataSourceAdapter<?>> adapters, Map<String, ConfiguredJsonSource> sources,
                        Clock clock, AuditSink sink, Map<String, PrivacyProfile> profiles) {
            this(adapters, sources, new DefaultFieldMetadataResolver(), clock, sink, profiles);
        }

        /**
         * With {@code resolver} shared by the engine and the orchestrator, and no configured sources: a source that
         * returns an {@link ObjectNode} root is scrubbed against whatever fields {@code resolver} declares for it.
         */
        public Assembly(List<DataSourceAdapter<?>> adapters, FieldMetadataResolver resolver, Clock clock,
                        AuditSink sink, Map<String, PrivacyProfile> profiles) {
            this(adapters, Map.of(), resolver, clock, sink, profiles);
        }

        private Assembly(List<DataSourceAdapter<?>> adapters, Map<String, ConfiguredJsonSource> sources,
                         FieldMetadataResolver resolver, Clock clock, AuditSink sink,
                         Map<String, PrivacyProfile> profiles) {
            SecretKeyProvider keys = StaticSecretKeyProvider.of(DEV_KEY);
            Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("en");
            SyntheticValueSource synthetics = new HmacSyntheticGenerator(keys, vocabulary);
            PrivacyPolicyResolver policies = new ProfilePrivacyPolicyResolver(profiles);
            ValueTokenSource tokens = new HmacValueTokenSource(keys);
            ScrubbingEngine javaFirst = new JsonTreeScrubbingEngine(resolver, policies, synthetics, tokens);
            ScrubbingEngine scrubber = sources.isEmpty() ? javaFirst
                    : new ConfiguredJsonScrubbingEngine(javaFirst, sources, policies, synthetics, tokens);
            this.orchestrator = new DefaultContextOrchestrator(adapters, scrubber, resolver,
                    List.of(new RawValueLeakValidator(), new SensitivePatternValidator()), synthetics,
                    new ParameterFingerprinter(keys), new AuditRecorder(sink, clock, "example-1"),
                    new PassThroughIdentityResolver(),
                    new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(),
                            SourceFanOutOptions.defaults()),
                    new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                    new SourceAliasing(tokens), PrivacyMetrics.none(), OrchestrationOptions.defaults());
            this.pseudonymisationVersion = PseudonymisationVersion.HMAC_SHA256_V1.withVocabulary(vocabulary.id());
        }

        public ContextOrchestrator orchestrator() {
            return orchestrator;
        }

        public PseudonymisationVersion pseudonymisationVersion() {
            return pseudonymisationVersion;
        }
    }

    /** The keyed property repeats a classified value, so a pass-through profile trips the leak check. */
    static DataSourceAdapter<?> adapter() {
        ObjectNode body = SourceTree.newObject();
        body.put("id", "123");
        body.put("name", NAME_VALUE);
        body.put(KEY, NAME_VALUE);
        return UPSTREAM.source("open-api", body, identifier("id"), personName("name"));
    }

    /**
     * One undeclared property beside one field that is declared on the model but never annotated. The tree is
     * scrubbed through {@link #mixedResolver()}, which exposes an {@link ObjectNode} root by hand.
     */
    static ObjectNode mixedTree(String id, String unreviewedNote, String key, String value) {
        ObjectNode tree = SourceTree.newObject();
        tree.put("id", id);
        tree.put("unreviewedNote", unreviewedNote);
        tree.put(key, value);
        return tree;
    }

    static FieldMetadataResolver mixedResolver() {
        return new FieldMetadataResolver() {
            @Override
            public List<FieldMetadata> resolve(Class<?> type) {
                return List.of(identifier("id"), unreviewed("unreviewedNote"));
            }

            @Override
            public boolean exposed(Class<?> type) {
                return type == ObjectNode.class;
            }
        };
    }

    /** A different synthetic key for the successful-result scan (task 124). */
    static final String RESULT_TOKEN = "zzResultKeyWv4";
    /** Matches no scanner shape: an email-shaped key is refused by the key scan (task 194). */
    static final String RESULT_KEY = RESULT_TOKEN + "-note";

    /** The undeclared property's value is benign, so a pass-through run still succeeds. */
    static DataSourceAdapter<?> benignAdapter() {
        ObjectNode body = SourceTree.newObject();
        body.put("id", "123");
        body.put(RESULT_KEY, "benign-note");
        return UPSTREAM.source("benign-api", body, identifier("id"));
    }

    /** The shipped default profiles, as {@code DataPrismAssembly} loads them. */
    public static Map<String, PrivacyProfile> defaultProfiles() {
        try (var in = UndeclaredKeyFixture.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return io.github.aindriub.dataprism.core.policy.PrivacyProfiles.fromYaml(in);
        } catch (IOException e) {
            throw new IllegalStateException("default privacy profiles could not be loaded", e);
        }
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
        Assembly assembly = new Assembly(List.of(adapter()), UPSTREAM.sources(), clock, sink,
                profiles(behaviour));
        AuditRecorder toolAudit = new AuditRecorder(sink, clock, "undeclared-key-mcp");
        SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(purpose)), CaseAuthorizer.tokenTrusted());
        GetEntityContextTool get = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, PrivacyMetrics.none(), toolAudit, clock, null, ToolOptions.defaults().noAdmission().build());
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(assembly.orchestrator(),
                authorizationService, scopeResolver, PrivacyMetrics.none(),
                toolAudit, clock, null, ToolOptions.defaults().noAdmission().build());
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
        Assembly assembly = new Assembly(List.of(benignAdapter()), UPSTREAM.sources(), clock, sink,
                profiles(behaviour));
        AuditRecorder toolAudit = new AuditRecorder(sink, clock, "result-key-mcp");
        SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(purpose)), CaseAuthorizer.tokenTrusted());
        GetEntityContextTool get = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, PrivacyMetrics.none(), toolAudit, clock, null, ToolOptions.defaults().noAdmission().build());
        CompareEntitySourcesTool compare = new CompareEntitySourcesTool(assembly.orchestrator(),
                authorizationService, scopeResolver, PrivacyMetrics.none(),
                toolAudit, clock, null, ToolOptions.defaults().noAdmission().build());
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
