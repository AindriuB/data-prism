package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.security.SecurityPolicy;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link SecurityPolicy#fromYaml} does today (Jackson 2 YAML, SnakeYAML, YAML 1.1 reading
 * rules). Each test's name or Javadoc states the observed behaviour; the expected text is what the
 * current code printed.
 */
class SecurityPolicyYamlCharacterisationTest {

    private static String render(InputStream in) {
        SecurityPolicy policy = SecurityPolicy.fromYaml(in);
        return "purposes=" + new java.util.TreeSet<>(policy.allowedPurposes())
                + " roles=" + new TreeMap<>(new TreeMap<>(policy.roleCapabilities()).entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                e -> new java.util.TreeSet<>(e.getValue()))));
    }

    private static String outcome(String yaml) {
        return Observe.outcome(() -> render(Observe.yaml(yaml)));
    }

    @Test
    @DisplayName("duplicate key: last wins, silently (purposes [first] then [second] gives [second])")
    void duplicateKey() {
        assertThat(outcome("purposes: [first]\npurposes: [second]\n")).isEqualTo("ok purposes=[second] roles={}");
    }

    @Test
    @Disabled("Jackson 3 reads YAML 1.2 where Jackson 2 read 1.1; awaiting owner acceptance, see the 167 hand-back; the Jackson 3 behaviour is asserted by booleanSpellingsJackson3")
    @DisplayName("as a purpose, yes, on and True read as true; no, off and FALSE read as false; y and n stay the text y and n (YAML 1.1 booleans, but only the long forms)")
    void booleanSpellings() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS, s -> "purposes:\n  - " + s + "\n",
                SecurityPolicyYamlCharacterisationTest::render)).isEqualTo("yes => ok purposes=[true] roles={}\n"
                + "no => ok purposes=[false] roles={}\n"
                + "on => ok purposes=[true] roles={}\n"
                + "off => ok purposes=[false] roles={}\n"
                + "y => ok purposes=[y] roles={}\n"
                + "n => ok purposes=[n] roles={}\n"
                + "True => ok purposes=[true] roles={}\n"
                + "FALSE => ok purposes=[false] roles={}\n");
    }

    @Test
    @DisplayName("Jackson 3: booleanSpellings (YAML 1.2 reading)")
    void booleanSpellingsJackson3() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS, s -> "purposes:\n  - " + s + "\n",
                SecurityPolicyYamlCharacterisationTest::render)).isEqualTo("yes => ok purposes=[yes] roles={}\n"
                + "no => ok purposes=[no] roles={}\n"
                + "on => ok purposes=[on] roles={}\n"
                + "off => ok purposes=[off] roles={}\n"
                + "y => ok purposes=[y] roles={}\n"
                + "n => ok purposes=[n] roles={}\n"
                + "True => ok purposes=[True] roles={}\n"
                + "FALSE => ok purposes=[FALSE] roles={}\n");
    }

    @Test
    @Disabled("Jackson 3 reads YAML 1.2 where Jackson 2 read 1.1; awaiting owner acceptance, see the 167 hand-back; the Jackson 3 behaviour is asserted by octalLookingScalarsJackson3")
    @DisplayName("as a purpose, 010 reads as decimal 8 and 0777 as decimal 511 (YAML 1.1 octal); 0o10 stays the text 0o10")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS, s -> "purposes:\n  - " + s + "\n",
                SecurityPolicyYamlCharacterisationTest::render)).isEqualTo("010 => ok purposes=[8] roles={}\n"
                + "0o10 => ok purposes=[0o10] roles={}\n"
                + "0777 => ok purposes=[511] roles={}\n");
    }

    @Test
    @DisplayName("Jackson 3: octalLookingScalars (YAML 1.2 reading)")
    void octalLookingScalarsJackson3() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS, s -> "purposes:\n  - " + s + "\n",
                SecurityPolicyYamlCharacterisationTest::render)).isEqualTo("010 => ok purposes=[010] roles={}\n"
                + "0o10 => ok purposes=[0o10] roles={}\n"
                + "0777 => ok purposes=[0777] roles={}\n");
    }

    @Test
    @DisplayName("unknown top-level key: refused, IllegalArgumentException \"unknown security policy key 'extra'\"")
    void unknownTopLevelKey() {
        assertThat(outcome("purposes: [p]\nextra: 1\n")).isEqualTo("refused IllegalArgumentException \"unknown security policy key 'extra'\"");
    }

    @Test
    @DisplayName("unknown nested key (a role given a mapping instead of a list): refused, IllegalArgumentException \"role 'r' capabilities must be a list\"")
    void unknownNestedKey() {
        assertThat(outcome("purposes: [p]\nroles:\n  r:\n    capabilities: [GET_ENTITY_CONTEXT]\n"))
                .isEqualTo("refused IllegalArgumentException \"role 'r' capabilities must be a list\"");
    }

    @Test
    @DisplayName("capability spelling: lower and mixed case refused as unknown, a padded upper-case name accepted (trimmed)")
    void capabilitySpelling() {
        assertThat(Observe.table(java.util.List.of("get_entity_context", "Get_Entity_Context",
                        "\"  GET_ENTITY_CONTEXT  \""),
                s -> "purposes: [p]\nroles:\n  r: [" + s + "]\n",
                SecurityPolicyYamlCharacterisationTest::render)).isEqualTo("get_entity_context => refused IllegalArgumentException \"role 'r' names unknown capability 'get_entity_context'\"\n"
                + "Get_Entity_Context => refused IllegalArgumentException \"role 'r' names unknown capability 'Get_Entity_Context'\"\n"
                + "\"  GET_ENTITY_CONTEXT  \" => ok purposes=[p] roles={r=[GET_ENTITY_CONTEXT]}\n");
    }
}
