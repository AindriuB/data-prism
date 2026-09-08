package io.github.aindriub.dataprism.pseudonymisation.vocabulary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VocabularyRegistryTest {

    private static final VocabularyRegistry BUILT_INS = VocabularyRegistry.withBuiltIns();

    private static NameSet set(String id, String locale, List<String> first, List<String> last) {
        return new NameSet(id, 1, locale, "Latn",
                Map.of(PoolKind.FIRST_NAME, first, PoolKind.LAST_NAME, last));
    }

    @Test
    @DisplayName("every bundled locale is registered and resolvable")
    void bundledLocalesResolve() {
        assertThat(BUILT_INS.locales())
                .contains("und", "en", "mul", "ga", "ar", "zh", "ru");

        for (String locale : BUILT_INS.locales()) {
            Vocabulary vocabulary = BUILT_INS.resolve(locale);
            assertThat(vocabulary.pool(PoolKind.FIRST_NAME)).isNotEmpty();
            assertThat(vocabulary.pool(PoolKind.LAST_NAME)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("a region tag finds its language, and an unknown locale falls back")
    void resolutionFallsBack() {
        assertThat(BUILT_INS.resolve("ga-IE").localeTag()).isEqualTo("ga");
        assertThat(BUILT_INS.resolve("zh-Hans-CN").localeTag()).isEqualTo("zh");
        // Degrades to a working pseudonym rather than failing the request.
        assertThat(BUILT_INS.resolve("xx").localeTag()).isEqualTo("en");
        assertThat(BUILT_INS.resolve(null).localeTag()).isEqualTo("en");
    }

    @Test
    @DisplayName("the vocabulary id changes when the contents do")
    void idIsContentAddressed() {
        var base = set("custom", "test", List.of("Ann", "Bob"), List.of("Stone", "Rivers"));
        var same = set("custom", "test", List.of("Ann", "Bob"), List.of("Stone", "Rivers"));
        var extra = set("custom", "test", List.of("Ann", "Bob", "Cara"), List.of("Stone", "Rivers"));

        String baseId = VocabularyRegistry.builder().add(base).defaultLocale("test").build()
                .resolve("test").id();
        String sameId = VocabularyRegistry.builder().add(same).defaultLocale("test").build()
                .resolve("test").id();
        String extraId = VocabularyRegistry.builder().add(extra).defaultLocale("test").build()
                .resolve("test").id();

        assertThat(sameId).as("identical contents are the same vocabulary").isEqualTo(baseId);
        assertThat(extraId).as("one extra name is a different vocabulary").isNotEqualTo(baseId);
    }

    @Test
    @DisplayName("a replaced locale takes over completely")
    void replaceSubstitutesTheSet() {
        var registry = VocabularyRegistry.builder()
                .addBuiltIns()
                .replace(set("house-style", "en", List.of("Ann"), List.of("Stone")))
                .build();

        assertThat(registry.resolve("en").pool(PoolKind.FIRST_NAME)).containsExactly("Ann");
        assertThat(registry.resolve("ga").pool(PoolKind.FIRST_NAME)).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("an extended locale keeps the bundled entries and adds to them")
    void extendAppends() {
        int before = BUILT_INS.resolve("en").pool(PoolKind.FIRST_NAME).size();

        var registry = VocabularyRegistry.builder()
                .addBuiltIns()
                .extend(set("house-additions", "en", List.of("Zenobia"), List.of("Quillfeather")))
                .build();

        var pool = registry.resolve("en").pool(PoolKind.FIRST_NAME);
        assertThat(pool).hasSize(before + 1).contains("Zenobia");
        // And the id moved, because every index derived from that pool did.
        assertThat(registry.resolve("en").id()).isNotEqualTo(BUILT_INS.resolve("en").id());
    }

    @Test
    @DisplayName("a locale not bundled at all can be registered")
    void newLocaleCanBeAdded() {
        var registry = VocabularyRegistry.builder()
                .addBuiltIns()
                .add(new NameSet("hellenic", 1, "el", "Grek",
                        Map.of(PoolKind.FIRST_NAME, List.of("Γιώργος", "Ελένη"),
                                PoolKind.LAST_NAME, List.of("Παπαδόπουλος", "Νικολάου"))))
                .build();

        assertThat(registry.resolve("el").pool(PoolKind.LAST_NAME)).contains("Νικολάου");
    }

    @Test
    @DisplayName("registering a locale twice is refused rather than guessed at")
    void duplicateLocaleIsRefused() {
        assertThatThrownBy(() -> VocabularyRegistry.builder()
                .addBuiltIns()
                .add(set("second", "en", List.of("Ann"), List.of("Stone")))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replace or extend");
    }

    @Test
    @DisplayName("a set with no names is refused at construction")
    void emptyPoolsAreRefused() {
        assertThatThrownBy(() -> new NameSet("broken", 1, "test", "Latn",
                Map.of(PoolKind.FIRST_NAME, List.of("Ann"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastNames");
    }

    @Test
    @DisplayName("a build whose default locale has no set fails at startup")
    void missingDefaultLocaleFails() {
        assertThatThrownBy(() -> VocabularyRegistry.builder()
                .add(set("only", "ga", List.of("Seán"), List.of("Ó Briain")))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default locale");
    }

    @Test
    @DisplayName("entries are stored canonically, so file encoding does not change the id")
    void entriesAreCanonicalised() {
        var precomposed = set("accents", "test", List.of("Seán"), List.of("Ó Briain"));
        var decomposed = set("accents", "test", List.of("Seán"), List.of("Ó Briain"));

        assertThat(precomposed.pool(PoolKind.FIRST_NAME))
                .isEqualTo(decomposed.pool(PoolKind.FIRST_NAME))
                .containsExactly("Seán");
    }

    @Test
    @DisplayName("a vocabulary file can be loaded from a stream")
    void loadsFromStream() {
        var registry = VocabularyRegistry.builder().addBuiltIns().load(new ByteArrayInputStream("""
                id: house
                version: 3
                locale: en
                script: Latn
                pools:
                  firstNames: [Ann, Bob]
                  lastNames: [Stone, Rivers]
                """.getBytes(StandardCharsets.UTF_8))).build();

        assertThat(registry.resolve("en").pool(PoolKind.FIRST_NAME)).containsExactly("Ann", "Bob");
        assertThat(registry.resolve("en").id()).startsWith("house-v3#");
    }

    @Test
    @DisplayName("no bundled set mixes scripts")
    void bundledSetsAreSingleScript() {
        // A Latin surname in a Han pool reads to a model as a data error, and
        // mixed-script text is where homoglyph confusion lives. This caught a
        // real slip: two stray Latin and Cyrillic entries in the Mandarin
        // organisation pool.
        for (String locale : BUILT_INS.locales()) {
            Vocabulary vocabulary = BUILT_INS.resolve(locale);
            Character.UnicodeScript expected = scriptOf(vocabulary.script());
            if (expected == null) {
                continue;
            }
            for (PoolKind kind : PoolKind.values()) {
                for (String entry : vocabulary.pool(kind)) {
                    entry.codePoints()
                            .filter(Character::isLetter)
                            .forEach(cp -> assertThat(Character.UnicodeScript.of(cp))
                                    .as("%s / %s / '%s' contains U+%04X", locale, kind.key(), entry, cp)
                                    .isEqualTo(expected));
                }
            }
        }
    }

    private static Character.UnicodeScript scriptOf(String iso15924) {
        return switch (iso15924) {
            case "Latn" -> Character.UnicodeScript.LATIN;
            case "Arab" -> Character.UnicodeScript.ARABIC;
            case "Cyrl" -> Character.UnicodeScript.CYRILLIC;
            case "Hans", "Hant", "Hani" -> Character.UnicodeScript.HAN;
            case "Grek" -> Character.UnicodeScript.GREEK;
            default -> null; // Zyyy and anything unmapped is not checked.
        };
    }
}
