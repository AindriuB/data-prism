package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.PoolKind;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacSyntheticGeneratorTest {

    private static final String GOLDEN_KEY = "golden-vector-key-for-tests-only-v1";
    private static final VocabularyRegistry REGISTRY = VocabularyRegistry.withBuiltIns();

    /** The frozen S0 pool. Every v1 vector was issued against it. */
    private static final Vocabulary GENERIC = REGISTRY.resolve("und");
    private static final Vocabulary WESTERN = REGISTRY.resolve("en");

    private final HmacSyntheticGenerator generator = generatorFor(GENERIC);

    private static HmacSyntheticGenerator generatorFor(Vocabulary vocabulary) {
        return new HmacSyntheticGenerator(StaticSecretKeyProvider.of(GOLDEN_KEY), vocabulary);
    }

    private static PrivacyContext scope(String scopeId, Vocabulary vocabulary) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withVocabulary(vocabulary.id()));
    }

    private static PrivacyContext scope(String scopeId) {
        return scope(scopeId, GENERIC);
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
        var other = new HmacSyntheticGenerator(
                StaticSecretKeyProvider.of("a-completely-different-key-value-x"), GENERIC);

        assertThat(other.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A")))
                .isNotEqualTo(generator.syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
    }

    @Test
    @DisplayName("v1 golden vectors still hold against the frozen pool")
    void goldenVectorsHold() throws Exception {
        List<String[]> vectors = readVectors("/golden-vectors-v1.tsv");
        assertThat(vectors).as("golden vector file must not be empty").isNotEmpty();

        for (String[] v : vectors) {
            String actual = generator.syntheticValue(v[1], PrivacyNamespace.valueOf(v[2]), scope(v[0]));
            assertThat(actual)
                    .as("v1 vector %s/%s/%s changed — see the header of golden-vectors-v1.tsv", v[0], v[1], v[2])
                    .isEqualTo(v[3]);
        }
    }

    @Test
    @DisplayName("the widened default pool has its own vectors")
    void westernVectorsHold() throws Exception {
        var westernGenerator = generatorFor(WESTERN);
        for (String[] v : readVectors("/golden-vectors-western-v2.tsv")) {
            assertThat(westernGenerator.syntheticValue(
                    v[1], PrivacyNamespace.valueOf(v[2]), scope(v[0], WESTERN)))
                    .isEqualTo(v[3]);
        }
    }

    @Test
    @DisplayName("widening the pool changed the pseudonyms, which is why pools are pinned")
    void wideningThePoolChangesEverything() {
        // The point of the frozen generic set. Both vector files pass, and they
        // disagree with each other on the same subject — so a scope that drifted
        // from one pool to the other would silently rename everyone.
        assertThat(generatorFor(WESTERN)
                .syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A", WESTERN)))
                .isNotEqualTo(generator
                        .syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
    }

    @Test
    @DisplayName("a scope pinned to another vocabulary is refused, not silently served")
    void refusesVocabularyMismatch() {
        assertThatThrownBy(() -> generator.syntheticValue(
                "123", PrivacyNamespace.PERSON_NAME, scope("CASE-A", WESTERN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned to vocabulary");
    }

    @Test
    @DisplayName("the same identifier in two Unicode forms is one subject, not two")
    void normalisationDoesNotSplitASubject() {
        // "Seán" precomposed, and the same name with a combining acute. Two
        // source systems can easily disagree about which they store; without
        // canonicalisation this person would get two different pseudonyms and
        // read as two people.
        String precomposed = "Seán-1";
        String decomposed = "Seán-1";
        assertThat(precomposed).isNotEqualTo(decomposed);

        assertThat(generator.syntheticValue(decomposed, PrivacyNamespace.PERSON_NAME, scope("CASE-A")))
                .isEqualTo(generator.syntheticValue(precomposed, PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
    }

    @Test
    @DisplayName("invisible bidi and zero-width characters do not split a subject either")
    void invisibleCharactersDoNotSplitASubject() {
        String plain = "عبدالله-9";
        String withMarks = "‎عبدالله‏-9";

        assertThat(generator.syntheticValue(withMarks, PrivacyNamespace.PERSON_NAME, scope("CASE-A")))
                .isEqualTo(generator.syntheticValue(plain, PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
    }

    @Test
    @DisplayName("every bundled locale renders in its own script")
    void localesRenderInTheirOwnScript() {
        Map<String, String> sample = new HashMap<>();
        for (String locale : List.of("en", "mul", "ga", "ar", "zh", "ru")) {
            Vocabulary vocabulary = REGISTRY.resolve(locale);
            String name = generatorFor(vocabulary)
                    .syntheticValue("123", PrivacyNamespace.PERSON_NAME, scope("CASE-A", vocabulary));

            assertThat(name).as("%s produced a name", locale).isNotBlank();
            sample.put(locale, name);
        }

        // Each script produces something distinct; nothing has silently fallen
        // back to the default pool.
        assertThat(Set.copyOf(sample.values())).hasSize(sample.size());
        assertThat(sample.get("zh")).as("Han names are family-first and unspaced")
                .matches("^\\p{IsHan}+ \\([0-9A-Z]{4}\\)$");
        assertThat(sample.get("ru")).matches("^\\p{IsCyrillic}+ \\p{IsCyrillic}+ \\([0-9A-Z]{4}\\)$");
        assertThat(sample.get("ar")).matches("^\\p{IsArabic}+ \\p{IsArabic}+ \\([0-9A-Z]{4}\\)$");
    }

    @Test
    @DisplayName("accented characters survive the round trip intact")
    void diacriticsSurvive() {
        Vocabulary irish = REGISTRY.resolve("ga");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            names.add(generatorFor(irish).syntheticValue(
                    "subject-" + i, PrivacyNamespace.PERSON_NAME, scope("CASE-A", irish)));
        }

        // If anything in the path were not UTF-8 clean this would show up as
        // replacement characters rather than fadas.
        assertThat(names).anySatisfy(n -> assertThat(n).containsAnyOf("á", "é", "í", "ó", "ú"));
        assertThat(names).allSatisfy(n -> assertThat(n).doesNotContain("�").doesNotContain("?"));
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
        // Without the discriminator the space is the product of the two pools, so
        // 50k subjects must collide. This proves the previous test is measuring
        // the discriminator rather than a pool that was large enough anyway.
        PrivacyContext scope = scope("CASE-COLLISION");
        Set<String> withoutDiscriminator = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            String value = generator.syntheticValue("subject-" + i, PrivacyNamespace.PERSON_NAME, scope);
            withoutDiscriminator.add(value.substring(0, value.indexOf('(')));
        }
        assertThat(withoutDiscriminator).hasSizeLessThan(50_000);

        int expected = GENERIC.pool(PoolKind.FIRST_NAME).size() * GENERIC.pool(PoolKind.LAST_NAME).size();
        assertThat(withoutDiscriminator).hasSizeLessThanOrEqualTo(expected);
    }

    private static List<String[]> readVectors(String resource) throws Exception {
        try (InputStream in = HmacSyntheticGeneratorTest.class.getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t"))
                    .toList();
        }
    }
}
