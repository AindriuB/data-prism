package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.ReadReservation;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
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
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Labels are one decision per request, and targeting selects adapters before anything is reserved
 * (catalog sections 3 and 6). Counting adapters prove what was and was not called.
 */
class LabelsAndTargetingTest {

    static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
    static final String SCOPE = "scope-a";

    record Thing(String id, String value) {
    }

    /** A source that counts how often it is called. */
    static final class CountingSource implements DataSourceAdapter<Thing> {
        private final String name;
        private final Set<String> entityTypes;
        private final AtomicInteger calls;

        CountingSource(String name, Set<String> entityTypes, AtomicInteger calls) {
            this.name = name;
            this.entityTypes = entityTypes;
            this.calls = calls;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public Class<Thing> responseType() {
            return Thing.class;
        }

        @Override
        public SourceDescription description() {
            return new SourceDescription(entityTypes, null, null, List.of(), null, null);
        }

        @Override
        public Thing fetch(DataRequest request) {
            calls.incrementAndGet();
            return new Thing("1", "raw");
        }
    }

    /** A budget that counts reservations, so a refusal before the budget can be seen as zero. */
    static final class CountingBudget implements ScopeBudget {
        private final InMemoryScopeBudget delegate = new InMemoryScopeBudget();
        final AtomicInteger reservations = new AtomicInteger();

        @Override
        public boolean tryRead(String scopeId, String subjectId, int budget) {
            return delegate.tryRead(scopeId, subjectId, budget);
        }

        @Override
        public int reads(String scopeId, String subjectId) {
            return delegate.reads(scopeId, subjectId);
        }

        @Override
        public void forget(String scopeId) {
            delegate.forget(scopeId);
        }

        @Override
        public ReadReservation reserve(String scopeId, String subjectId, int reads, int subjectBudget,
                                       int scopeTotalBudget) {
            reservations.incrementAndGet();
            return delegate.reserve(scopeId, subjectId, reads, subjectBudget, scopeTotalBudget);
        }

        @Override
        public void release(ReadReservation reservation, int unused) {
            delegate.release(reservation, unused);
        }
    }

    /** One orchestrator over counting sources, with every audit event kept. */
    static final class Harness {
        final List<AuditEvent> events = new ArrayList<>();
        final Map<String, AtomicInteger> calls = new LinkedHashMap<>();
        final CountingBudget budget = new CountingBudget();
        final SourceAliasing aliasing;
        final DefaultContextOrchestrator orchestrator;

        Harness(OrchestrationOptions options, RequestLimits limits, ValueTokenSource tokens,
                Map<String, Set<String>> sources) {
            this.aliasing = new SourceAliasing(tokens);
            List<DataSourceAdapter<?>> adapters = new ArrayList<>();
            sources.forEach((name, types) -> {
                AtomicInteger counter = new AtomicInteger();
                calls.put(name, counter);
                adapters.add(new CountingSource(name, types, counter));
            });
            var resolver = new DefaultFieldMetadataResolver();
            var mapper = JsonMapper.builder().build();
            LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
            this.orchestrator = new DefaultContextOrchestrator(
                    adapters,
                    (source, ctx) -> new ScrubResult(mapper.createObjectNode().put("value", "ok"), Set.of()),
                    resolver, List.of(ok, new SensitivePatternValidator()),
                    (subjectId, namespace, ctx) -> "SUBJ-1",
                    new ParameterFingerprinter(KEYS),
                    new AuditRecorder(events::add, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
                            ZoneOffset.UTC), "test-1"),
                    new PassThroughIdentityResolver(),
                    new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(),
                            SourceFanOutOptions.defaults()),
                    budget, limits, new NamespaceCorrelationService(resolver), aliasing, PrivacyMetrics.none(),
                    options);
        }

        int called(String source) {
            return calls.get(source).get();
        }

