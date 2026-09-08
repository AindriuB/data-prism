package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSyntheticGeneratorTest {

    private static final String GOLDEN_KEY = "golden-vector-key-for-tests-only-v1";

    private final HmacSyntheticGenerator generator =
            new HmacSyntheticGenerator(StaticSecretKeyProvider.of(GOLDEN_KEY));

    private static PrivacyContext scope(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    @Test
    @DisplayName("the same subject in the same scope always resolves to the same value")
    void sameSubjectProducesSameValue() {
        String first = generator.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));
        String second = generator.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("different scopes never share a synthetic identity")
    void differentScopesDiverge() {
        String caseA = generator.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));
        String caseB = generator.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-B"));

        // Asserting inequality rather than a particular value: the property is
        // that investigations cannot be correlated, not that any given case
        // produces any given name.
        assertThat(caseB).isNotEqualTo(caseA);
    }

    @Test
    @DisplayName("different namespaces of one subject do not collide")
    void namespacesDiverge() {
        PrivacyContext scope = scope("CASE-A");

        assertThat(generator.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope))
                .isNotEqualTo(generator.syntheticValue("123", PrivacyNamespace.ORGANISATION_NAME, scope));
    }

    @Test
    @DisplayName("a different key changes every value, so key rotation is total")
    void differentKeyDiverges() {
        var other = new HmacSyntheticGenerator(StaticSecretKeyProvider.of("a-completely-different-key-value-x"));

        assertThat(other.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A")))
                .isNotEqualTo(generator.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
    }

    @Test
    @DisplayName("golden vectors for v1 still hold")
    void goldenVectorsHold() throws Exception {
        List<String[]> vectors = readVectors();
        assertThat(vectors).as("golden vector file must not be empty").isNotEmpty();

        for (String[] v : vectors) {
            String actual = generator.syntheticValue(v[1], PrivacyNamespace.valueOf(v[2]), scope(v[0]));
            assertThat(actual)
                    .as("v1 vector %s/%s/%s changed — see the header of golden-vectors-v1.tsv", v[0], v[1], v[2])
                    .isEqualTo(v[3]);
        }
    }

    @Test
    @DisplayName("50k subjects in one scope produce no colliding person name")
    void discriminatorAvoidsCollisions() {
        PrivacyContext scope = scope("CASE-COLLISION");
        Set<String> seen = new HashSet<>();
        Map<String, String> firstUse = new HashMap<>();

        for (int i = 0; i < 50_000; i++) {
            String subject = "subject-" + i;
            String value = generator.syntheticValue(subject, PrivacyNamespace.PERSON_NAME, scope);
            if (!seen.add(value)) {
                // A collision means two real people read as one person to the
                // model, which is a false statement rather than a leak — and so
                // much harder to notice downstream.
                throw new AssertionError("collision between " + firstUse.get(value) + " and " + subject);
            }
            firstUse.put(value, subject);
        }
        assertThat(seen).hasSize(50_000);
    }

    @Test
    @DisplayName("a name pool alone would have collided at this volume")
    void collisionTestIsNotVacuous() {
        // Without the discriminator the space is 32 x 32 = 1024 pairs, so 50k
        // subjects must collide. This proves the previous test is measuring the
        // discriminator rather than a pool that was large enough anyway.
        PrivacyContext scope = scope("CASE-COLLISION");
        Set<String> withoutDiscriminator = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            String value = generator.syntheticValue("subject-" + i, PrivacyNamespace.PERSON_NAME, scope);
            withoutDiscriminator.add(value.substring(0, value.indexOf('(')));
        }
        assertThat(withoutDiscriminator).hasSizeLessThan(50_000);
    }

    private static List<String[]> readVectors() throws Exception {
        try (InputStream in = HmacSyntheticGeneratorTest.class
                .getResourceAsStream("/golden-vectors-v1.tsv");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t"))
                    .toList();
        }
    }
}
