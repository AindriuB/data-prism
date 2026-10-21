package io.github.aindriub.dataprism.hazelcast.seal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static io.github.aindriub.dataprism.hazelcast.seal.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class KeyedNamesTest {

    private static final String MAP = "dataprism.approval.id.s1";
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9_-]+");

    private final KeyedNames names = new KeyedNames(keys());

    @Test
    @DisplayName("map keys are 43 URL-safe characters with no dot, and the scope token is 22")
    void shapes() {
        assertThat(names.mapKey(MAP, "approval-0001")).hasSize(43).matches(URL_SAFE);
        assertThat(names.scopeToken("scope-0001")).hasSize(22).matches(URL_SAFE);
        assertThat(names.scoped(MAP, "scope-0001", "approval-0001"))
                .isEqualTo(names.scopeToken("scope-0001") + "." + names.mapKey(MAP, "approval-0001"));
    }

    @Test
    @DisplayName("the same name key and input always give the same name")
    void deterministic() {
        KeyedNames again = new KeyedNames(keys());
        assertThat(again.mapKey(MAP, "x")).isEqualTo(names.mapKey(MAP, "x"));
        assertThat(again.scopeToken("s")).isEqualTo(names.scopeToken("s"));
        assertThat(again.scoped(MAP, "s", "x")).isEqualTo(names.scoped(MAP, "s", "x"));
    }

    @Test
    @DisplayName("two different name keys give different names for one input")
    void differentNameKeys() {
        ClusterKeys other = new ClusterKeys("k1", currentKey(), null, null, null, null, "n2", bytes(0x55, 32));
        KeyedNames otherNames = new KeyedNames(other);
        assertThat(otherNames.mapKey(MAP, "x")).isNotEqualTo(names.mapKey(MAP, "x"));
        assertThat(otherNames.scopeToken("s")).isNotEqualTo(names.scopeToken("s"));
    }

    @Test
    @DisplayName("the map is part of the name, and the cluster key plays no part in it")
    void mapSeparationAndKeyIndependence() {
        assertThat(names.mapKey("dataprism.budget.s1", "x")).isNotEqualTo(names.mapKey("dataprism.identity.s1", "x"));
        ClusterKeys otherCluster = new ClusterKeys("k9", bytes(0x70, 32), null, null, null, null, NAME_ID, nameKey());
        assertThat(new KeyedNames(otherCluster).mapKey(MAP, "x")).isEqualTo(names.mapKey(MAP, "x"));
    }

    @Test
    @DisplayName("a lock key is the keyed map key of the entry it guards")
    void lockKeyIsTheMapKey() {
        assertThat(names.lockKey(MAP, "approval-0001")).isEqualTo(names.mapKey(MAP, "approval-0001"));
    }

    @Test
    @DisplayName("output holds none of the identifiers as a substring")
    void noIdentifierAppearsInAName() {
        List<String> identifiers = List.of("scope-7f3a", "subject-4411", "principal-ana", "approval-9a21", "pseudo-Kd83");
        for (String id : identifiers) {
            for (String output : List.of(names.mapKey(MAP, id), names.lockKey(MAP, id), names.scopeToken(id),
                    names.scoped(MAP, id, id))) {
                for (String each : identifiers) {
                    assertThat(output).doesNotContain(each);
                }
            }
        }
    }

    @Test
    @DisplayName("the empty scope id still has a token, as an approval with no scope needs")
    void emptyScope() {
        assertThat(names.scopeToken("")).hasSize(22);
    }
}
