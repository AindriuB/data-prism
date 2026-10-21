package io.github.aindriub.dataprism.hazelcast.seal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;

import static io.github.aindriub.dataprism.hazelcast.seal.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterKeysTest {

    private static void assertRefusalNamesFieldOnly(Throwable refusal, String field, byte[]... secrets) {
        assertThat(refusal).isInstanceOf(IllegalArgumentException.class);
        assertThat(refusal.getMessage()).contains(field);
        for (byte[] secret : secrets) {
            assertThat(refusal.getMessage())
                    .doesNotContain(HexFormat.of().formatHex(secret))
                    .doesNotContain(Base64.getEncoder().encodeToString(secret))
                    .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        }
    }

    @Test
    @DisplayName("a valid configuration exposes current, next, previous and name keys")
    void validConfiguration() {
        ClusterKeys keys = rotatingKeys();
        assertThat(keys.current().id()).isEqualTo(CURRENT_ID);
        assertThat(keys.next()).hasValueSatisfying(k -> assertThat(k.id()).isEqualTo(NEXT_ID));
        assertThat(keys.previous()).hasValueSatisfying(k -> assertThat(k.id()).isEqualTo(PREVIOUS_ID));
        assertThat(keys.nameKeyId()).isEqualTo(NAME_ID);
        assertThat(keys.held()).extracting(ClusterKeys.Key::id).containsExactly("k1", "k2", "k0");
        assertThat(keys.find("k2")).isPresent();
        assertThat(keys.find("n1")).as("the name key is not a cluster key").isEmpty();
        assertThat(keys().next()).isEmpty();
    }

    @Test
    @DisplayName("a key under 32 bytes is refused, for each of the four keys")
    void shortKeysRefused() {
        byte[] shortKey = bytes(1, 31);
        assertThatThrownBy(() -> new ClusterKeys("k1", shortKey, null, null, null, null, "n1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "current", shortKey));
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), "k2", shortKey, null, null, "n1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "next", shortKey));
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), null, null, "k0", shortKey, "n1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "previous", shortKey));
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), null, null, null, null, "n1", shortKey))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "name", shortKey));
    }

    @Test
    @DisplayName("duplicated ids are refused, naming the fields and not the id or the material")
    void duplicateIdsRefused() {
        assertThatThrownBy(() -> new ClusterKeys("same", currentKey(), "same", nextKey(), null, null, "n1", nameKey()))
                .satisfies(e -> {
                    assertRefusalNamesFieldOnly(e, "current", currentKey(), nextKey());
                    assertThat(e.getMessage()).contains("next").doesNotContain("same");
                });
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), null, null, null, null, "k1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "name", nameKey()));
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), "k2", nextKey(), "k2", previousKey(), "n1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "previous", nextKey(), previousKey()));
    }

    @Test
    @DisplayName("a name key equal to any cluster key is refused")
    void nameKeyEqualToClusterKeyRefused() {
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), null, null, null, null, "n1", currentKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "name", currentKey()));
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), "k2", nextKey(), null, null, "n1", nextKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "name", nextKey()));
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), null, null, "k0", previousKey(), "n1", previousKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "name", previousKey()));
    }

    @Test
    @DisplayName("an id that is blank or holds a dot is refused, and next and previous come as id plus material")
    void idShapeAndPairing() {
        assertThatThrownBy(() -> new ClusterKeys("a.b", currentKey(), null, null, null, null, "n1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "current"));
        assertThatThrownBy(() -> new ClusterKeys(" ", currentKey(), null, null, null, null, "n1", nameKey()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), "k2", null, null, null, "n1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "next"));
        assertThatThrownBy(() -> new ClusterKeys("k1", currentKey(), null, nextKey(), null, null, "n1", nameKey()))
                .satisfies(e -> assertRefusalNamesFieldOnly(e, "next", nextKey()));
    }

    @Test
    @DisplayName("toString and the held key never show material, and a caller's array is copied")
    void materialIsNotLeakedOrAliased() {
        byte[] material = currentKey();
        ClusterKeys keys = new ClusterKeys("k1", material, null, null, null, null, "n1", nameKey());
        material[0] = 99;
        assertThat(keys.current().material()[0]).isZero();
        keys.current().material()[0] = 99;
        assertThat(keys.current().material()[0]).isZero();
        assertThat(keys.toString() + keys.current())
                .doesNotContain(HexFormat.of().formatHex(currentKey()))
                .doesNotContain(Base64.getEncoder().encodeToString(currentKey()));
    }
}
