package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyProfilesTest {

    private static InputStream yaml(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the profiles shipped with the library load")
    void shippedProfilesLoad() throws Exception {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            Map<String, PrivacyProfile> profiles = PrivacyProfiles.fromYaml(in);

            assertThat(profiles).containsKeys("DEFAULT", "STRICT");
            assertThat(profiles.get("DEFAULT").classifications())
                    .containsEntry(DataClassification.PII,
                            PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE));
            // Every shipped profile refuses rather than exposing an unclassified
            // field. There is no setting that would let one through.
            assertThat(profiles.values()).allSatisfy(p ->
                    assertThat(p.unclassified())
                            .isEqualTo(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST));
        }
    }

    @Test
    @DisplayName("override is read, and defaults to false")
    void readsOverride() {
        var profiles = PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    unclassified: REDACT_AND_WARN
                    classifications:
                      PII: { action: SYNTHESIZE, override: true }
                      CONTACT: { action: REDACT }
                """));

        var rules = profiles.get("P").classifications();
        assertThat(rules.get(DataClassification.PII).override()).isTrue();
        assertThat(rules.get(DataClassification.CONTACT).override()).isFalse();
    }

    @Test
    @DisplayName("a misspelled classification fails at load, naming the key")
    void unknownClassificationFails() {
        // The alternative is a rule that silently does not apply, which looks
        // exactly like a rule that does.
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PIII: { action: REDACT }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PIII");
    }

    @Test
    @DisplayName("a misspelled action fails at load")
    void unknownActionFails() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PII: { action: REDAKT }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDAKT");
    }

    @Test
    @DisplayName("a rule with no action fails rather than defaulting")
    void missingActionFails() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PII: { override: true }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no action");
    }

    @Test
    @DisplayName("a file with no profiles section fails")
    void emptyFileFails() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("something: else\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profiles");
    }

    @Test
    @DisplayName("unclassified defaults to failing the request when not stated")
    void unclassifiedDefaultsToFailClosed() {
        var profiles = PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PII: { action: REDACT }
                """));

        assertThat(profiles.get("P").unclassified())
                .isEqualTo(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST);
    }
}
