package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.link.AttemptSubject;
import io.github.aindriub.dataprism.core.link.FollowPayload;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.link.SealResult;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.AuditedRefusalException;
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
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.NOW;
import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.PRINCIPAL;
import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.SCOPE;
import static io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.SCOPE_END;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** {@code follow}: one sealed link read through the same engine as any other read (task 252). */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowTest {

    static final String PSEUDO_KEY = "pseudo-key";
    static final String LINK_KEY = "link-key";

    /** A record whose only field is declared non-sensitive, so only the engine's own checks can stop an echo. */
    public record Echo(@NonSensitive(reason = "test fixture") String note) {
    }

    /** A configurable orchestrator with follow on; the target sources hold records by key. */
    public static final class FollowRig {
        public final List<DataSourceAdapter<?>> adapters = new ArrayList<>();
        public final List<DataRequest> requests = new CopyOnWriteArrayList<>();
        public final List<String> events = new CopyOnWriteArrayList<>();
        public final AtomicInteger fetches = new AtomicInteger();
        public final Map<String, Object> records = new ConcurrentHashMap<>();
        public final Map<Metric, AtomicInteger> counters = new ConcurrentHashMap<>();
        public final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        public final List<String> resolverCalls = new CopyOnWriteArrayList<>();
        public final List<Integer> resolverMax = new CopyOnWriteArrayList<>();
        public final List<KeyResolver> resolvers = new ArrayList<>();
        public final StaticSecretKeyProvider secrets =
                StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
        public final List<LlmResponseValidator> validators = new ArrayList<>(List.of(new SensitivePatternValidator()));
        public volatile boolean clockFails;
        public volatile Instant now = NOW;
        public volatile Function<String, RuntimeException> fetchFailure = key -> null;
        public volatile Function<Object, PrivacyRefusedException> scrubFailure = record -> null;
        public volatile Function<Object, Set<String>> emittedBy = record -> Set.of();
        /** How long one read takes on the rig's clock; the clock moves by this at the start of each fetch. */
        public volatile Duration fetchTakes = Duration.ZERO;
        public final List<Instant> fetchStarts = new CopyOnWriteArrayList<>();
        public ScopeBudget budget = new InMemoryScopeBudget();
        public LinkAttemptLimiter limiter = new InMemoryLinkAttemptLimiter(3, Duration.ofMinutes(10),
                Clock.fixed(NOW, ZoneId.of("UTC")));
        public SourceVisibility visibility = SourceVisibility.allowAll();
        public int maxTargets = 5;
        public int budgetCost = 1;
        public boolean followEnabled = true;
        public boolean scopeEndKnown = true;
        public RequestLimits limits = RequestLimits.DEFAULT;
        public final Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                if (clockFails) {
                    throw new IllegalStateException("clock down");
                }
                return now;
            }
        };
        private final LinkKeys keys = new LinkKeys(id -> id.equals(LINK_KEY)
                ? "link-key-material-0123456789abcdef0123456789".getBytes() : secrets.secret(id),
                LINK_KEY, PSEUDO_KEY);

        /**
         * A target source. Its record for a key is {@code records.get(key)}; with no entry for any key it answers
         * {@code defaultRecord} for every key, and with entries it answers null (no data) for a key not in them.
         */
        public FollowRig source(String name, Object defaultRecord, String... inputKeys) {
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
                    return (Class<Object>) defaultRecord.getClass();
                }

                @Override
                public Object fetch(DataRequest request) {
                    if (name.equals("origin-api")) {
                        return defaultRecord;
                    }
                    requests.add(request);
                    fetchStarts.add(now);
                    now = now.plus(fetchTakes);
                    events.add("fetch");
                    fetches.incrementAndGet();
                    RuntimeException failure = fetchFailure.apply(request.subjectId());
                    if (failure != null) {
                        throw failure;
                    }
                    return records.containsKey(request.subjectId()) ? records.get(request.subjectId())
                            : records.isEmpty() ? defaultRecord : null;
                }

                @Override
                public SourceDescription description() {
                    return description;
                }
            });
            return this;
        }

        /** A resolver that answers with {@code behaviour}; it records its calls. */
        public FollowRig resolver(String name, String from, String to,
                                  Function<String, KeyResolver.Resolution> behaviour) {
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
                    resolverMax.add(maxResults);
                    events.add("resolve");
                    return behaviour.apply(key);
                }
            });
            return this;
        }

        /** The real raw-value check, as a deployment wires it, beside the pattern scan. */
        public FollowRig realValidators() {
            validators.add(new RawValueLeakValidator());
            return this;
        }

        public LinkSettings settings() {
            return new LinkSettings(LINK_KEY, Optional.of(Duration.ofMinutes(30)), 1024, true, Duration.ofSeconds(5));
        }

        public LinkCodec codec() {
            return new LinkCodec(settings(), keys, clock);
        }

        public LinkKeys keys() {
            return keys;
        }

        public String label(String name) {
            return new SourceAliasing(new HmacValueTokenSource(secrets)).aliasFor(name, context());
        }

        public static LinkBinding binding() {
            return new LinkBinding(SCOPE, PRINCIPAL, SCOPE_END, SCOPE_END);
        }

        /** A genuine link toward {@code target}, sealed with this rig's codec. */
        public String token(String target, String namespace, LinkRelation relation, String resolver, String key) {
            SealResult sealed = codec().sealDetailed(new FollowPayload(new byte[8], now, namespace,
                    Optional.ofNullable(resolver), key.getBytes(StandardCharsets.UTF_8), label(target), relation),
                    binding());
            return ((SealResult.Issued) sealed).link().token();
        }

        /** A token of the right shape whose tag no longer verifies. */
        public String forged() {
            char[] chars = direct("parcel-api", "parcel-ref", "P-1").toCharArray();
            int at = chars.length - 3;
            chars[at] = chars[at] == 'A' ? 'B' : 'A';
            return new String(chars);
        }

        public String direct(String target, String namespace, String key) {
            return token(target, namespace, LinkRelation.DIRECT, null, key);
        }

        public String translated(String target, String fromNamespace, String resolver, String key) {
            return token(target, fromNamespace, LinkRelation.TRANSLATED, resolver, key);
        }

        public DefaultContextOrchestrator orchestrator() {
            ScrubbingEngine scrubber = (record, ctx) -> {
                PrivacyRefusedException refusal = scrubFailure.apply(record);
                if (refusal != null) {
                    throw refusal;
                }
                String note = record instanceof LinkBuilderTest.Parcel p ? p.note()
                        : record instanceof LinkBuilderTest.Plain n ? n.note()
                        : record instanceof Echo e ? e.note() : "n";
                return new ScrubResult(LinkBuilderTest.MAPPER.createObjectNode().put("note", note),
                        emittedBy.apply(record), Map.of("/note", PrivacyAction.PASS_THROUGH));
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
            OrchestrationOptions options = OrchestrationOptions.defaults().withVisibility(visibility)
                    .withLinks(new OrchestrationOptions.LinkIssuing(codec(), 50, new KeyResolvers(resolvers),
                            Map.of(), clock));
            if (followEnabled) {
                options = options.withFollow(new OrchestrationOptions.FollowSettings(
                        new LinkAttemptGate(codec(), limiter), keys, new KeyResolvers(resolvers),
                        new ResolverInvoker(metrics, ResolverInvoker.DEFAULT_MAX_CONCURRENT_CALLS, clock),
                        maxTargets, Duration.ofSeconds(2), budgetCost));
            }
            var resolver = new DefaultFieldMetadataResolver();
            return new DefaultContextOrchestrator(adapters, scrubber, resolver, validators,
                    (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(secrets),
                    new AuditRecorder(audit::add, Clock.fixed(NOW, ZoneId.of("UTC")), "test-1"), new PassThroughIdentityResolver(),
                    new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                    budget, limits, new NamespaceCorrelationService(resolver),
                    new SourceAliasing(new HmacValueTokenSource(secrets)), metrics, options);
        }

        public PrivacyContext context() {
            return LinkBuilderTest.context(scopeEndKnown ? SCOPE_END : null);
        }

        public InvestigationContext caller() {
            return LinkBuilderTest.caller();
        }

        public ContextResponse follow(String link) {
            return orchestrator().follow(FollowRequest.of(link), context(), caller());
        }

        public AuditEvent lastAudit() {
            return audit.get(audit.size() - 1);
        }

        public int count(Metric metric) {
            AtomicInteger value = counters.get(metric);
            return value == null ? 0 : value.get();
        }
    }

    static PrivacyRefusedException refusal(FollowRig rig, String link) {
        Throwable thrown = catchThrowable(() -> rig.follow(link));
        assertThat(thrown).isInstanceOf(AuditedRefusalException.class);
        return (PrivacyRefusedException) thrown;
    }

    private static FollowRig rig() {
        return new FollowRig().source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref");
    }

    @Test
    void aDirectLinkReadsTheTargetOnceWithTheSealedKeyAndItsNamespace() {
        FollowRig rig = rig();

        ContextResponse response = rig.follow(rig.direct("parcel-api", "parcel-ref", "P-1"));

        assertThat(response.sources()).containsOnlyKeys(rig.label("parcel-api"));
        SourceResult result = response.sources().get(rig.label("parcel-api"));
        assertThat(result.status()).isEqualTo("ANSWERED");
        assertThat(result.entity().get("note").asString()).isEqualTo("alpha");
        assertThat(result.items()).isEmpty();
        assertThat(rig.fetches.get()).isEqualTo(1);
        assertThat(rig.requests.get(0).subjectId()).isEqualTo("P-1");
        assertThat(rig.requests.get(0).keyNamespace()).contains(new KeyNamespace("parcel-ref"));
        assertThat(rig.lastAudit().policyDecision()).isEqualTo("ALLOW");
        assertThat(rig.lastAudit().tool()).isEqualTo("follow");
    }

    @Test
    void aLinkIssuedByGetEntityContextIsFollowedEndToEnd() {
        FollowRig rig = new FollowRig()
                .source("origin-api", new LinkBuilderTest.Parcel("P-1", List.of(), List.of(), "ok"))
                .source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        DefaultContextOrchestrator orchestrator = rig.orchestrator();
        ContextResponse origin = orchestrator.buildContext(ContextRequest.of("THING", "1"), rig.context(), rig.caller());
        String link = origin.sources().values().stream().flatMap(s -> s.links().stream()).findFirst().orElseThrow().link();

        ContextResponse followed = orchestrator.follow(FollowRequest.of(link), rig.context(), rig.caller());

        assertThat(followed.sources().values().iterator().next().status()).isEqualTo("ANSWERED");
        assertThat(rig.requests).extracting(DataRequest::subjectId).contains("P-1");
    }

    @Test
    void theFollowedResultCarriesItsOwnOneStepLinks() {
        FollowRig rig = new FollowRig()
                .source("parcel-api", new LinkBuilderTest.Parcel("P-1", List.of("D-1"), List.of(), "alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref");

        ContextResponse response = rig.follow(rig.direct("parcel-api", "parcel-ref", "P-1"));

        SourceResult result = response.sources().get(rig.label("parcel-api"));
        assertThat(result.links()).extracting(IssuedLink::target).containsExactly(rig.label("depot-api"));
        assertThat(result.links().get(0).field()).isEqualTo("depots[0]");
    }

    @Test
    void aValidatorThatRefusesTheTargetsAnswerRefusesTheCall() {
        FollowRig rig = rig();
        rig.validators.add((tree, prohibited, emitted, ctx) -> tree.toString().contains("alpha")
                ? ValidationResult.failed(List.of(new Violation("$.note", "STUB", "TEST"))) : ValidationResult.ok());

        PrivacyRefusedException refused = refusal(rig, rig.direct("parcel-api", "parcel-ref", "P-1"));

        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(rig.lastAudit().policyDecision()).isEqualTo("DENY:VALIDATION_FAILED");
    }

    @Test
    void aScrubRefusalRefusesTheCall() {
        FollowRig rig = rig();
        rig.scrubFailure = record -> new PrivacyRefusedException("UNCLASSIFIED_FIELD", "$.x", "no");

        assertThat(refusal(rig, rig.direct("parcel-api", "parcel-ref", "P-1")).code()).isEqualTo("UNCLASSIFIED_FIELD");
    }

    @Test
    void aTargetThatHasNoRecordIsNoData() {
        FollowRig rig = rig();
        rig.records.put("other", new LinkBuilderTest.Plain("x"));

        SourceResult result = rig.follow(rig.direct("parcel-api", "parcel-ref", "P-1"))
                .sources().get(rig.label("parcel-api"));

        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.entity()).isNull();
    }

    @Test
    void aTargetNoLongerPermittedIsRefusedAndNothingIsRead() {
        FollowRig rig = rig();
        String link = rig.direct("parcel-api", "parcel-ref", "P-1");
        rig.visibility = (caller, name) -> !name.equals("parcel-api");

        PrivacyRefusedException refused = refusal(rig, link);

        assertThat(refused.code()).isEqualTo("LINK_INVALID");
        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_TARGET_NOT_PERMITTED");
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void aTargetThatNoLongerAcceptsTheNamespaceIsRelationUndeclared() {
        FollowRig rig = rig();

        refusal(rig, rig.direct("parcel-api", "depot-ref", "P-1"));

        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_RELATION_UNDECLARED");
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void aTranslatedLinkWhoseResolverBeanIsGoneIsRelationUndeclared() {
        FollowRig rig = rig();
        String link = rig.translated("depot-api", "parcel-ref", "gone-resolver", "P-1");

        refusal(rig, link);

        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_RELATION_UNDECLARED");
        assertThat(rig.resolverCalls).isEmpty();
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void aPayloadThatDoesNotParseAsANamespaceIsPayloadMismatch() {
        FollowRig rig = rig();

        refusal(rig, rig.direct("parcel-api", "Not A Namespace", "P-1"));

        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_PAYLOAD_MISMATCH");
    }

    @Test
    void anUnknownTargetLabelIsTargetNotPermitted() {
        FollowRig rig = rig();

        refusal(rig, rig.direct("no-such-api", "parcel-ref", "P-1"));

        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_TARGET_NOT_PERMITTED");
    }

    @Test
    void anExpiredLinkIsRefusedAsExpiredAndReadsNothing() {
        FollowRig rig = rig();
        String link = rig.direct("parcel-api", "parcel-ref", "P-1");
        rig.now = NOW.plus(Duration.ofHours(2));

        refusal(rig, link);

        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_EXPIRED");
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void postAuthenticationRefusalsNeverTouchTheLimiter() {
        FollowRig rig = rig();
        List<String> recorded = new CopyOnWriteArrayList<>();
        rig.limiter = new LinkAttemptLimiter() {
            @Override
            public LockState lockState(AttemptSubject subject) {
                return LockState.UNLOCKED;
            }

            @Override
            public Admission recordFailure(AttemptSubject subject) {
                recorded.add("recorded");
                return Admission.WITHIN_LIMIT;
            }
        };
        String expired = rig.direct("parcel-api", "parcel-ref", "P-1");

        refusal(rig, rig.direct("no-such-api", "parcel-ref", "P-1"));
        refusal(rig, rig.direct("parcel-api", "depot-ref", "P-1"));
        refusal(rig, rig.direct("parcel-api", "Not A Namespace", "P-1"));
        rig.now = NOW.plus(Duration.ofHours(2));
        refusal(rig, expired);

        assertThat(recorded).isEmpty();
    }

    @Test
    void followIsRefusedWhenItIsNotConfigured() {
        FollowRig rig = rig();
        String link = rig.direct("parcel-api", "parcel-ref", "P-1");
        rig.followEnabled = false;

        PrivacyRefusedException refused = refusal(rig, link);

        assertThat(refused.code()).isEqualTo("LINK_INVALID");
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void aScopeWithNoKnownEndRefusesBeforeAnythingIsRead() {
        FollowRig rig = rig();
        String link = rig.direct("parcel-api", "parcel-ref", "P-1");
        rig.scopeEndKnown = false;

        PrivacyRefusedException refused = refusal(rig, link);

        assertThat(refused.code()).isEqualTo("LINK_INVALID");
        assertThat(rig.fetches.get()).isZero();
    }

    // ---- the final validation pass (task 252, review round 1) ----

    private static final String SECRET_SHAPED = "sk-a1B2c3D4e5F6g7H8i9J0";

    private static FollowRig twoParcels() {
        FollowRig rig = new FollowRig().realValidators()
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref")
                .resolver("parcel-to-depot", "parcel-ref", "depot-ref",
                        key -> new KeyResolver.Resolution(List.of("K-1", "K-2"), false));
        rig.source("parcel-api", new LinkBuilderTest.Plain("alpha"), "parcel-ref");
        return rig;
    }

    @Test
    void aValueOneItemHoldsThatAnEarlierItemEchoesIsCaughtByTheFinalPassAndRefusesTheCall() {
        FollowRig rig = twoParcels();
        rig.records.put("K-1", new LinkBuilderTest.Parcel("ID-1", List.of(), List.of(), "ID-2"));
        rig.records.put("K-2", new LinkBuilderTest.Parcel("ID-2", List.of(), List.of(), "ID-1"));

        PrivacyRefusedException refused = refusal(rig,
                rig.translated("depot-api", "parcel-ref", "parcel-to-depot", "P-1"));

        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(rig.fetches.get()).isEqualTo(2);
    }

    @Test
    void anEmittedValueTheScrubberDeclaredPassesTheFinalPassOnlyBecauseItsEmittedSetIsKept() {
        FollowRig allowed = rig();
        allowed.emittedBy = record -> Set.of(SECRET_SHAPED);
        allowed.records.put("P-1", new LinkBuilderTest.Plain(SECRET_SHAPED));
        FollowRig refused = rig();
        refused.records.put("P-1", new LinkBuilderTest.Plain(SECRET_SHAPED));

        assertThat(allowed.follow(allowed.direct("parcel-api", "parcel-ref", "P-1"))
                .sources().get(allowed.label("parcel-api")).status()).isEqualTo("ANSWERED");
        assertThat(refusal(refused, refused.direct("parcel-api", "parcel-ref", "P-1")).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void theFinalPassSeesTheLinkNodesAndEveryIssuedTokenIsInItsEmittedSet() {
        FollowRig rig = new FollowRig()
                .source("parcel-api", new LinkBuilderTest.Parcel("P-1", List.of("D-1"), List.of(), "alpha"), "parcel-ref")
                .source("depot-api", new LinkBuilderTest.Plain("beta"), "depot-ref");
        List<JsonNode> seen = new CopyOnWriteArrayList<>();
        List<Set<String>> emittedSeen = new CopyOnWriteArrayList<>();
        rig.validators.add((tree, prohibited, emitted, ctx) -> {
            if (tree.isArray()) {
                seen.add(tree.deepCopy());
                emittedSeen.add(Set.copyOf(emitted));
            }
            return ValidationResult.ok();
        });

        SourceResult result = rig.follow(rig.direct("parcel-api", "parcel-ref", "P-1"))
                .sources().get(rig.label("parcel-api"));

        String token = result.links().get(0).link();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).toString()).contains(token);
        assertThat(emittedSeen.get(0)).contains(token);
    }

    // ---- the binding comes from the live session (task 252, review round 1) ----

    private static PrivacyContext contextOf(String scope, Instant end) {
        return new PrivacyContext(scope, PrivacyScopeType.CASE, "DEFAULT", "test", end,
                PseudonymisationVersion.HMAC_SHA256_V1);
    }

    @Test
    void anotherCallerAnotherScopeAndAnExpiredSessionEachRefuseAGenuineLink() {
        FollowRig other = rig();
        String link = other.direct("parcel-api", "parcel-ref", "P-1");

        Throwable caller = catchThrowable(() -> other.orchestrator().follow(FollowRequest.of(link), other.context(),
                new InvestigationContext("investigator-2", "client-1", "CASE-1", Set.of())));
        assertThat(caller).isInstanceOf(AuditedRefusalException.class);
        assertThat(other.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_AUTH_FAILED");

        FollowRig scope = rig();
        Throwable scoped = catchThrowable(() -> scope.orchestrator().follow(FollowRequest.of(link),
                contextOf("SCOPE-2", SCOPE_END), scope.caller()));
        assertThat(scoped).isInstanceOf(AuditedRefusalException.class);
        assertThat(scope.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_AUTH_FAILED");

        FollowRig expired = rig();
        Throwable ended = catchThrowable(() -> expired.orchestrator().follow(FollowRequest.of(link),
                contextOf("SCOPE-1", NOW), expired.caller()));
        assertThat(ended).isInstanceOf(AuditedRefusalException.class);
        assertThat(expired.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_EXPIRED");

        assertThat(other.fetches.get() + scope.fetches.get() + expired.fetches.get()).isZero();
    }

    // ---- the relation and the resolver name are still declared (task 252, review round 1) ----

    @Test
    void aDirectLinkToATargetThatOnlyAcceptsTheNamespaceThroughAResolverIsRelationUndeclared() {
        FollowRig rig = rig().resolver("parcel-to-depot", "parcel-ref", "depot-ref",
                key -> new KeyResolver.Resolution(List.of("D-1"), false));

        refusal(rig, rig.direct("depot-api", "parcel-ref", "P-1"));

        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_RELATION_UNDECLARED");
        assertThat(rig.fetches.get()).isZero();
    }

    @Test
    void aTranslatedLinkNamingAResolverThatIsGoneIsUndeclaredEvenWhenAnotherResolverServesTheSameTarget() {
        FollowRig rig = rig().resolver("parcel-to-depot", "parcel-ref", "depot-ref",
                key -> new KeyResolver.Resolution(List.of("D-1"), false));

        refusal(rig, rig.translated("depot-api", "parcel-ref", "gone-resolver", "P-1"));

        assertThat(rig.lastAudit().fieldDispositions()).containsEntry("link", "REFUSED:LINK_RELATION_UNDECLARED");
        assertThat(rig.resolverCalls).isEmpty();
        assertThat(rig.fetches.get()).isZero();
    }
}
