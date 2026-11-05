package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.annotations.Chainable;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.link.FollowPayload;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkOpenResult;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.orchestration.catalog.SourceVisibility;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** What links are built from, toward whom, and in what order. */
public class LinkBuilderTest {

    /** A record carrying chainable keys, as a source model would. */
    public record Parcel(
            @InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String parcel,
            @InternalIdentifier @Chainable(keyNamespace = "depot-ref") List<String> depots,
            List<Route> routes,
            String note) {
    }

    @SensitiveObject
    public record Route(@InternalIdentifier @Chainable(keyNamespace = "depot-ref") String depot) {
    }

    /** A record with nothing chainable. */
    public record Plain(String note) {
    }

    public static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    public static final Instant SCOPE_END = Instant.parse("2030-01-01T00:00:00Z");
    public static final String PRINCIPAL = "investigator-1";
    public static final String SCOPE = "SCOPE-1";

    /** A configurable orchestrator with links on. */
    public static final class Rig {
        public final List<DataSourceAdapter<?>> adapters = new ArrayList<>();
        public final List<String> resolverCalls = new CopyOnWriteArrayList<>();
        public final AtomicInteger fetches = new AtomicInteger();
        public final Map<Metric, AtomicInteger> counters = new java.util.concurrent.ConcurrentHashMap<>();
        public final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        private final List<KeyResolver> resolvers = new ArrayList<>();
        private final List<LlmResponseValidator> validators = new ArrayList<>(List.of(new SensitivePatternValidator()));
        private SourceVisibility visibility = SourceVisibility.allowAll();
        private int cap = 50;
        private boolean enabled = true;
        private int maxResponseBytes = RequestLimits.DEFAULT.maxResponseBytes();
        private Duration lifetime = Duration.ofMinutes(30);
        public final StaticSecretKeyProvider keys = StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
        public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        public Rig source(String name, Object record, String... inputKeys) {
            List<KeyNamespace> inputs = new ArrayList<>();
            for (String key : inputKeys) {
                inputs.add(new KeyNamespace(key));
            }
            SourceDescription description = new SourceDescription(Set.of(), null, null, inputs, null, null);
            adapters.add(new DataSourceAdapter<Object>() {
                @Override
                public String sourceName() {
                    return name;
                }

                @Override
                @SuppressWarnings("unchecked")
                public Class<Object> responseType() {
                    return record == null ? (Class<Object>) (Class<?>) Plain.class : (Class<Object>) record.getClass();
                }

                @Override
                public Object fetch(DataRequest request) {
                    fetches.incrementAndGet();
                    return record;
                }

                @Override
                public SourceDescription description() {
                    return description;
                }
            });
            return this;
        }

        public Rig resolver(String name, String from, String to) {
            resolvers.add(new KeyResolver() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public KeyNamespace from() {
                    return new KeyNamespace(from);
                }

                @Override
                public KeyNamespace to() {
                    return new KeyNamespace(to);
                }

                @Override
                public Resolution resolve(String key, ResolutionContext context, int maxResults) {
                    resolverCalls.add(name);
                    return new Resolution(List.of(), false);
                }
            });
            return this;
        }

