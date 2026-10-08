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

    private static final String OTHER_POOLS =
            "  lastNames: [l]\n  streets: [s]\n  towns: [t]\n  organisations: [o]\n";
    private static final String VOCABULARY = "id: probe\nlocale: en\nscript: Latn\npools:\n  firstNames: [a]\n" + OTHER_POOLS;

    private static VocabularyRegistry.Builder load(String yaml) {
        return VocabularyRegistry.builder().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertRefused(String yaml, String prefix) {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(prefix);
    }

    @Test
    @DisplayName("a duplicate top-level key is refused with DUPLICATE_CONFIG_KEY")
    void duplicateTopLevelKey() {
        assertRefused("id: first\n" + VOCABULARY, "DUPLICATE_CONFIG_KEY: vocabulary file has a duplicate key 'id'");
    }

    @Test
    @DisplayName("a duplicate pool is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicatePool() {
        assertRefused(VOCABULARY + "  firstNames: [b]\n",
                "DUPLICATE_CONFIG_KEY: vocabulary file has a duplicate key 'firstNames' in pools");
    }

    @Test
    @DisplayName("an unknown top-level key is refused with UNKNOWN_CONFIG_KEY")
    void unknownTopLevelKey() {
        assertRefused("extra: 1\n" + VOCABULARY, "UNKNOWN_CONFIG_KEY: vocabulary file has an unknown key 'extra'");
    }

    @Test
    @DisplayName("an unknown pool name is refused with UNKNOWN_CONFIG_KEY, naming the path")
    void unknownPool() {
        assertRefused(VOCABULARY + "  firstName: [x]\n",
                "UNKNOWN_CONFIG_KEY: vocabulary file pools has an unknown key 'firstName'");
    }

    @Test
    @DisplayName("a second YAML document is refused with TRAILING_CONFIG_CONTENT")
    void secondDocument() {
        assertRefused(VOCABULARY + "---\n" + VOCABULARY, "TRAILING_CONFIG_CONTENT: vocabulary file ");
    }

    @Test
    @DisplayName("a pool entry, id, locale or script written as a number or boolean is refused with NON_STRING_CONFIG_SCALAR")
    void textFieldsMustBeStrings() {
        assertRefused(VOCABULARY.replace("[a]", "[a, 12345]"),
                "NON_STRING_CONFIG_SCALAR: vocabulary file pools.firstNames[1] must be a quoted string");
        assertRefused(VOCABULARY.replace("id: probe", "id: true"),
                "NON_STRING_CONFIG_SCALAR: vocabulary file id must be a quoted string");
        assertRefused(VOCABULARY.replace("locale: en", "locale: 1"),
                "NON_STRING_CONFIG_SCALAR: vocabulary file locale must be a quoted string");
        assertRefused(VOCABULARY.replace("script: Latn", "script: 1.5"),
                "NON_STRING_CONFIG_SCALAR: vocabulary file script must be a quoted string");
        assertThat(load(VOCABULARY.replace("[a]", "[\"12345\", 010]")).defaultLocale("en").build()
                .resolve("en").pool(PoolKind.FIRST_NAME)).containsExactly("12345", "010");
    }

    @Test
    @DisplayName("a version written with a leading zero is refused with LEADING_ZERO_CONFIG_NUMBER; a plain one loads")
    void versionLeadingZero() {
        for (String version : new String[] {"010", "0777", "00"}) {
            assertRefused("version: " + version + "\n" + VOCABULARY,
                    "LEADING_ZERO_CONFIG_NUMBER: vocabulary file version must not be written with a leading zero");
        }
        assertThat(load("version: 2\n" + VOCABULARY).defaultLocale("en").build().resolve("en").id())
                .startsWith("probe-v2#");
    }

    @Test
    @DisplayName("pools that are not a mapping are refused with INVALID_CONFIG_SHAPE")
    void wrongShapedPools() {
        assertRefused("id: probe\nlocale: en\npools: [a]\n", "INVALID_CONFIG_SHAPE: vocabulary file.pools must be a mapping");
        assertRefused("id: probe\nlocale: en\npools:\n", "INVALID_CONFIG_SHAPE: vocabulary file.pools must be a mapping");
    }

    @Test
    @DisplayName("a pool that is not a list is refused with INVALID_CONFIG_SHAPE, not dropped")
    void wrongShapedPool() {
        assertRefused(VOCABULARY.replace("[a]", "alice"),
                "INVALID_CONFIG_SHAPE: vocabulary file pools.firstNames must be a list");
        assertRefused(VOCABULARY.replace("firstNames: [a]", "firstNames:"),
                "INVALID_CONFIG_SHAPE: vocabulary file pools.firstNames must be a list");
    }

    @Test
    @DisplayName("an alias is refused with UNSUPPORTED_CONFIG_YAML")
    void aliasRefused() {
        assertRefused(VOCABULARY.replace("firstNames: [a]", "firstNames: &n [a]").replace("lastNames: [l]", "lastNames: *n"),
                "UNSUPPORTED_CONFIG_YAML: ");
    }
}
