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
    @DisplayName("duplicate key: last wins, silently (id first then second gives second)")
    void duplicateKey() {
        assertThat(outcome("id: first\nid: second\nlocale: en\npools:\n  firstNames: [a]\n" + OTHER_POOLS))
                .isEqualTo("ok id=second-v1 locale=en script=Zyyy firstNames=[a]");
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
    @DisplayName("a leading-zero number such as 010 or 0777 is read as written, not as octal (YAML 1.2)")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS,
                s -> vocabulary("version: " + s + "\npools:\n  firstNames: [" + s + "]\n" + OTHER_POOLS),
                VocabularyYamlCharacterisationTest::render)).isEqualTo("010 => ok id=probe-v10 locale=en script=Latn firstNames=[010]\n"
                + "0o10 => refused NumberFormatException \"For input string: \"0o10\"\"\n"
                + "0777 => ok id=probe-v777 locale=en script=Latn firstNames=[0777]\n");
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
