package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What validators are shown, and what the final whole-response pass still catches. */
class PerSourceValidationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS = StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Thing(String id, String value) {
    }

    private static DataSourceAdapter<Thing> source(String name, String value) {
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
                return new Thing("1", value);
            }
        };
    }

    private static DefaultContextOrchestrator orchestrator(List<AuditEvent> sink, LlmResponseValidator validator,
                                                            DataSourceAdapter<?>... adapters) {
        // Each source's scrubbed tree is {"value": <its value>} and it emits "synthetic-<value>".
        ScrubbingEngine scrubber = (record, ctx) -> {
            String value = ((Thing) record).value();
            return new ScrubResult(MAPPER.createObjectNode().put("value", value), Set.of("synthetic-" + value));
        };
        var resolver = new DefaultFieldMetadataResolver();
        return new DefaultContextOrchestrator(List.of(adapters), scrubber, resolver, List.of(validator),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(sink::add, CLOCK, "test-1"), new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
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

    private static void keys(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.forEachEntry((k, v) -> {
                out.add(k);
                keys(v, out);
            });
        } else if (node.isArray()) {
            node.forEach(v -> keys(v, out));
        }
    }

    @Test
    @DisplayName("validators see only payload-derived property names: never an alias or a real source name")
    void validatorsNeverSeeASourceNameAsAKey() {
        Set<String> seen = new LinkedHashSet<>();
        LlmResponseValidator recording = (tree, prohibited, emitted, ctx) -> {
            keys(tree, seen);
            return ValidationResult.ok();
        };

        ContextResponse response = orchestrator(new ArrayList<>(), recording, source("alpha-api", "a"),
                source("beta-api", "b")).buildContext(ContextRequest.of("THING", "1"), context(), caller());

        assertThat(seen).isNotEmpty().containsExactly("value");
        assertThat(response.sources().keySet()).doesNotContainAnyElementsOf(seen);
    }

    @Test
    @DisplayName("a violation that only the whole-response pass can see refuses the call as VALIDATION_FAILED at merged:<refused>")
    void finalPassStillRefusesTheWholeCall() {
        // Each source's own emitted set holds one value; only the union holds both.
        LlmResponseValidator needsBoth = (tree, prohibited, emitted, ctx) ->
                emitted.contains("synthetic-a") && emitted.contains("synthetic-b")
                        ? ValidationResult.failed(List.of(new Violation("$.value", "BOTH", "TEST")))
                        : ValidationResult.ok();
        List<AuditEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> orchestrator(events, needsBoth, source("alpha-api", "a"), source("beta-api", "b"))
                .buildContext(ContextRequest.of("THING", "1"), context(), caller()))
                .isInstanceOf(PrivacyRefusedException.class);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.policyDecision()).isEqualTo("DENY:VALIDATION_FAILED");
            assertThat(e.fieldDispositions()).containsEntry("merged:<refused>", "REFUSED");
        });
    }

    @Test
    @DisplayName("a validator that refuses a combination across two sources still refuses it, with no source name in its input")
    void crossSourceCombinationIsStillRefused() {
        // Sees the final pass only: one array of the returned trees. A single tree never holds both values.
        Set<String> seenKeys = new LinkedHashSet<>();
        LlmResponseValidator combination = (tree, prohibited, emitted, ctx) -> {
            keys(tree, seenKeys);
            String text = tree.toString();
            return text.contains("\"a\"") && text.contains("\"b\"")
                    ? ValidationResult.failed(List.of(new Violation("$[0].value", "COMBINATION", "TEST")))
                    : ValidationResult.ok();
        };
        List<AuditEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> orchestrator(events, combination, source("alpha-api", "a"), source("beta-api", "b"))
                .buildContext(ContextRequest.of("THING", "1"), context(), caller()))
                .isInstanceOf(PrivacyRefusedException.class)
                .satisfies(e -> assertThat(((PrivacyRefusedException) e).path())
                        .doesNotContain("alpha-api").doesNotContain("beta-api"));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.policyDecision()).isEqualTo("DENY:VALIDATION_FAILED");
            assertThat(e.fieldDispositions()).containsEntry("merged:<refused>", "REFUSED");
            assertThat(e.fieldDispositions().keySet()).noneMatch(k -> k.contains("alpha-api:<refused>"));
        });
        assertThat(seenKeys).containsExactly("value");
    }
}
