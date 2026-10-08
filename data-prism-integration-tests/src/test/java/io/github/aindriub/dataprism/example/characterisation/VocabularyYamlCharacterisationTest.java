package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.pseudonymisation.vocabulary.PoolKind;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code VocabularyRegistry.builder().load(...)} does today (Jackson 3 YAML, YAML 1.2 reading rules; decision D-167-1 accepted
 * the change from Jackson 2 and YAML 1.1). The expected text is what the current code printed. The id is shown
 * up to the {@code #} (the part after it is a content digest and moves with every value).
 */
class VocabularyYamlCharacterisationTest {

    private static String render(InputStream in) {
        Vocabulary v = VocabularyRegistry.builder().load(in).defaultLocale("en").build().resolve("en");
        String id = v.id();
        return "id=" + id.substring(0, id.indexOf('#')) + " locale=" + v.localeTag() + " script=" + v.script()
                + " firstNames=" + v.pool(PoolKind.FIRST_NAME);
    }

    private static String outcome(String yaml) {
        return Observe.outcome(() -> render(Observe.yaml(yaml)));
    }

    /** The pools a name set must have besides the one under test. */
    private static final String OTHER_POOLS =
            "  lastNames: [l]\n  streets: [s]\n  towns: [t]\n  organisations: [o]\n";

    private static String vocabulary(String extra) {
        return "id: probe\nlocale: en\nscript: Latn\n" + extra;

    }

    @Test
    @DisplayName("duplicate key: refused, IllegalArgumentException \"DUPLICATE_CONFIG_KEY: ...\" (id given twice)")
    void duplicateKey() {
        assertThat(outcome("id: first\nid: second\nlocale: en\npools:\n  firstNames: [a]\n" + OTHER_POOLS))
                .isEqualTo("refused IllegalArgumentException \"DUPLICATE_CONFIG_KEY: vocabulary file has a duplicate key 'id'\"");
    }

    @Test
    @DisplayName("yes, no, on and off are read as text, not booleans (YAML 1.2); only true and false, in any case, are booleans")
    void booleanSpellings() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS,
                s -> vocabulary("pools:\n  firstNames: [" + s + "]\n" + OTHER_POOLS),
                VocabularyYamlCharacterisationTest::render)).isEqualTo("yes => ok id=probe-v1 locale=en script=Latn firstNames=[yes]\n"
                + "no => ok id=probe-v1 locale=en script=Latn firstNames=[no]\n"
                + "on => ok id=probe-v1 locale=en script=Latn firstNames=[on]\n"
                + "off => ok id=probe-v1 locale=en script=Latn firstNames=[off]\n"
                + "y => ok id=probe-v1 locale=en script=Latn firstNames=[y]\n"
                + "n => ok id=probe-v1 locale=en script=Latn firstNames=[n]\n"
                + "True => ok id=probe-v1 locale=en script=Latn firstNames=[True]\n"
                + "FALSE => ok id=probe-v1 locale=en script=Latn firstNames=[FALSE]\n");
    }

    @Test
    @DisplayName("a version written with a leading zero, such as 010 or 0777, is refused, IllegalArgumentException \"LEADING_ZERO_CONFIG_NUMBER: ...\"; 0o10 is not a number; pool entries are text")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS,
                s -> vocabulary("version: " + s + "\npools:\n  firstNames: [" + s + "]\n" + OTHER_POOLS),
                VocabularyYamlCharacterisationTest::render)).isEqualTo("010 => refused IllegalArgumentException \"LEADING_ZERO_CONFIG_NUMBER: vocabulary file version must not be writte\"\n"
                + "0o10 => refused NumberFormatException \"For input string: \"0o10\"\"\n"
                + "0777 => refused IllegalArgumentException \"LEADING_ZERO_CONFIG_NUMBER: vocabulary file version must not be writte\"\n");
    }

    @Test
    @DisplayName("a pool entry that looks like a leading-zero number is text and is read as written")
    void octalLookingPoolEntries() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS,
                s -> vocabulary("pools:\n  firstNames: [" + s + "]\n" + OTHER_POOLS),
                VocabularyYamlCharacterisationTest::render)).isEqualTo("010 => ok id=probe-v1 locale=en script=Latn firstNames=[010]\n"
                + "0o10 => ok id=probe-v1 locale=en script=Latn firstNames=[0o10]\n"
                + "0777 => ok id=probe-v1 locale=en script=Latn firstNames=[0777]\n");
    }

    @Test
    @DisplayName("a number or boolean pool entry is refused, \"NON_STRING_CONFIG_SCALAR: ...\"; the quoted form is accepted")
    void nonStringScalarsInAPool() {
        assertThat(Observe.table(java.util.List.of("1", "true", "1.5", "\"1\""),
                s -> vocabulary("pools:\n  firstNames: [" + s + "]\n" + OTHER_POOLS),
                VocabularyYamlCharacterisationTest::render)).isEqualTo("1 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: vocabulary file pools.firstNames[0] must be \"\n"
                + "true => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: vocabulary file pools.firstNames[0] must be \"\n"
                + "1.5 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: vocabulary file pools.firstNames[0] must be \"\n"
                + "\"1\" => ok id=probe-v1 locale=en script=Latn firstNames=[1]\n");
    }

    @Test
    @DisplayName("unknown top-level key: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownTopLevelKey() {
        assertThat(outcome(vocabulary("extra: 1\npools:\n  firstNames: [a]\n" + OTHER_POOLS))).isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: vocabulary file has an unknown key 'extra'\"");
    }

    @Test
    @DisplayName("unknown nested key inside pools: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownNestedKey() {
        assertThat(outcome(vocabulary("pools:\n  surprise: [x]\n  firstNames: [a]\n" + OTHER_POOLS)))
                .isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: vocabulary file pools has an unknown key 'surprise\"");
    }
}
