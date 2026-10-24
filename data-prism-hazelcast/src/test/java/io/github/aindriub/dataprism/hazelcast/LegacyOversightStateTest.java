package io.github.aindriub.dataprism.hazelcast;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyOversightStateTest {

    private PrivacyCluster[] members;

    @BeforeEach
    void start() {
        members = TestMembers.pair(TestMembers.uniqueName("legacy"), OptionalInt.empty());
    }

    @AfterEach
    void stop() {
        for (PrivacyCluster member : members) {
            member.close();
        }
    }

    @Test
    void aZeroPointSevenScopePauseRefusesStartup() {
        members[0].instance().getMap(PrivacyCluster.OVERSIGHT_MAP).put("CASE-A\0paused", Boolean.TRUE);

        assertThatThrownBy(() -> new HazelcastOversightState(members[1]))
                .isInstanceOf(LegacyOversightStateException.class)
                .hasMessageContaining("full cluster restart")
                .extracting(e -> ((LegacyOversightStateException) e).code())
                .isEqualTo("LEGACY_OVERSIGHT_STATE");
    }

    @Test
    void withoutLegacyEntriesStartupIsNormal() {
        var state = new HazelcastOversightState(members[0]);
        state.pauseAll();
        state.pauseTool("search");
        state.pauseScope("CASE-A");

        var restarted = new HazelcastOversightState(members[1]);

        assertThat(restarted.allPaused()).isTrue();
        assertThat(restarted.scopePaused("CASE-A")).isTrue();
    }

    @Test
    void aLegacyPauseWrittenAfterConstructionPausesThatScopeOnly() {
        var state = new HazelcastOversightState(members[0]);
        assertThat(state.scopePaused("CASE-A")).isFalse();

        members[1].instance().getMap(PrivacyCluster.OVERSIGHT_MAP).put("CASE-A\0paused", Boolean.TRUE);

        assertThat(state.scopePaused("CASE-A")).isTrue();
        assertThat(state.scopePaused("CASE-B")).isFalse();
        assertThat(state.snapshot().pausedScopes()).containsExactly("CASE-A");
    }
}
