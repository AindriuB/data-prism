package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.CostHint;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FreshnessHint;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code list_sources} and {@code describe_source} without paging concerns (catalog.md sections 3 and 4). */
class CatalogQueriesTest {

    static final Instant T0 = Instant.parse("2029-06-01T00:00:00Z");
    static final Instant SCOPE_END = Instant.parse("2030-01-01T00:00:00Z");

    /** A clock the test moves, and can make fail. */
    static final class TestClock extends Clock {
        volatile Instant now = T0;
        volatile boolean failing;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (failing) {
                throw new IllegalStateException("clock down");
            }
            return now;
        }
    }

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

        int count(Metric metric) {
            return counts.getOrDefault(metric, 0);
        }
    }

    /** What the orchestrator answers for a source: its scrubber, asked about the adapter's response type. */
    static ContextOrchestrator orchestratorOver(ScrubbingEngine engine, List<DataSourceAdapter<?>> adapters) {
        return new ContextOrchestrator() {
            @Override
            public ContextResponse buildContext(ContextRequest request, PrivacyContext privacy,
                                                InvestigationContext caller) {
                throw new AssertionError("the catalog tools never build context");
            }

            @Override
            public Optional<ModelDescription> describe(String sourceName, PrivacyContext privacy, boolean links) {
                return adapters.stream().filter(a -> a.sourceName().equals(sourceName)).findFirst()
                        .flatMap(a -> engine.describe(sourceName, a.responseType(), privacy, links));
            }
        };
    }

    /** Describes every model the same way, or fails. */
    static final class StubEngine implements ScrubbingEngine {
        Optional<ModelDescription> answer;
        boolean throwing;
        boolean lastLinksEnabled;

        StubEngine(Optional<ModelDescription> answer) {
            this.answer = answer;
        }

        @Override
        public ScrubResult scrub(Object source, PrivacyContext context) {
            throw new AssertionError("the catalog never scrubs");
        }

        @Override
        public Optional<ModelDescription> describe(Class<?> model, PrivacyContext context, boolean linksEnabled) {
            lastLinksEnabled = linksEnabled;
            if (throwing) {
                throw new IllegalStateException("describe failed with SECRET-TEXT");
            }
            return answer;
        }
    }

    static final class MapKeys implements SecretKeyProvider {
        private final Map<String, byte[]> keys = Map.of(
                "pseudo-key", "pseudonymisation-key-0123456789abcdef".getBytes(StandardCharsets.US_ASCII),
                "link-key", "link-key-material-0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII));

        @Override
        public byte[] secret(String keyId) {
            byte[] material = keys.get(keyId);
            if (material == null) {
                throw new IllegalStateException("unknown key");
            }
            return material.clone();
        }
    }

    /** A limiter the test steers. */
    static final class StubLimiter implements LinkAttemptLimiter {
        LockState lock = LockState.UNLOCKED;
        Admission admission = Admission.WITHIN_LIMIT;
        boolean throwOnLock;
        boolean nullLock;
        boolean throwOnRecord;
        boolean nullRecord;
        int recorded;

        @Override
        public LockState lockState(io.github.aindriub.dataprism.core.link.AttemptSubject subject) {
            if (throwOnLock) {
                throw new IllegalStateException("limiter down");
            }
            return nullLock ? null : lock;
        }

        @Override
        public Admission recordFailure(io.github.aindriub.dataprism.core.link.AttemptSubject subject) {
            recorded++;
            if (throwOnRecord) {
                throw new IllegalStateException("limiter down");
            }
            return nullRecord ? null : admission;
        }
    }

    /** One catalog over the given names, with the pieces a test wants to reach. */
    static final class Fixture {
        final TestClock clock = new TestClock();
        final CountingMetrics metrics = new CountingMetrics();
        final StubEngine engine = new StubEngine(Optional.empty());
        final StubLimiter limiter = new StubLimiter();
        final LinkKeys keys = new LinkKeys(new MapKeys(), "link-key", "pseudo-key");
        final LinkCodec codec = new LinkCodec(
                new LinkSettings("link-key", Optional.empty(), 1024, true, Duration.ofSeconds(30)), keys, clock);
        final CatalogQueries queries;
        final List<DataSourceAdapter<?>> adapters = new ArrayList<>();

        Fixture(List<String> names, PagingSettings paging, boolean links) {
            this(names, paging, links, true, List.of(new SensitivePatternValidator()), Map.of());
        }

        Fixture(List<String> names, PagingSettings paging, boolean links, boolean chainable,
                List<LlmResponseValidator> validators, Map<String, SourceDescription> descriptions) {
            this(names, paging, links, chainable, validators, descriptions, new HmacValueTokenSource(
                    StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data")));
        }

        Fixture(List<String> names, PagingSettings paging, boolean links, boolean chainable,
                List<LlmResponseValidator> validators, Map<String, SourceDescription> descriptions,
                ValueTokenSource tokens) {
            for (String name : names) {
                SourceDescription description = descriptions.getOrDefault(name, SourceDescription.undeclared());
                adapters.add(new TestAdapters.DescribedAdapter(name, description));
            }
            queries = new CatalogQueries(SourceCatalog.build(adapters, Map.of()), SourceVisibility.allowAll(),
                    new SourceAliasing(tokens),
                    new RealNamesPolicy(List.of("scope-*")), orchestratorOver(engine, adapters), validators, paging,
                    links ? new CatalogQueries.PageLinks(codec, keys, new LinkAttemptGate(codec, limiter)) : null,
                    chainable, metrics, clock);
        }

        CatalogQueries.Outcome list(CatalogQueries.ListRequest request, boolean real) {
            return queries.listSources(request, caller("investigator-1", real), context("scope-a"), null, "c-1");
        }

        CatalogQueries.Outcome list(Integer pageSize, String page) {
            return list(new CatalogQueries.ListRequest(null, null, pageSize, page), true);
        }
    }

    static PrivacyContext context(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test", SCOPE_END,
                PseudonymisationVersion.HMAC_SHA256_V1);
    }

    static InvestigationContext caller(String principal, boolean exposeNames) {
        return new InvestigationContext(principal, "client-1", "CASE-1",
                exposeNames ? Set.of(Capability.EXPOSE_SOURCE_NAMES) : Set.of());
    }

    static List<String> names(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> String.format("src-%03d", i)).toList();
    }

    static List<String> sources(CatalogQueries.Outcome outcome) {
        List<String> labels = new ArrayList<>();
        outcome.body().get("sources").forEach(node -> labels.add(node.get("source").asString()));
        return labels;
    }

    static final PagingSettings DEFAULT = PagingSettings.defaults();

    // ---- list_sources body ----

    @Test
    @DisplayName("entries are in ascending byte order of label, whatever order the adapters were registered in")
    void entriesAreInByteOrderOfLabel() {
        Fixture fx = new Fixture(List.of("zz-src", "aa-src", "mm-src"), DEFAULT, false);

        List<String> real = sources(fx.list(null, null));
        List<String> aliases = sources(fx.list(new CatalogQueries.ListRequest(null, null, null, null), false));

        assertThat(real).containsExactly("aa-src", "mm-src", "zz-src");
        assertThat(aliases).hasSize(3).isSortedAccordingTo(Comparator.comparing(
                label -> label.getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned));
        assertThat(aliases).doesNotContainAnyElementsOf(real);
    }

    @Test
    @DisplayName("one label kind per response, with the description of that kind")
    void oneLabelKindWithItsDescription() {
        SourceDescription described = new SourceDescription(Set.of("PARCEL"), "Alias text for the first record set.",
                "Real text for the first record set.", List.of(new KeyNamespace("parcel-ref")), CostHint.LOW,
                FreshnessHint.DAILY);
        Fixture fx = new Fixture(List.of("ldgr-src"), DEFAULT, false, true,
                List.of(new SensitivePatternValidator()), Map.of("ldgr-src", described));

        CatalogQueries.Outcome real = fx.list(null, null);
        CatalogQueries.Outcome alias = fx.list(new CatalogQueries.ListRequest(null, null, null, null), false);

        assertThat(real.body().get("labels").asString()).isEqualTo("REAL");
        assertThat(real.body().get("sources").get(0).get("description").asString()).isEqualTo("Real text for the first record set.");
        assertThat(real.names()).isEqualTo(SourceCatalog.LabelKind.REAL);
        assertThat(alias.body().get("labels").asString()).isEqualTo("ALIAS");
        assertThat(alias.body().get("sources").get(0).get("description").asString()).isEqualTo("Alias text for the first record set.");
        assertThat(alias.body().toString()).doesNotContain("ldgr-src");
    }

    @Test
    @DisplayName("entityTypes, cost and freshness are omitted when undeclared, and nothing is null")
    void undeclaredPartsAreOmitted() {
        Fixture fx = new Fixture(List.of("bare"), DEFAULT, false);

        JsonNode entry = fx.list(null, null).body().get("sources").get(0);

        assertThat(entry.propertyNames()).containsExactlyInAnyOrder("source", "description", "inputKeys");
        assertThat(entry.get("inputKeys")).isEmpty();
        assertThat(fx.list(null, null).body().toString()).doesNotContain("null");
    }

    @Test
    @DisplayName("declared parts are present")
    void declaredPartsArePresent() {
        SourceDescription described = new SourceDescription(Set.of("PARCEL", "DEPOT"), null, null,
                List.of(new KeyNamespace("parcel-ref"), new KeyNamespace("depot-code")), CostHint.HIGH,
                FreshnessHint.LIVE);
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false, true,
                List.of(new SensitivePatternValidator()), Map.of("ledger", described));

        JsonNode entry = fx.list(null, null).body().get("sources").get(0);

        assertThat(entry.get("entityTypes").valueStream().map(JsonNode::asString)).containsExactly("DEPOT", "PARCEL");
        assertThat(entry.get("inputKeys").valueStream().map(JsonNode::asString)).containsExactly("parcel-ref", "depot-code");
        assertThat(entry.get("cost").asString()).isEqualTo("HIGH");
        assertThat(entry.get("freshness").asString()).isEqualTo("LIVE");
    }

    @Test
    @DisplayName("filters match exactly and apply before the slice; a filter matching nothing is an empty last page")
    void filtersApplyBeforeTheSlice() {
        Map<String, SourceDescription> described = Map.of(
                "a-parcel", new SourceDescription(Set.of("PARCEL"), null, null, List.of(), null, null),
                "b-depot", new SourceDescription(Set.of("DEPOT"), null, null, List.of(new KeyNamespace("depot-code")), null, null),
                "c-parcel", new SourceDescription(Set.of("PARCEL"), null, null, List.of(new KeyNamespace("depot-code")), null, null),
                "d-any", new SourceDescription(Set.of("OTHER"), null, null, List.of(), null, null),
                "e-parcel", new SourceDescription(Set.of("PARCEL"), null, null, List.of(), null, null));
        Fixture fx = new Fixture(List.of("a-parcel", "b-depot", "c-parcel", "d-any", "e-parcel"), new PagingSettings(2, 10, Duration.ofMinutes(5)),
                true, true, List.of(new SensitivePatternValidator()), described);

        CatalogQueries.Outcome parcels = fx.list(new CatalogQueries.ListRequest("PARCEL", null, null, null), true);
        CatalogQueries.Outcome none = fx.list(new CatalogQueries.ListRequest("parcel", null, null, null), true);
        CatalogQueries.Outcome keyed = fx.list(new CatalogQueries.ListRequest(null, "depot-code", null, null), true);

        // The page is full (two entries) although the listing is longer: the filter ran before the slice.
        assertThat(sources(parcels)).containsExactly("a-parcel", "c-parcel");
        assertThat(parcels.body().get("page").get("last").asBoolean()).isFalse();
        assertThat(sources(none)).isEmpty();
        assertThat(none.body().get("page").get("last").asBoolean()).isTrue();
        assertThat(none.body().get("page").get("size").asInt()).isEqualTo(2);
        assertThat(sources(keyed)).containsExactly("b-depot", "c-parcel");
    }

    @Test
    @DisplayName("nothing in the body counts or hints at hidden sources")
    void hiddenSourcesLeaveNoTrace() {
        List<DataSourceAdapter<?>> adapters = new ArrayList<>();
        adapters.add(new TestAdapters.OldStyleAdapter("open-one"));
        adapters.add(new TestAdapters.OldStyleAdapter("closed-one"));
        CatalogQueries queries = new CatalogQueries(SourceCatalog.build(adapters, Map.of()),
                (caller, name) -> !name.startsWith("closed"),
                new SourceAliasing(new HmacValueTokenSource(StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data"))),
                new RealNamesPolicy(List.of("scope-*")), orchestratorOver(new StubEngine(Optional.empty()), adapters),
                List.of(new SensitivePatternValidator()), DEFAULT, null, true, PrivacyMetrics.none(), new TestClock());

        CatalogQueries.Outcome outcome = queries.listSources(new CatalogQueries.ListRequest(null, null, null, null),
                caller("investigator-1", true), context("scope-a"), null, "c-1");

        assertThat(sources(outcome)).containsExactly("open-one");
        assertThat(outcome.body().toString()).doesNotContain("closed").doesNotContain("more").doesNotContain("total");
        assertThat(outcome.sourceSystems()).containsExactly("open-one");
    }

    @Test
    @DisplayName("the audit parts name the real sources on the page and never a client string")
    void auditPartsHoldRealNamesOnly() {
        Fixture fx = new Fixture(List.of("one", "two"), DEFAULT, false);

        CatalogQueries.Outcome alias = fx.list(new CatalogQueries.ListRequest(null, "no-such-ns", null, null), false);

        assertThat(alias.sourceSystems()).isEmpty();
        CatalogQueries.Outcome all = fx.list(new CatalogQueries.ListRequest(null, null, null, null), false);
        assertThat(all.sourceSystems()).containsExactlyInAnyOrder("one", "two");
        assertThat(all.dispositions()).doesNotContainKey("link");
        assertThat(all.names()).isEqualTo(SourceCatalog.LabelKind.ALIAS);
    }

    @Test
    @DisplayName("no codec: the whole visible list on one page with last true, whatever pageSize says")
    void noCodecReturnsTheWholeList() {
        Fixture fx = new Fixture(names(7), new PagingSettings(2, 3, Duration.ofMinutes(5)), false);

        CatalogQueries.Outcome outcome = fx.list(1, null);

        assertThat(sources(outcome)).hasSize(7);
        assertThat(outcome.body().get("page").get("last").asBoolean()).isTrue();
        assertThat(outcome.body().get("page").has("next")).isFalse();
        assertThat(outcome.body().get("page").get("size").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("no codec: a call that carries page is refused as an invalid link, uncounted")
    void noCodecRefusesAPageToken() {
        Fixture fx = new Fixture(names(3), DEFAULT, false);

        CatalogQueries.Outcome outcome = fx.list(null, "AAAA");

        assertThat(outcome.refusalCode()).isEqualTo("LINK_INVALID");
        assertThat(outcome.dispositions()).containsEntry("link", "REFUSED:LINK_MALFORMED");
        assertThat(fx.limiter.recorded).isZero();
    }

    @Test
    @DisplayName("a response that fails a validator is withheld, fail closed")
    void aFailingValidatorWithholdsTheBody() {
        LlmResponseValidator rejecting = (response, prohibited, emitted, context) ->
                ValidationResult.failed(List.of(new Violation("$", "TEST", "test")));
        LlmResponseValidator throwing = (response, prohibited, emitted, context) -> {
            throw new IllegalStateException("boom");
        };

        for (LlmResponseValidator validator : List.of(rejecting, throwing)) {
            Fixture fx = new Fixture(names(2), DEFAULT, false, true, List.of(validator), Map.of());
            CatalogQueries.Outcome outcome = fx.list(null, null);
            assertThat(outcome.body()).isNull();
            assertThat(outcome.refusalCode()).isEqualTo(CatalogQueries.RESPONSE_REFUSED);
        }
    }

    @Test
    @DisplayName("description text with a pattern is refused at startup, so it never reaches a response")
    void aPatternInADescriptionIsRefusedAtStartup() {
        SourceDescription leaking = new SourceDescription(Set.of(), "Mail jane.doe@example.org for more.", null,
                List.of(), null, null);

        assertThatThrownBy(() -> new Fixture(List.of("ledger"), DEFAULT, false, true,
                List.of(new SensitivePatternValidator()), Map.of("ledger", leaking)))
                .hasMessageContaining("INVALID_SOURCE_DESCRIPTION");
    }

    @Test
    @DisplayName("PAGING_PAGES is incremented once per page served and not for a refusal")
    void pagingPagesCountsServedPages() {
        Fixture fx = new Fixture(names(5), new PagingSettings(2, 10, Duration.ofMinutes(5)), true);

        fx.list(null, null);
        fx.list(null, "not-a-link");

        assertThat(fx.metrics.count(Metric.PAGING_PAGES)).isEqualTo(1);
    }

    // ---- describe_source ----

    private static ModelDescription modelDescription() {
        return new ModelDescription(List.of(
                new FieldProtection("state", Protection.VERBATIM, null, null),
                new FieldProtection("recipientName", Protection.SYNTHETIC, "PERSON_NAME", null),
                new FieldProtection("parcel", Protection.ABSENT, null, "parcel-ref")),
                ModelDescription.Undeclared.REFUSE);
    }

    @Test
    @DisplayName("describe_source returns the schema from the scrub plan, chainable only while links are enabled")
    void describeReturnsTheSchema() {
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false);
        fx.engine.answer = Optional.of(modelDescription());

        CatalogQueries.Outcome outcome = fx.queries.describeSource("ledger", caller("p", true), context("scope-a"), "c-2");

        JsonNode body = outcome.body();
        assertThat(body.get("labels").asString()).isEqualTo("REAL");
        assertThat(body.get("source").asString()).isEqualTo("ledger");
        assertThat(body.get("undeclared").asString()).isEqualTo("REFUSE");
        assertThat(body.get("inputKeys")).isEmpty();
        List<JsonNode> fields = body.get("fields").valueStream().toList();
        assertThat(fields.stream().map(f -> f.get("path").asString())).containsExactly("parcel", "recipientName", "state");
        assertThat(fields.get(0).get("chainable").asBoolean()).isTrue();
        assertThat(fields.get(0).get("keyNamespace").asString()).isEqualTo("parcel-ref");
        assertThat(fields.get(1).get("valueNamespace").asString()).isEqualTo("PERSON_NAME");
        assertThat(fields.get(2).has("valueNamespace")).isFalse();
        assertThat(fields.get(2).has("chainable")).isFalse();
        assertThat(fx.engine.lastLinksEnabled).isTrue();
        assertThat(outcome.sourceSystems()).containsExactly("ledger");
    }

    @Test
    @DisplayName("with links disabled the description carries no chainable marker")
    void describeWithoutLinksHasNoChainable() {
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false, false, List.of(new SensitivePatternValidator()), Map.of());
        fx.engine.answer = Optional.of(modelDescription());

        CatalogQueries.Outcome outcome = fx.queries.describeSource("ledger", caller("p", true), context("scope-a"), "c-2");

        assertThat(outcome.body().toString()).doesNotContain("chainable").doesNotContain("keyNamespace");
        assertThat(fx.engine.lastLinksEnabled).isFalse();
    }

    @Test
    @DisplayName("an unknown label, a label of the wrong kind, a forbidden source and a failed description are one refusal")
    void describeRefusalsAreIdentical() {
        List<DataSourceAdapter<?>> adapters = List.of(new TestAdapters.OldStyleAdapter("open-one"),
                new TestAdapters.OldStyleAdapter("closed-one"));
        StubEngine engine = new StubEngine(Optional.of(modelDescription()));
        CatalogQueries queries = new CatalogQueries(SourceCatalog.build(adapters, Map.of()),
                (caller, name) -> !name.startsWith("closed"),
                new SourceAliasing(new HmacValueTokenSource(StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data"))),
                new RealNamesPolicy(List.of("scope-*")), orchestratorOver(engine, adapters),
                List.of(new SensitivePatternValidator()),
                DEFAULT, null, true, PrivacyMetrics.none(), new TestClock());
        InvestigationContext real = caller("p", true);
        InvestigationContext alias = caller("p", false);

        CatalogQueries.Outcome unknown = queries.describeSource("nothing", real, context("scope-a"), "c");
        CatalogQueries.Outcome forbidden = queries.describeSource("closed-one", real, context("scope-a"), "c");
        CatalogQueries.Outcome wrongKind = queries.describeSource("open-one", alias, context("scope-a"), "c");
        engine.throwing = true;
        CatalogQueries.Outcome threw = queries.describeSource("open-one", real, context("scope-a"), "c");
        engine.throwing = false;
        engine.answer = Optional.empty();
        CatalogQueries.Outcome empty = queries.describeSource("open-one", real, context("scope-a"), "c");

        for (CatalogQueries.Outcome refusal : List.of(unknown, forbidden, wrongKind, threw, empty)) {
            assertThat(refusal.body()).isNull();
            assertThat(refusal.refusalCode()).isEqualTo("SOURCE_NOT_AVAILABLE");
        }
        assertThat(unknown.dispositions()).containsEntry("catalog", "REFUSED:CATALOG_UNKNOWN_SOURCE");
        assertThat(forbidden.dispositions()).containsEntry("catalog", "REFUSED:CATALOG_SOURCE_NOT_PERMITTED");
        assertThat(wrongKind.dispositions()).containsEntry("catalog", "REFUSED:CATALOG_UNKNOWN_SOURCE");
        assertThat(threw.dispositions()).containsEntry("catalog", "REFUSED:CATALOG_DESCRIPTION_FAILED");
        assertThat(empty.dispositions()).containsEntry("catalog", "REFUSED:CATALOG_DESCRIPTION_FAILED");
        assertThat(unknown.sourceSystems()).isEmpty();
        assertThat(threw.sourceSystems()).containsExactly("open-one");
        assertThat(threw.toString()).doesNotContain("SECRET-TEXT");
    }

    @Test
    @DisplayName("the description contains no class name, base url or classification name")
    void describeHoldsNothingBeyondTheVocabulary() {
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false);
        fx.engine.answer = Optional.of(modelDescription());

        String body = fx.queries.describeSource("ledger", caller("p", true), context("scope-a"), "c").body().toString();

        assertThat(body).doesNotContain("OldStyleAdapter").doesNotContain("DescribedAdapter").doesNotContain("http")
                .doesNotContain("PII").doesNotContain("Thing").doesNotContain("class");
    }

    // ---- review round 1 ----

    /** A token source whose alias for every name is that name with a fixed shaped suffix, or that throws. */
    static final class ShapedTokens implements ValueTokenSource {
        final String suffix;
        boolean throwing;

        ShapedTokens(String suffix) {
            this.suffix = suffix;
        }

        @Override
        public String hash(String value, PrivacyNamespace namespace, PrivacyContext context) {
            return token(value, namespace, context);
        }

        @Override
        public String token(String value, PrivacyNamespace namespace, PrivacyContext context) {
            if (throwing) {
                throw new IllegalStateException("token source down");
            }
            return value + suffix;
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    /** An email shape, and a PPSN shape with a deliberately wrong check character. */
    private static final String SHAPED_SUFFIX = ".person@example.org";
    private static final String PPSN_SHAPED = "ORG-1234567A";

    @Test
    @DisplayName("an alias that has a sensitive shape is this scope's own value, so it does not refuse the page or the description")
    void aShapedAliasIsNotRefused() {
        Fixture fx = new Fixture(List.of("ledger", "other"), DEFAULT, false, true,
                List.of(new SensitivePatternValidator()), Map.of(), new ShapedTokens(SHAPED_SUFFIX));
        fx.engine.answer = Optional.of(modelDescription());
        InvestigationContext alias = caller("p", false);

        CatalogQueries.Outcome listed = fx.list(new CatalogQueries.ListRequest(null, null, null, null), false);
        CatalogQueries.Outcome described = fx.queries.describeSource("ledger" + SHAPED_SUFFIX, alias,
                context("scope-a"), "c-2");

        assertThat(listed.refused()).isFalse();
        assertThat(sources(listed)).contains("ledger" + SHAPED_SUFFIX);
        assertThat(described.refused()).isFalse();
    }

    @Test
    @DisplayName("a real name that has a sensitive shape is scanned and refuses the page and the description")
    void aShapedRealNameIsRefused() {
        for (String shaped : List.of("person@example.org", PPSN_SHAPED)) {
            Fixture fx = new Fixture(List.of(shaped, "other"), DEFAULT, false);
            fx.engine.answer = Optional.of(modelDescription());

            CatalogQueries.Outcome listed = fx.list(null, null);
            CatalogQueries.Outcome described = fx.queries.describeSource(shaped, caller("p", true),
                    context("scope-a"), "c-2");

            assertThat(listed.refused()).isTrue();
            assertThat(listed.refusalCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(described.refused()).isTrue();
            assertThat(described.refusalCode()).isEqualTo("SOURCE_NOT_AVAILABLE");
        }
    }

    @Test
    @DisplayName("a correlation id that has a sensitive shape is this server's own value and does not refuse")
    void aShapedCorrelationIdIsNotRefused() {
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false);
        fx.engine.answer = Optional.of(modelDescription());

        CatalogQueries.Outcome listed = fx.queries.listSources(
                new CatalogQueries.ListRequest(null, null, null, null), caller("p", true), context("scope-a"),
                null, "person@example.org");
        CatalogQueries.Outcome described = fx.queries.describeSource("ledger", caller("p", true),
                context("scope-a"), "person@example.org");

        assertThat(listed.refused()).isFalse();
        assertThat(described.refused()).isFalse();
    }

    @Test
    @DisplayName("the 128 cap on a name counts code points: 128 with a supplementary character is served, 129 is refused")
    void nameCapCountsCodePoints() {
        String atCap = "n".repeat(127) + new String(Character.toChars(0x1F600));
        String overCap = "n".repeat(128) + new String(Character.toChars(0x1F600));
        assertThat(atCap.length()).isEqualTo(129);

        CatalogQueries.Outcome at = new Fixture(List.of(atCap), DEFAULT, false).list(null, null);
        CatalogQueries.Outcome over = new Fixture(List.of(overCap), DEFAULT, false).list(null, null);

        assertThat(at.refused()).isFalse();
        assertThat(over.refused()).isTrue();
    }

    @Test
    @DisplayName("an unexpected failure is REQUEST_FAILED, since no validator ran")
    void unexpectedFailureIsRequestFailed() {
        ShapedTokens tokens = new ShapedTokens("-x");
        tokens.throwing = true;
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false, true,
                List.of(new SensitivePatternValidator()), Map.of(), tokens);

        CatalogQueries.Outcome listed = fx.list(new CatalogQueries.ListRequest(null, null, null, null), false);

        assertThat(listed.refused()).isTrue();
        assertThat(listed.refusalCode()).isEqualTo("REQUEST_FAILED");
    }

    @Test
    @DisplayName("a generated description is within the served cap however many entity types are declared")
    void generatedDescriptionStaysWithinTheCap() {
        Set<String> types = new java.util.TreeSet<>();
        IntStream.rangeClosed(1, 30).forEach(i -> types.add(String.format("ENTITY_TYPE_%02d", i)));
        SourceDescription many = new SourceDescription(types, null, null, List.of(new KeyNamespace("parcel-ref")),
                CostHint.LOW, FreshnessHint.DAILY);
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false, true,
                List.of(new SensitivePatternValidator()), Map.of("ledger", many));

        CatalogQueries.Outcome listed = fx.list(null, null);

        assertThat(listed.refused()).isFalse();
        String text = listed.body().get("sources").get(0).get("description").asString();
        assertThat(text.codePointCount(0, text.length())).isBetween(1, 240);
        assertThat(text).startsWith("Holds ENTITY_TYPE_01").contains("more");
    }

    @Test
    @DisplayName("a body the served schema would reject is refused here: a path over 64 characters, a name over 128")
    void aBodyOverTheSchemaCapsIsRefused() {
        Fixture fx = new Fixture(List.of("ledger"), DEFAULT, false);
        fx.engine.answer = Optional.of(new ModelDescription(List.of(
                new FieldProtection("a".repeat(69), Protection.VERBATIM, null, null)),
                ModelDescription.Undeclared.REFUSE));
        Fixture longName = new Fixture(List.of("n".repeat(129)), DEFAULT, false);

        CatalogQueries.Outcome described = fx.queries.describeSource("ledger", caller("p", true),
                context("scope-a"), "c-2");
        CatalogQueries.Outcome listed = longName.list(null, null);

        assertThat(described.refused()).isTrue();
        assertThat(described.refusalCode()).isEqualTo("SOURCE_NOT_AVAILABLE");
        assertThat(described.dispositions()).containsEntry("catalog", "REFUSED:CATALOG_DESCRIPTION_REFUSED");
        assertThat(listed.refused()).isTrue();
        assertThat(listed.refusalCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(listed.body()).isNull();
    }

    @Test
    @DisplayName("a body validation failure is the existing VALIDATION_FAILED code")
    void validationFailureUsesTheExistingCode() {
        assertThat(CatalogQueries.RESPONSE_REFUSED).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("settings are validated and name the property, never the value")
    void pagingSettingsAreValidated() {
        assertThatThrownBy(() -> new PagingSettings(0, 10, Duration.ofMinutes(1)))
                .hasMessageStartingWith("INVALID_PAGING_SETTING: ").hasMessageContaining("page-size");
        assertThatThrownBy(() -> new PagingSettings(11, 10, Duration.ofMinutes(1)))
                .hasMessageStartingWith("INVALID_PAGING_SETTING: ");
        assertThatThrownBy(() -> new PagingSettings(5, 10, Duration.ZERO))
                .hasMessageStartingWith("INVALID_PAGING_SETTING: ").hasMessageContaining("lifetime");
        assertThat(DEFAULT.effective(null)).isEqualTo(25);
        assertThat(DEFAULT.effective(1000)).isEqualTo(100);
        assertThat(DEFAULT.effective(3)).isEqualTo(3);
    }
}
