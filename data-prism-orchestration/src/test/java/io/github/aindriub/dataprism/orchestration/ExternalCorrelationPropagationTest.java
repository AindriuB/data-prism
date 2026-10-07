package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The call's external correlation id reaches the audit event and every source request, and only its own call's. */
class ExternalCorrelationPropagationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-110-test-key-not-for-any-real-data-32b");
    private static final CorrelationIdPolicy POLICY = CorrelationIdPolicy.opaque("ext-[a-z0-9-]{1,40}");

    private record Thing(String id, String value) {
    }

    private final List<AuditEvent> audited = Collections.synchronizedList(new ArrayList<>());
    /** sourceName to the ids that source saw, in call order. */
    private final Map<String, List<Optional<ExternalCorrelationId>>> seen = new ConcurrentHashMap<>();

    private static ExternalCorrelationId id(String value) {
        return POLICY.validate(value).orElseThrow();
    }

    private DataSourceAdapter<Thing> recording(String name, Thing thing) {
        return new DataSourceAdapter<>() {
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
                seen.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(request.context().externalCorrelationId());
                return thing;
            }
        };
    }

    private DefaultContextOrchestrator orchestrator(Thing thing) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        ObjectMapper mapper = new ObjectMapper();
        ScrubbingEngine scrubber = (source, ctx) ->
                new ScrubResult(mapper.createObjectNode().put("value", "ok"), Set.of());
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        return new DefaultContextOrchestrator(
                List.of(recording("source-a", thing), recording("source-b", thing), recording("source-c", thing)),
                scrubber, resolver, List.of(ok, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(audited::add, CLOCK, "test-110"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), PrivacyMetrics.none()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    private static ContextRequest requestWith(ExternalCorrelationId id) {
        return new ContextRequest("THING", "1", Set.of(), ContextRequest.DEFAULT_TOOL_NAME, false, "", "",
                Optional.ofNullable(id));
    }

    @Test
    @DisplayName("every pre-existing ContextRequest constructor and factory gives Optional.empty(), never null")
    void existingShapesHaveNoExternalId() {
        assertThat(ContextRequest.of("THING", "1").externalCorrelationId()).isEmpty();
        assertThat(new ContextRequest("THING", "1", Set.of()).externalCorrelationId()).isEmpty();
        assertThat(ContextRequest.comparison("THING", "1", Set.of(), "compare_entity_sources")
                .externalCorrelationId()).isEmpty();
        assertThat(new ContextRequest("THING", "1", Set.of(), "t", false, "", "", null).externalCorrelationId())
                .isEmpty();
    }

    @Test
    @DisplayName("all three sources, fetched in parallel, receive the call's id")
    void everySourceReceivesTheId() {
        orchestrator(new Thing("1", "raw")).buildContext(requestWith(id("ext-call-1")), context(), caller());

        assertThat(seen).containsOnlyKeys("source-a", "source-b", "source-c");
        seen.values().forEach(ids -> assertThat(ids).containsExactly(Optional.of(id("ext-call-1"))));
    }

    @Test
    @DisplayName("a call with no id gives every source an empty one")
    void noIdMeansEmptyForSources() {
        orchestrator(new Thing("1", "raw")).buildContext(requestWith(null), context(), caller());

        seen.values().forEach(ids -> assertThat(ids).containsExactly(Optional.empty()));
        assertThat(audited).singleElement().satisfies(e -> assertThat(e.externalCorrelationId()).isEmpty());
    }

    @Test
    @DisplayName("two concurrent calls with different ids each see only their own id")
    void concurrentCallsDoNotCrossTalk() throws Exception {
        DefaultContextOrchestrator orchestrator = orchestrator(new Thing("1", "raw"));
        int rounds = 20;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (String value : List.of("ext-one", "ext-two")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        orchestrator.buildContext(requestWith(id(value)), context(), caller());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        // Each call writes one audit event; its id must be the id that call's sources saw.
        assertThat(audited).hasSize(2 * rounds);
        assertThat(audited).extracting(AuditEvent::externalCorrelationId)
                .containsOnly("ext-one", "ext-two");
        assertThat(audited.stream().filter(e -> e.externalCorrelationId().equals("ext-one"))).hasSize(rounds);
        seen.values().forEach(ids -> {
            assertThat(ids).hasSize(2 * rounds);
            assertThat(ids.stream().filter(o -> o.equals(Optional.of(id("ext-one")))).count()).isEqualTo(rounds);
            assertThat(ids.stream().filter(o -> o.equals(Optional.of(id("ext-two")))).count()).isEqualTo(rounds);
        });
    }

    @Test
    @DisplayName("the ALLOW event carries the id")
    void allowEventCarriesId() {
        orchestrator(new Thing("1", "raw")).buildContext(requestWith(id("ext-allow")), context(), caller());

        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.policyDecision()).isEqualTo("ALLOW");
            assertThat(e.externalCorrelationId()).isEqualTo("ext-allow");
        });
    }

    @Test
    @DisplayName("the DENY event of an orchestrator refusal carries the id")
    void denyEventCarriesId() {
        assertThatThrownBy(() -> orchestrator(null).buildContext(requestWith(id("ext-deny")), context(), caller()))
                .isInstanceOf(PrivacyRefusedException.class);

        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.policyDecision()).isEqualTo("DENY:NO_SOURCE_DATA");
            assertThat(e.externalCorrelationId()).isEqualTo("ext-deny");
        });
    }
}
