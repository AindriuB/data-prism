package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.orchestration.AuditedRefusalException;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.github.aindriub.dataprism.orchestration.catalog.LabelsAndTargetingTest.Harness;
import static io.github.aindriub.dataprism.orchestration.catalog.LabelsAndTargetingTest.caller;
import static io.github.aindriub.dataprism.orchestration.catalog.LabelsAndTargetingTest.context;
import static io.github.aindriub.dataprism.orchestration.catalog.LabelsAndTargetingTest.hmac;
import static io.github.aindriub.dataprism.orchestration.catalog.LabelsAndTargetingTest.SCOPE;
import static io.github.aindriub.dataprism.orchestration.catalog.LabelsAndTargetingTest.sources;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-RM-9: an unselected call over too large an estate is refused before any adapter or budget is touched. */
class UnselectedSourceLimitTest {

    private static String[] names(int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = "source-" + i;
        }
        return out;
    }

    private static OrchestrationOptions limit(int limit) {
        return OrchestrationOptions.defaults().withUnselectedSourceLimit(limit);
    }

    private static void assertRefusedUntouched(Harness h) {
        assertThatThrownBy(() -> h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE),
                caller(false)))
                .isInstanceOf(AuditedRefusalException.class)
                .satisfies(e -> assertThat(((AuditedRefusalException) e).code())
                        .isEqualTo("SOURCE_SELECTION_REQUIRED"));
        assertThat(h.calledTotal()).isZero();
        assertThat(h.budget.reservations).hasValue(0);
        assertThat(h.last().policyDecision()).isEqualTo("DENY:SOURCE_SELECTION_REQUIRED");
        assertThat(h.last().sourceSystems()).isEmpty();
    }

    @Test
    @DisplayName("the default limit is 8: eight sources are all asked, nine are refused")
    void defaultBoundary() {
        assertThat(OrchestrationOptions.defaults().unselectedSourceLimit()).isEqualTo(8);

        Harness atLimit = new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, hmac(), sources(names(8)));
        ContextResponse response = atLimit.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE),
                caller(false));
        assertThat(response.sources()).hasSize(8);
        assertThat(atLimit.calledTotal()).isEqualTo(8);

        assertRefusedUntouched(new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, hmac(),
                sources(names(9))));
    }

    @Test
    @DisplayName("at the limit every source is asked; one above, the call is refused (>, not >=)")
    void boundary() {
        Harness atLimit = new Harness(limit(3), RequestLimits.DEFAULT, hmac(), sources(names(3)));
        assertThat(atLimit.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE), caller(false))
                .sources()).hasSize(3);
        assertThat(atLimit.calledTotal()).isEqualTo(3);

        assertRefusedUntouched(new Harness(limit(3), RequestLimits.DEFAULT, hmac(), sources(names(4))));
    }

    @Test
    @DisplayName("only permitted sources are counted")
    void countsPermittedOnly() {
        OrchestrationOptions options = limit(3).withVisibility((caller, source) -> !source.equals("source-3"));
        Harness h = new Harness(options, RequestLimits.DEFAULT, hmac(), sources(names(4)));

        ContextResponse response = h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE),
                caller(false));

        assertThat(response.sources()).hasSize(3);
        assertThat(h.called("source-3")).isZero();
    }

    @Test
    @DisplayName("sources serving another entity type are not counted; sources declaring none are")
    void countsServingOnly() {
        Map<String, Set<String>> estate = new LinkedHashMap<>();
        estate.put("source-0", Set.of("THING"));
        estate.put("source-1", Set.of());
        estate.put("source-2", Set.of("OTHER_KIND"));
        estate.put("source-3", Set.of("OTHER_KIND"));
        Harness within = new Harness(limit(2), RequestLimits.DEFAULT, hmac(), estate);

        assertThat(within.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE), caller(false))
                .sources()).hasSize(2);
        assertThat(within.called("source-0")).isEqualTo(1);
        assertThat(within.called("source-1")).isEqualTo(1);
        assertThat(within.called("source-2")).isZero();
        assertThat(within.called("source-3")).isZero();
        assertThat(within.budget.reservations).hasValue(1);

        estate.put("source-4", Set.of());
        assertRefusedUntouched(new Harness(limit(2), RequestLimits.DEFAULT, hmac(), estate));
    }

    @Test
    @DisplayName("eight serving sources plus one of another type are answered, and the other one is not called")
    void eightServingPlusOneOtherIsAnswered() {
        Map<String, Set<String>> estate = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            estate.put("source-" + i, Set.of("THING"));
        }
        estate.put("other-source", Set.of("OTHER_KIND"));
        Harness h = new Harness(OrchestrationOptions.defaults(), RequestLimits.DEFAULT, hmac(), estate);

        ContextResponse response = h.orchestrator.buildContext(ContextRequest.of("THING", "1"), context(SCOPE),
                caller(false));

        assertThat(response.sources()).hasSize(8);
        assertThat(h.called("other-source")).isZero();
        assertThat(h.calledTotal()).isEqualTo(8);
    }

    @Test
    @DisplayName("a targeted call is not subject to the limit, only to maxSources")
    void targetedIgnoresTheLimit() {
        Harness h = new Harness(limit(1), RequestLimits.DEFAULT, hmac(), sources(names(5)));
        var labels = java.util.stream.Stream.of(names(3)).map(n -> h.aliasing.aliasFor(n, context(SCOPE))).toList();

        ContextResponse response = h.orchestrator.buildContext(
                ContextRequest.of("THING", "1").withTargetSources(labels), context(SCOPE), caller(false));

        assertThat(response.sources()).hasSize(3);
    }

    @Test
    @DisplayName("a non-positive limit is refused when the options are built")
    void positiveLimit() {
        assertThatThrownBy(() -> limit(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_CATALOG_SETTING:");
        assertThatThrownBy(() -> limit(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
