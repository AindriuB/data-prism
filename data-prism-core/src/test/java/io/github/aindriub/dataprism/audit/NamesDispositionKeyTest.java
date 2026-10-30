package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reserved audit key {@code names} records the effective source-label setting (catalog OD-C2 (a)).
 * It takes {@code REAL} or {@code ALIAS} and nothing else, and no other key gains either value.
 */
class NamesDispositionKeyTest {

    private static AuditEvent event(Map<String, String> dispositions) {
        return new AuditEvent("event-1", Instant.parse("2026-01-01T00:00:00Z"), "investigator-1", "client-1",
                "get_entity_context", "CUSTOMER", "pseudo-1", "fp-1", "DEFAULT", "scope-1", "investigation",
                "CASE-1", "ALLOW", Set.of("customer-api:ANSWERED"), Set.of(), "corr-1", "instance-1", 1L, "", "hash",
                3, dispositions, "", "", "");
    }

    @Test
    @DisplayName("the key is spelled names")
    void keyName() {
        assertThat(AuditEvent.NAMES_KEY).isEqualTo("names");
    }

    @Test
    @DisplayName("names accepts REAL and ALIAS")
    void acceptsBothValues() {
        assertThat(event(Map.of("names", "REAL")).fieldDispositions()).containsEntry("names", "REAL");
        assertThat(event(Map.of("names", "ALIAS")).fieldDispositions()).containsEntry("names", "ALIAS");
    }

    @Test
    @DisplayName("names rejects any other value, including a PrivacyAction, REFUSED and a lower-case spelling")
    void rejectsEveryOtherValue() {
        for (String value : new String[] {"REMOVE", "REFUSED", "REFUSED:SOURCE_NOT_AVAILABLE", "real", "Alias",
                "", "REAL ", "BOTH"}) {
            assertThatThrownBy(() -> event(Map.of("names", value)))
                    .as("value '%s'", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a made-up key is still rejected with REAL or ALIAS")
    void otherKeysDoNotGainTheValues() {
        assertThatThrownBy(() -> event(Map.of("labels", "REAL"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(Map.of("alpha:/name", "ALIAS"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(Map.of("Names", "REAL"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every other key's grammar is unchanged")
    void otherGrammarUnchanged() {
        assertThat(event(Map.of("alpha:/name", "SYNTHESIZE", "alpha:<refused>", "REFUSED:VALIDATION_FAILED",
                "beta:<refused>", "REFUSED", "catalog", "REFUSED:CATALOG_UNKNOWN_SOURCE", "names", "ALIAS"))
                .fieldDispositions()).hasSize(5);
        assertThatThrownBy(() -> event(Map.of("alpha:/name", "NOT_AN_ACTION")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
