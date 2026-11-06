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

import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final String SOURCE_WITH_REMOVED_FIELD = """
            json-sources:
              customer-api:
                base-url: https://127.0.0.1:1
                path: /customers/{subject}
                timeout: PT2S
                model-version: customer-v1
                subject-json-path: id
                fields:
                  id:
                    identifier: true
                  secret:
                    classifications: [CREDENTIAL]
                    action: REDACT
                  status:
                    nonSensitive: "enumerated lifecycle state"
                  tags:
                    nonSensitive: "list of labels"
            """;

    private static ConfiguredJsonSource removedFieldSource() {
        return ConfiguredJsonSources.fromYaml(
                new ByteArrayInputStream(SOURCE_WITH_REMOVED_FIELD.getBytes(StandardCharsets.UTF_8)))
                .sources().get("customer-api");
    }

    private static ConfiguredJsonScrubbingEngine realProfilesEngine(ConfiguredJsonSource source, Vocabulary vocabulary)
            throws IOException {
        Map<String, PrivacyProfile> profiles;
        try (var in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            profiles = PrivacyProfiles.fromYaml(in);
        }
        return new ConfiguredJsonScrubbingEngine((o, c) -> {
            throw new AssertionError("java-first delegate must not be reached");
        }, Map.of("customer-api", source), new ProfilePrivacyPolicyResolver(profiles),
                new HmacSyntheticGenerator(KEYS, vocabulary), new HmacValueTokenSource(KEYS));
    }

    private static PrivacyContext contextFor(String profile, Vocabulary vocabulary) {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, profile,
                "investigation", Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));
    }

    @Test
    @DisplayName("a structure at a root leaf the profile removes is dropped, not refused, under DEFAULT and STRICT")
    void structureAtRemovedRootLeafIsDropped() throws IOException {
        ConfiguredJsonSource source = removedFieldSource();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        var engine = realProfilesEngine(source, vocabulary);
        for (String profile : new String[] {"DEFAULT", "STRICT"}) {
            for (String json : new String[] {"{\"id\":\"C1\",\"secret\":{\"token\":\"t\"}}",
                    "{\"id\":\"C1\",\"secret\":[{\"token\":\"t\"}]}"}) {
                ObjectNode body = (ObjectNode) JsonMapper.builder().build().readTree(json);
                var result = engine.scrub(new ConfiguredJsonPayload("customer-api", body),
                        contextFor(profile, vocabulary));
                assertThat(result.tree().has("secret")).as(profile + " " + json).isFalse();
                assertThat(result.tree().toString()).doesNotContain("\"t\"");
            }
        }
    }

    @Test
    @DisplayName("a structure at a root leaf the profile does not remove still refuses under real profiles")
    void structureAtKeptRootLeafStillRefuses() throws IOException {
        ConfiguredJsonSource source = removedFieldSource();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        var engine = realProfilesEngine(source, vocabulary);
        ObjectNode body = (ObjectNode) JsonMapper.builder().build()
                .readTree("{\"id\":\"C1\",\"status\":{\"code\":\"ACTIVE\"}}");
        assertThatThrownBy(() -> engine.scrub(new ConfiguredJsonPayload("customer-api", body),
                contextFor("DEFAULT", vocabulary)))
                .isInstanceOfSatisfying(PrivacyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("NESTED_LEAF_NOT_SCALAR"));
    }

    @Test
    @DisplayName("an array of scalars at a root leaf passes")
    void arrayOfScalarsAtRootLeafPasses() throws IOException {
        ConfiguredJsonSource source = removedFieldSource();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        var engine = realProfilesEngine(source, vocabulary);
        ObjectNode body = (ObjectNode) JsonMapper.builder().build()
                .readTree("{\"id\":\"C1\",\"tags\":[\"a\",\"b\"]}");
        var result = engine.scrub(new ConfiguredJsonPayload("customer-api", body), contextFor("DEFAULT", vocabulary));
        assertThat(result.tree().get("tags").size()).isEqualTo(2);
    }
}