        int calledTotal() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        AuditEvent last() {
            return events.get(events.size() - 1);
        }
    }

    static Map<String, Set<String>> sources(String... names) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String name : names) {
            out.put(name, Set.of());
        }
        return out;
    }

    static PrivacyContext context(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    static InvestigationContext caller(boolean exposeNames) {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1",
                exposeNames ? Set.of(Capability.EXPOSE_SOURCE_NAMES) : Set.of());
    }

    static HmacValueTokenSource hmac() {
        return new HmacValueTokenSource(KEYS);
    }

    private static OrchestrationOptions realNamesIn(String... scopes) {
        return OrchestrationOptions.defaults().withRealNames(new RealNamesPolicy(List.of(scopes)));
    }

    // -- the effective setting ------------------------------------------------------------------

    @Test
    @DisplayName("labels are real only when the scope permits real names AND the caller holds the capability")
    void fourCombinations() {
        for (boolean scopePermits : new boolean[] {false, true}) {
            for (boolean capability : new boolean[] {false, true}) {
                Harness h = new Harness(realNamesIn(scopePermits ? SCOPE : "another-scope"), RequestLimits.DEFAULT,
                        hmac(), sources("alpha", "beta"));

                ContextResponse response = h.orchestrator.buildContext(ContextRequest.of("THING", "1"),
                        context(SCOPE), caller(capability));

                boolean real = scopePermits && capability;
                assertThat(response.sources().keySet())
                        .as("scope permits=%s capability=%s", scopePermits, capability)
                        .isEqualTo(real ? Set.of("alpha", "beta") : Set.of(
                                h.aliasing.aliasFor("alpha", context(SCOPE)), h.aliasing.aliasFor("beta", context(SCOPE))));
                assertThat(h.last().fieldDispositions()).containsEntry("names", real ? "REAL" : "ALIAS");
            }
        }
    }

    @Test
    @DisplayName("the capability alone does not show real names while no scope is permitted (the default)")
    void capabilityAloneIsNotEnough() {
        Harness h = new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, hmac(),
                sources("alpha", "beta"));

        ContextResponse response = h.orchestrator.buildContext(ContextRequest.of("THING", "1"),
                context(SCOPE), caller(true));

        assertThat(response.sources().keySet()).doesNotContain("alpha", "beta");
        assertThat(h.last().fieldDispositions()).containsEntry("names", "ALIAS");
    }

    @Test
    @DisplayName("the scope setting alone does not show real names to a caller without the capability")
    void scopeAloneIsNotEnough() {
        Harness h = new Harness(realNamesIn("*"), RequestLimits.DEFAULT, hmac(), sources("alpha", "beta"));

        ContextResponse response = h.orchestrator.buildContext(ContextRequest.of("THING", "1"),
                context(SCOPE), caller(false));

        assertThat(response.sources().keySet()).doesNotContain("alpha", "beta");
    }

    @Test
    @DisplayName("one response never mixes label kinds, and the audit still names the real sources")
    void neverMixed() {
        Harness h = new Harness(realNamesIn(SCOPE), RequestLimits.DEFAULT, hmac(), sources("alpha", "beta", "gamma"));

        ContextResponse response = h.orchestrator.buildContext(ContextRequest.of("THING", "1"),
                context(SCOPE), caller(true));

        assertThat(response.sources().keySet()).containsExactlyInAnyOrder("alpha", "beta", "gamma");
        assertThat(h.last().sourceSystems()).contains("alpha:ANSWERED", "beta:ANSWERED", "gamma:ANSWERED");
    }

    @Test
    @DisplayName("a source has the same alias in every call of one scope, and a different one in another scope")
    void stableWithinScopeDifferentAcross() {
        Harness h = new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, hmac(),
                sources("alpha", "beta"));

        Set<String> first = h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE), caller(false))
                .sources().keySet();
        Set<String> second = h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE), caller(false))
                .sources().keySet();
        Set<String> other = h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context("scope-b"),
                caller(false)).sources().keySet();

        assertThat(second).isEqualTo(first);
        assertThat(other).doesNotContainAnyElementsOf(first);
    }

    // -- collision ------------------------------------------------------------------------------

    @Test
    @DisplayName("two sources with one label refuse the call, and the audit carries both real names")
    void collision() {
        ValueTokenSource constant = new ValueTokenSource() {
            @Override
            public String hash(String value, PrivacyNamespace namespace, PrivacyContext context) {
                return "h";
            }

            @Override
            public String token(String value, PrivacyNamespace namespace, PrivacyContext context) {
                return "same-label";
            }
        };
        Harness h = new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, constant,
                sources("alpha", "beta", "gamma"));

        assertThatThrownBy(() -> h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE),
                caller(false)))
                .isInstanceOf(AuditedRefusalException.class)
                .satisfies(e -> assertThat(((AuditedRefusalException) e).code()).isEqualTo("SOURCE_LABEL_COLLISION"));

        assertThat(h.calledTotal()).isZero();
        assertThat(h.budget.reservations).hasValue(0);
        assertThat(h.last().policyDecision()).isEqualTo("DENY:SOURCE_LABEL_COLLISION");
        assertThat(h.last().sourceSystems()).contains("alpha:REFUSED", "beta:REFUSED", "gamma:REFUSED");
        assertThat(h.last().fieldDispositions()).containsEntry("names", "ALIAS");
    }

    // -- targeting ------------------------------------------------------------------------------

    private static String message(Throwable t) {
        return ((AuditedRefusalException) t).code() + "|" + ((AuditedRefusalException) t).path() + "|" + t.getMessage();
    }

    @Test
    @DisplayName("with targetSources only the chosen adapters are called")
    void onlyChosenAreCalled() {
        Harness h = new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, hmac(),
                sources("alpha", "beta", "gamma"));
        String betaLabel = h.aliasing.aliasFor("beta", context(SCOPE));

        ContextResponse response = h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(List.of(betaLabel)), context(SCOPE), caller(false));

        assertThat(h.called("beta")).isEqualTo(1);
        assertThat(h.called("alpha")).isZero();
        assertThat(h.called("gamma")).isZero();
        assertThat(response.sources().keySet()).containsExactly(betaLabel);
        assertThat(h.last().sourceSystems()).containsExactly("beta:ANSWERED");
    }

    @Test
    @DisplayName("real names select sources when real names are in effect")
    void realNamesSelectWhenInEffect() {
        Harness h = new Harness(realNamesIn(SCOPE), RequestLimits.DEFAULT, hmac(), sources("alpha", "beta"));

        ContextResponse response = h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(List.of("alpha")), context(SCOPE), caller(true));

        assertThat(response.sources().keySet()).containsExactly("alpha");
        assertThat(h.called("beta")).isZero();
    }

    @Test
    @DisplayName("a legacy source name over 64 characters is selectable by its real label when the estate "
            + "is too large to ask unselected")
    void longLegacyNameIsSelectable() {
        RequestLimits two = new RequestLimits(2, 500, 512 * 1024, Duration.ofSeconds(3), 4, 100,
                Duration.ofSeconds(10), 50_000);
        String longName = "legacy-".concat("n".repeat(60));
        assertThat(longName.length()).isEqualTo(67);
        Harness h = new Harness(realNamesIn(SCOPE), two, hmac(), sources("alpha", "beta", longName));

        assertThatThrownBy(() -> h.orchestrator.buildContext(
                ContextRequest.of("THING", "1"), context(SCOPE), caller(true)))
                .isInstanceOf(AuditedRefusalException.class);

        ContextResponse response = h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(List.of(longName)), context(SCOPE), caller(true));

        assertThat(response.sources().keySet()).containsExactly(longName);
        assertThat(h.called(longName)).isEqualTo(1);
        assertThat(h.calledTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown, wrong-kind, unpermitted or non-serving label refuses the whole call identically, "
            + "calls nothing, charges nothing, and the audit says which kind without the label")
    void fourRefusalsAreIdentical() {
        Map<String, Set<String>> estate = new LinkedHashMap<>();
        estate.put("alpha", Set.of());
        estate.put("beta", Set.of());
        estate.put("gamma", Set.of());
        estate.put("delta", Set.of("OTHER_KIND"));
        OrchestrationOptions options = OrchestrationOptions.defaults()
                .withVisibility((caller, source) -> !source.equals("gamma"));
        Harness h = new Harness(options, RequestLimits.DEFAULT, hmac(), estate);
        PrivacyContext ctx = context(SCOPE);
        String good = h.aliasing.aliasFor("alpha", ctx);
        String sentUnknown = "probe-label-unknown-7731";
        String sentNotPermitted = h.aliasing.aliasFor("gamma", ctx);
        String sentMismatch = h.aliasing.aliasFor("delta", ctx);

        Map<String, String> byCase = new LinkedHashMap<>();
        byCase.put("CATALOG_UNKNOWN_SOURCE", sentUnknown);
        byCase.put("CATALOG_UNKNOWN_SOURCE (wrong kind)", "beta");
        byCase.put("CATALOG_SOURCE_NOT_PERMITTED", sentNotPermitted);
        byCase.put("CATALOG_ENTITY_TYPE_MISMATCH", sentMismatch);

        List<String> seen = new ArrayList<>();
        for (Map.Entry<String, String> c : byCase.entrySet()) {
            int before = h.events.size();
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> h.orchestrator.buildContext(
                    ContextRequest.of("THING", "1").withTargetSources(List.of(good, c.getValue())), ctx, caller(false)));

            assertThat(thrown).as(c.getKey()).isInstanceOf(AuditedRefusalException.class);
            assertThat(((AuditedRefusalException) thrown).code()).isEqualTo("SOURCE_NOT_AVAILABLE");
            seen.add(message(thrown));
            assertThat(h.events).hasSize(before + 1);
            AuditEvent event = h.last();
            assertThat(event.policyDecision()).isEqualTo("DENY:SOURCE_NOT_AVAILABLE");
            assertThat(event.sourceSystems()).isEmpty();
            assertThat(event.fieldDispositions()).containsEntry("catalog",
                    "REFUSED:" + c.getKey().replace(" (wrong kind)", ""));
            assertThat(event.fieldDispositions()).containsEntry("names", "ALIAS");
            assertThat(event.toString()).doesNotContain(c.getValue().equals("beta") ? "NOT-CHECKED" : c.getValue());
            assertThat(event.fieldDispositions().values()).noneMatch(v -> v.contains(sentUnknown));
        }

        assertThat(seen).hasSize(4).containsOnly(seen.get(0));
        assertThat(h.calledTotal()).isZero();
        assertThat(h.budget.reservations).hasValue(0);
    }

    @Test
    @DisplayName("an alias sent while real names are in effect does not resolve")
    void aliasWhileRealIsRefused() {
        Harness h = new Harness(realNamesIn(SCOPE), RequestLimits.DEFAULT, hmac(), sources("alpha", "beta"));
        String alias = h.aliasing.aliasFor("alpha", context(SCOPE));

        assertThatThrownBy(() -> h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(List.of(alias)), context(SCOPE), caller(true)))
                .isInstanceOf(AuditedRefusalException.class)
                .satisfies(e -> assertThat(((AuditedRefusalException) e).code()).isEqualTo("SOURCE_NOT_AVAILABLE"));
        assertThat(h.calledTotal()).isZero();
    }

    @Test
    @DisplayName("more labels than maxSources refuses with SOURCE_SELECTION_TOO_LARGE and calls nothing; "
            + "exactly maxSources is answered")
    void tooLarge() {
        RequestLimits two = new RequestLimits(2, 500, 512 * 1024, Duration.ofSeconds(3), 4, 100,
                Duration.ofSeconds(10), 50_000);
        Harness h = new Harness(OrchestrationOptions.defaults(), two, hmac(), sources("alpha", "beta", "gamma"));
        PrivacyContext ctx = context(SCOPE);
        List<String> three = List.of("alpha", "beta", "gamma").stream().map(n -> h.aliasing.aliasFor(n, ctx)).toList();

        assertThatThrownBy(() -> h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(three), ctx, caller(false)))
                .isInstanceOf(AuditedRefusalException.class)
                .satisfies(e -> assertThat(((AuditedRefusalException) e).code())
                        .isEqualTo("SOURCE_SELECTION_TOO_LARGE"));
        assertThat(h.calledTotal()).isZero();
        assertThat(h.budget.reservations).hasValue(0);
        assertThat(h.last().policyDecision()).isEqualTo("DENY:SOURCE_SELECTION_TOO_LARGE");
        assertThat(h.last().fieldDispositions()).doesNotContainKey("catalog");

        ContextResponse response = h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(three.subList(0, 2)), ctx, caller(false));
        assertThat(response.sources()).hasSize(2);
    }

    @Test
    @DisplayName("a request with no targetSources is unchanged while the estate is within the limit")
    void unselectedUnchanged() {
        Harness h = new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, hmac(),
                sources("alpha", "beta", "gamma"));

        ContextResponse response = h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE),
                caller(false));

        assertThat(response.sources()).hasSize(3);
        assertThat(h.calledTotal()).isEqualTo(3);
    }

    @Test
    @DisplayName("a refused call carries the names key too")
    void deniedCallsCarryNames() {
        Harness h = new Harness(realNamesIn(SCOPE), RequestLimits.DEFAULT, hmac(), sources("alpha"));

        assertThatThrownBy(() -> h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(List.of("nope")), context(SCOPE), caller(true)))
                .isInstanceOf(AuditedRefusalException.class);

        assertThat(h.last().fieldDispositions()).containsEntry("names", "REAL");
    }
}
