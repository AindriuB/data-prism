package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.pseudonymisation.vocabulary.PoolKind;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code VocabularyRegistry.builder().load(...)} does today (Jackson 2 YAML, SnakeYAML,
 * YAML 1.1 reading rules). The expected text is what the current code printed. The id is shown
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
    @DisplayName("duplicate key: last wins, silently (id first then second gives second)")
    void duplicateKey() {
        assertThat(outcome("id: first\nid: second\nlocale: en\npools:\n  firstNames: [a]\n" + OTHER_POOLS))
                .isEqualTo("ok id=second-v1 locale=en script=Zyyy firstNames=[a]");
    }

    @Test
    @DisplayName("as a pool entry, yes, on and True read as true; no, off and FALSE read as false; y and n stay the text y and n (YAML 1.1 booleans, but only the long forms); yes/no/on/off arrive as true/false text")
    void booleanSpellings() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS,
                s -> vocabulary("pools:\n  firstNames: [" + s + "]\n" + OTHER_POOLS),
                VocabularyYamlCharacterisationTest::render)).isEqualTo("yes => ok id=probe-v1 locale=en script=Latn firstNames=[true]\n"
                + "no => ok id=probe-v1 locale=en script=Latn firstNames=[false]\n"
                + "on => ok id=probe-v1 locale=en script=Latn firstNames=[true]\n"
                + "off => ok id=probe-v1 locale=en script=Latn firstNames=[false]\n"
                + "y => ok id=probe-v1 locale=en script=Latn firstNames=[y]\n"
                + "n => ok id=probe-v1 locale=en script=Latn firstNames=[n]\n"
                + "True => ok id=probe-v1 locale=en script=Latn firstNames=[true]\n"
                + "FALSE => ok id=probe-v1 locale=en script=Latn firstNames=[false]\n");
    }

    @Test
    @DisplayName("as a pool entry, 010 reads as decimal 8 and 0777 as decimal 511 (YAML 1.1 octal); 0o10 stays the text 0o10; as the version 010 gives v8 and 0o10 is refused with NumberFormatException")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS,
                s -> vocabulary("version: " + s + "\npools:\n  firstNames: [" + s + "]\n" + OTHER_POOLS),
                VocabularyYamlCharacterisationTest::render)).isEqualTo("010 => ok id=probe-v8 locale=en script=Latn firstNames=[8]\n"
                + "0o10 => refused NumberFormatException \"For input string: \"0o10\"\"\n"
                + "0777 => ok id=probe-v511 locale=en script=Latn firstNames=[511]\n");
    }

    @Test
    @DisplayName("unknown top-level key: ignored silently")
    void unknownTopLevelKey() {
        assertThat(outcome(vocabulary("extra: 1\npools:\n  firstNames: [a]\n" + OTHER_POOLS))).isEqualTo("ok id=probe-v1 locale=en script=Latn firstNames=[a]");
    }

    @Test
    @DisplayName("unknown nested key inside pools: ignored silently")
    void unknownNestedKey() {
        assertThat(outcome(vocabulary("pools:\n  surprise: [x]\n  firstNames: [a]\n" + OTHER_POOLS)))
                .isEqualTo("ok id=probe-v1 locale=en script=Latn firstNames=[a]");
    }
}
