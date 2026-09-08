package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiKeySecretKeyProviderTest {

    // Obviously synthetic, and long enough to clear the 32-character minimum.
    private static final String RETIRED_KEY = "retired-test-key-not-real-material-2029";
    private static final String CURRENT_KEY = "current-test-key-not-real-material-2030";

    private static final Vocabulary VOCABULARY = VocabularyRegistry.withBuiltIns().resolve("und");

    private final MultiKeySecretKeyProvider provider = new MultiKeySecretKeyProvider()
            .add("retired", RETIRED_KEY)
            .add("current", CURRENT_KEY);

    private static PrivacyContext scope(String scopeId, String keyId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1
                        .withVocabulary(VOCABULARY.id())
                        .withKey(keyId));
    }

    @Test
    @DisplayName("each registered key id resolves to its own key")
    void resolvesEachKeyById() {
        assertThat(provider.secret("retired")).isEqualTo(RETIRED_KEY.getBytes(StandardCharsets.UTF_8));
        assertThat(provider.secret("current")).isEqualTo(CURRENT_KEY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("two scopes pinned to different key ids resolve through one generator, each stably")
    void twoScopesOnDifferentKeysResolveSimultaneously() {
        var generator = new HmacSyntheticGenerator(provider, VOCABULARY);
        PrivacyContext onRetired = scope("CASE-A", "retired");
        PrivacyContext onCurrent = scope("CASE-A", "current");

        String retired = generator.syntheticValue("subject-1", PrivacyNamespace.PERSON_NAME, onRetired);
        String current = generator.syntheticValue("subject-1", PrivacyNamespace.PERSON_NAME, onCurrent);

        // Interleaved deliberately: a provider that latched onto the key of the
        // last scope it served would pass a sequential check and fail this one.
        assertThat(generator.syntheticValue("subject-1", PrivacyNamespace.PERSON_NAME, onRetired))
                .isEqualTo(retired);
        assertThat(generator.syntheticValue("subject-1", PrivacyNamespace.PERSON_NAME, onCurrent))
                .isEqualTo(current);
        assertThat(current).isNotEqualTo(retired);
    }

    @Test
    @DisplayName("registering a new key does not retire the key a scope is already pinned to")
    void addingAKeyDoesNotRetireAnother() {
        provider.add("next", "next-test-key-not-real-material-2031");

        assertThat(provider.keyIds()).containsExactly("current", "next", "retired");
        assertThat(provider.secret("retired")).isEqualTo(RETIRED_KEY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a key is retired only when it is removed explicitly")
    void removalRetiresAKey() {
        assertThat(provider.remove("retired")).isTrue();

        assertThatThrownBy(() -> provider.secret("retired"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(provider.keyIds()).containsExactly("current");
    }

    @Test
    @DisplayName("an unknown key id throws and names the id rather than falling back")
    void unknownKeyIdThrowsNamingTheId() {
        assertThatThrownBy(() -> provider.secret("never-registered"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-registered");
    }

    @Test
    @DisplayName("an unknown key id throws even when exactly one key could have been substituted")
    void singleKeyProviderStillRefusesAnUnknownId() {
        // The fallback that would be tempting to write is "if there is only one
        // key, use it". It would change every synthetic value in the scope while
        // nothing appeared to fail, so prove the single-key case refuses too.
        var single = new MultiKeySecretKeyProvider(
                Map.of("current", CURRENT_KEY.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> single.secret("retired"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
    }

    @Test
    @DisplayName("no key material appears in toString or in a failure message")
    void keyMaterialNeverEscapes() {
        // A record would put every key into toString by default, and this is the
        // assertion that catches someone making one.
        assertThat(provider.toString())
                .doesNotContain(RETIRED_KEY)
                .doesNotContain(CURRENT_KEY)
                .contains("retired", "current");

        assertThatThrownBy(() -> provider.secret("never-registered"))
                .hasMessageNotContaining(RETIRED_KEY)
                .hasMessageNotContaining(CURRENT_KEY);
        assertThatThrownBy(() -> provider.add("too-short", "short-key"))
                .hasMessageNotContaining("short-key");
    }

    @Test
    @DisplayName("a key shorter than 32 characters is rejected when it is registered")
    void shortKeysAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new MultiKeySecretKeyProvider().add("weak", "31-characters-is-one-too-few---"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weak")
                .hasMessageContaining("32");

        assertThat(new MultiKeySecretKeyProvider().add("ok", "32-characters-is-exactly-enough-")
                .keyIds()).containsExactly("ok");
    }

    @Test
    @DisplayName("both providers enforce the same minimum key length")
    void minimumKeyLengthMatchesTheEnvironmentProvider() {
        // The two minimums are separate constants; this is what stops them
        // drifting apart and letting a key be strong enough for one provider and
        // too weak for the other.
        String tooShort = "31-characters-is-one-too-few---";
        var environment = new EnvironmentSecretKeyProvider(
                name -> "DATA_PRISM_HMAC_KEY_WEAK".equals(name) ? tooShort : null);

        assertThatThrownBy(() -> environment.secret("weak")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MultiKeySecretKeyProvider().add("weak", tooShort))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the key handed to a caller is a copy, so a caller cannot mutate the provider's")
    void secretIsDefensivelyCopied() {
        byte[] handedOut = provider.secret("current");
        handedOut[0] = 0;

        assertThat(provider.secret("current")).isEqualTo(CURRENT_KEY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a blank key id is rejected rather than registered")
    void blankKeyIdIsRejected() {
        assertThatThrownBy(() -> new MultiKeySecretKeyProvider().add("  ", CURRENT_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("EnvironmentSecretKeyProvider")
    class Environment {

        private final EnvironmentSecretKeyProvider environment = new EnvironmentSecretKeyProvider(
                Map.of("DATA_PRISM_HMAC_KEY_RETIRED", RETIRED_KEY,
                        "DATA_PRISM_HMAC_KEY_CURRENT", CURRENT_KEY)::get);

        @Test
        @DisplayName("several key ids resolve side by side during a rotation")
        void resolvesSeveralKeyIds() {
            assertThat(environment.secret("retired"))
                    .isEqualTo(RETIRED_KEY.getBytes(StandardCharsets.UTF_8));
            assertThat(environment.secret("current"))
                    .isEqualTo(CURRENT_KEY.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("an unset key id throws naming the variable rather than falling back")
        void unsetKeyIdThrows() {
            assertThatThrownBy(() -> environment.secret("next"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DATA_PRISM_HMAC_KEY_NEXT")
                    .hasMessageNotContaining(CURRENT_KEY);
        }

        @Test
        @DisplayName("a blank key id is refused rather than read from the bare prefix")
        void blankKeyIdIsRefused() {
            assertThatThrownBy(() -> environment.secret(" "))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