        public Rig visibility(SourceVisibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public Rig cap(int cap) {
            this.cap = cap;
            return this;
        }

        public Rig disabled() {
            this.enabled = false;
            return this;
        }

        public Rig maxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        public Rig lifetime(Duration lifetime) {
            this.lifetime = lifetime;
            return this;
        }

        public Rig validator(LlmResponseValidator validator) {
            validators.add(validator);
            return this;
        }

        public LinkSettings settings() {
            return new LinkSettings("link-key", Optional.of(lifetime), 1024, true, Duration.ofSeconds(5));
        }

        public LinkCodec codec() {
            LinkKeys linkKeys = new LinkKeys(id -> id.equals("link-key")
                    ? "link-key-material-0123456789abcdef0123456789".getBytes() : keys.secret(id),
                    "link-key", "pseudo-key");
            return new LinkCodec(settings(), linkKeys, clock);
        }

        public static LinkBinding binding() {
            return new LinkBinding(SCOPE, PRINCIPAL, SCOPE_END, OrchestrationOptions.LinkIssuing.NO_EXPIRY);
        }

        public DefaultContextOrchestrator orchestrator() {
            ScrubbingEngine scrubber = (record, ctx) -> {
                String note = record instanceof Parcel p ? p.note() : ((Plain) record).note();
                return new ScrubResult(MAPPER.createObjectNode().put("note", note == null ? "n" : note),
                        Set.of(), Map.of("/note", PrivacyAction.PASS_THROUGH));
            };
            PrivacyMetrics metrics = new PrivacyMetrics() {
                @Override
                public void increment(Metric metric) {
                    counters.computeIfAbsent(metric, m -> new AtomicInteger()).incrementAndGet();
                }

                @Override
                public void increment(Metric metric, String sourceName) {
                    increment(metric);
                }

                @Override
                public void record(Metric metric, String sourceName, Duration duration) {
                }
            };
            OrchestrationOptions options = OrchestrationOptions.defaults().withVisibility(visibility);
            if (enabled) {
                options = options.withLinks(new OrchestrationOptions.LinkIssuing(codec(), cap,
                        new KeyResolvers(resolvers), Map.of(), clock, caller -> OrchestrationOptions.LinkIssuing.NO_EXPIRY));
            }
            var resolver = new DefaultFieldMetadataResolver();
            return new DefaultContextOrchestrator(adapters, scrubber, resolver, validators,
                    (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(keys),
                    new AuditRecorder(audit::add, clock, "test-1"), new PassThroughIdentityResolver(),
                    new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                    new InMemoryScopeBudget(), limits(), new NamespaceCorrelationService(resolver),
                    new SourceAliasing(new HmacValueTokenSource(keys)), metrics, options);
        }

        private RequestLimits limits() {
            RequestLimits d = RequestLimits.DEFAULT;
            return new RequestLimits(d.maxSources(), d.maxRecords(), maxResponseBytes, d.perSourceTimeout(),
                    d.maxConcurrency(), d.scopeReadBudget(), d.requestTimeout(), d.scopeTotalReadBudget());
        }

        public ContextResponse run() {
            return orchestrator().buildContext(ContextRequest.of("THING", "1"), context(), caller());
        }

        public int count(Metric metric) {
            AtomicInteger value = counters.get(metric);
            return value == null ? 0 : value.get();
        }
    }

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static PrivacyContext context() {
        return new PrivacyContext(SCOPE, PrivacyScopeType.CASE, "DEFAULT", "test", SCOPE_END,
                PseudonymisationVersion.HMAC_SHA256_V1);
    }

    public static InvestigationContext caller() {
        return new InvestigationContext(PRINCIPAL, "client-1", "CASE-1", Set.of());
    }

    private static Rig rig() {
        return new Rig();
    }

    private static SourceResult originOf(ContextResponse response) {
        return response.sources().values().stream().filter(s -> !s.links().isEmpty() || s.linksOmitted() != null)
                .findFirst().orElseThrow();
    }

    private static Parcel parcel() {
        return new Parcel("P-1", List.of("D-1", "D-2"), List.of(new Route("D-9")), "ok");
    }

    @Test
    void directLinkOnAnExactNamespaceMatch() {
        ContextResponse response = rig().source("origin-api", parcel())
                .source("parcel-api", new Plain("a"), "parcel-ref").run();

        List<IssuedLink> links = originOf(response).links();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).rel()).isEqualTo("direct");
        assertThat(links.get(0).field()).isEqualTo("parcel");
        assertThat(links.get(0).namespace()).isEqualTo("parcel-ref");
        assertThat(links.get(0).toNamespace()).isNull();
    }

    @Test
    void translatedLinkThroughExactlyOneDeclaredResolver() {
        Rig rig = rig().source("origin-api", parcel()).source("route-api", new Plain("a"), "route-code")
                .resolver("parcel-to-route", "parcel-ref", "route-code");

        List<IssuedLink> links = originOf(rig.run()).links();

        assertThat(links).hasSize(1);
        assertThat(links.get(0).rel()).isEqualTo("translated");
        assertThat(links.get(0).namespace()).isEqualTo("parcel-ref");
        assertThat(links.get(0).toNamespace()).isEqualTo("route-code");
    }

    @Test
    void aTargetThatIsReachableBothWaysGetsTheDirectLinkOnly() {
        Rig rig = rig().source("origin-api", parcel())
                .source("both-api", new Plain("a"), "route-code", "parcel-ref")
                .resolver("parcel-to-route", "parcel-ref", "route-code");

        List<IssuedLink> links = originOf(rig.run()).links();

        assertThat(links).extracting(IssuedLink::rel).containsExactly("direct");
    }

    @Test
    void neverThroughTwoResolvers() {
        Rig rig = rig().source("origin-api", parcel()).source("far-api", new Plain("a"), "end-ref")
                .resolver("first", "parcel-ref", "middle-ref").resolver("second", "middle-ref", "end-ref");

        assertThat(rig.run().sources().values()).allMatch(s -> s.links().isEmpty());
    }

    @Test
    void aNearMissNamespaceGivesNoLink() {
        // The flag matters: case-insensitive or prefix matching would link these. Namespaces are lower case
        // by grammar, so the near misses are a prefix, an extension and a different separator.
        Rig rig = rig().source("origin-api", parcel())
                .source("prefix-api", new Plain("a"), "parcel-re")
                .source("longer-api", new Plain("a"), "parcel-ref2")
                .source("other-api", new Plain("a"), "parcel-reference");

        assertThat(rig.run().sources().values()).allMatch(s -> s.links().isEmpty());
    }

    @Test
    void sameKeyAtSeveralIndicesCollapsesToTheFirst() {
        Parcel repeated = new Parcel("P-1", List.of("D-1", "D-2", "D-1"), List.of(new Route("D-2")), "ok");
        Rig rig = rig().source("origin-api", repeated).source("depot-api", new Plain("a"), "depot-ref");

        List<String> fields = originOf(rig.run()).links().stream().map(IssuedLink::field).toList();

        assertThat(fields).containsExactly("depots[0]", "depots[1]");
    }

    @Test
    void aRecordFieldInsideACollectionGetsAnIndexedPath() {
        Parcel nested = new Parcel("P-1", List.of(), List.of(new Route("D-4"), new Route("D-5")), "ok");
        Rig rig = rig().source("origin-api", nested).source("depot-api", new Plain("a"), "depot-ref");

        List<String> fields = originOf(rig.run()).links().stream().map(IssuedLink::field).toList();

        assertThat(fields).containsExactly("routes[0].depot", "routes[1].depot");
    }

    @Test
    void noLinkTowardASourceTheCallerMayNotSee() {
        Rig rig = rig().source("origin-api", parcel()).source("hidden-api", new Plain("a"), "parcel-ref")
                .visibility((caller, name) -> !name.equals("hidden-api"));

        assertThat(rig.run().sources().values()).allMatch(s -> s.links().isEmpty());
    }

    @Test
    void noLinkOnASourceThatDidNotAnswer() {
        Rig rig = rig().source("origin-api", null).source("parcel-api", new Plain("a"), "parcel-ref");

        ContextResponse response = rig.run();

        assertThat(response.sources().values()).allMatch(s -> s.links().isEmpty() && s.linksOmitted() == null);
    }

    @Test
    void aSourceIsNeverLinkedToItself() {
        Rig rig = rig().source("origin-api", parcel(), "parcel-ref");

        assertThat(rig.run().sources().values()).allMatch(s -> s.links().isEmpty());
    }

    @Test
    void aFieldWhoseDeclaredNameIsSensitiveGetsNoLink() {
        // A validator that refuses the declared name "parcel" as a lone value stands for a name that is sensitive.
        LlmResponseValidator refusesTheName = (tree, prohibited, emitted, ctx) ->
                tree.toString().contains("\"field\":\"parcel\"")
                        ? io.github.aindriub.dataprism.validation.ValidationResult.failed(List.of(
                                new io.github.aindriub.dataprism.validation.Violation("$", "NAME", "TEST")))
                        : io.github.aindriub.dataprism.validation.ValidationResult.ok();
        Rig rig = rig().source("origin-api", parcel()).source("parcel-api", new Plain("a"), "parcel-ref")
                .validator(refusesTheName);

        assertThat(rig.run().sources().values()).allMatch(s -> s.links().isEmpty());
    }

    @Test
    void linksAreOrderedByRelThenTargetThenField() {
        Rig rig = rig().source("origin-api", parcel())
                .source("b-depot-api", new Plain("a"), "depot-ref")
                .source("a-depot-api", new Plain("a"), "depot-ref")
                .source("route-api", new Plain("a"), "route-code")
                .resolver("depot-to-route", "depot-ref", "route-code");
        ContextResponse response = rig.run();

        List<IssuedLink> links = originOf(response).links();

        List<String> keys = links.stream().map(l -> l.rel() + "/" + l.target() + "/" + l.field()).toList();
        assertThat(keys).isSorted();
        assertThat(links).extracting(IssuedLink::rel).contains("direct", "translated");
    }

    @Test
    void expiryIsTheLinkLifetimeAndTheBindingIsTheSessions() {
        Rig rig = rig().source("origin-api", parcel()).source("parcel-api", new Plain("a"), "parcel-ref");
        IssuedLink link = originOf(rig.run()).links().get(0);

        assertThat(link.expiresAt()).isEqualTo("2026-10-09T12:30:00Z");
        LinkOpenResult opened = rig.codec().open(link.link(), Rig.binding(), Set.of(LinkRelation.DIRECT));
        assertThat(opened).isInstanceOf(LinkOpenResult.Opened.class);
        FollowPayload payload = (FollowPayload) ((LinkOpenResult.Opened) opened).payload();
        assertThat(payload.target()).isEqualTo(link.target());
        // Another caller's binding fails: the link is sealed to this session, not to anything in the request.
        LinkBinding other = new LinkBinding(SCOPE, "someone-else", SCOPE_END, OrchestrationOptions.LinkIssuing.NO_EXPIRY);
        assertThat(rig.codec().open(link.link(), other, Set.of(LinkRelation.DIRECT)))
                .isInstanceOf(LinkOpenResult.Refused.class);
    }

    @Test
    void issuingCallsNoResolverAndNoExtraAdapterRead() {
        Rig rig = rig().source("origin-api", parcel()).source("route-api", new Plain("a"), "route-code")
                .source("depot-api", new Plain("a"), "depot-ref")
                .resolver("parcel-to-route", "parcel-ref", "route-code");

        rig.run();

        assertThat(rig.resolverCalls).isEmpty();
        assertThat(rig.fetches.get()).isEqualTo(3);
    }

    @Test
    void aComparisonCarriesNoLinks() {
        Rig rig = rig().source("origin-api", parcel()).source("parcel-api", new Plain("a"), "parcel-ref");

        ContextResponse response = rig.orchestrator().buildContext(
                ContextRequest.comparison("THING", "1", Set.of(), "compare_entity_sources"), context(), caller());

        assertThat(response.sources().values()).allMatch(s -> s.links().isEmpty() && s.linksOmitted() == null);
        assertThat(rig.count(Metric.LINKS_ISSUED)).isZero();
    }

    /** Stands for a scan that refuses any token-shaped string the engine did not emit. */
    private static final LlmResponseValidator REFUSES_UNEMITTED_TOKEN_SHAPES = (tree, prohibited, emitted, ctx) -> {
        List<io.github.aindriub.dataprism.validation.Violation> found = new ArrayList<>();
        scan(tree, emitted, found);
        return found.isEmpty() ? io.github.aindriub.dataprism.validation.ValidationResult.ok()
                : io.github.aindriub.dataprism.validation.ValidationResult.failed(found);
    };

    private static void scan(tools.jackson.databind.JsonNode node, Set<String> emitted,
                             List<io.github.aindriub.dataprism.validation.Violation> found) {
        if (node.isString() && node.asString().matches("[A-Za-z0-9_-]{60,}") && !emitted.contains(node.asString())) {
            found.add(new io.github.aindriub.dataprism.validation.Violation("$", "TOKEN_SHAPE", "TEST"));
        }
        node.forEach(child -> scan(child, emitted, found));
    }

    @Test
    void anIssuedTokenPassesTheScanButATokenShapedStringFromARecordStillFails() {
        Rig issued = rig().source("origin-api", parcel()).source("parcel-api", new Plain("a"), "parcel-ref")
                .validator(REFUSES_UNEMITTED_TOKEN_SHAPES);
        assertThat(originOf(issued.run()).links()).hasSize(1);

        String shaped = "AQ" + "x".repeat(80);
        Rig forged = rig().source("origin-api", new Parcel("P-1", List.of(), List.of(), shaped))
                .source("parcel-api", new Plain("a"), "parcel-ref").validator(REFUSES_UNEMITTED_TOKEN_SHAPES);
        ContextResponse response = forged.run();
        // The record's own token-shaped string was not issued, so its source is withheld whole, links included.
        assertThat(response.sources().values()).extracting(SourceResult::status).contains("REFUSED");
        assertThat(response.sources().values()).allMatch(s -> s.links().isEmpty());
    }

    @Test
    void aTargetNameOver64CharactersGetsNoLinkAndCountsAsOmitted() {
        String longName = "x".repeat(65);
        Rig rig = rig().source("origin-api", parcel()).source(longName, new Plain("a"), "parcel-ref");

        ContextResponse response = rig.run();

        SourceResult origin = response.sources().values().stream().filter(s -> s.linksOmitted() != null)
                .findFirst().orElseThrow();
        assertThat(origin.links()).isEmpty();
        assertThat(origin.linksOmitted()).isEqualTo(1);
    }
}
