package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile.UnclassifiedBehaviour;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A root leaf is described as a scalar, so a structure arriving there refuses, as it does in a nested
 * catalogue, instead of taking the unclassified-structure path that scrubs a field described verbatim
 * (task 249 review round 1).
 */
class ConfiguredJsonRootLeafShapeTest {

    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-249-root-leaf-test-key-not-for-any-real-data-32bytes");

    private static ConfiguredJsonSource task20Source() throws IOException {
        try (var in = ConfiguredJsonRootLeafShapeTest.class.getResourceAsStream("/task20-json-sources.yaml")) {
            return ConfiguredJsonSources.fromYaml(in).sources().get("customer-api");
        }
    }

    private static ConfiguredJsonScrubbingEngine engine(ConfiguredJsonSource source, UnclassifiedBehaviour behaviour,
                                                         Vocabulary vocabulary) {
        var policy = new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", new PrivacyProfile("DEFAULT", behaviour, Map.of())));
        return new ConfiguredJsonScrubbingEngine((o, c) -> {
            throw new AssertionError("java-first delegate must not be reached");
        }, Map.of("customer-api", source), policy, new HmacSyntheticGenerator(KEYS, vocabulary),
                new HmacValueTokenSource(KEYS));
    }

    @Test
    @DisplayName("a structure at a root leaf refuses with NESTED_LEAF_NOT_SCALAR under both warning profiles")
    void rootLeafHoldingAnObjectRefuses() throws IOException {
        ConfiguredJsonSource source = task20Source();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        PrivacyContext context = new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT",
                "investigation", Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));
        ObjectNode body = (ObjectNode) JsonMapper.builder().build()
                .readTree("{\"id\":\"C1\",\"status\":{\"code\":\"ACTIVE\"}}");

        for (UnclassifiedBehaviour behaviour
                : new UnclassifiedBehaviour[] {UnclassifiedBehaviour.REDACT_AND_WARN, UnclassifiedBehaviour.DROP_AND_WARN}) {
            assertThatThrownBy(() -> engine(source, behaviour, vocabulary)
                    .scrub(new ConfiguredJsonPayload("customer-api", body.deepCopy()), context))
                    .as(behaviour.name())
                    .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                        assertThat(e.code()).isEqualTo("NESTED_LEAF_NOT_SCALAR");
                        assertThat(e.path()).isEqualTo("customer-api$.status");
                        assertThat(e.getMessage()).doesNotContain("ACTIVE");
                    });
        }
    }
}
