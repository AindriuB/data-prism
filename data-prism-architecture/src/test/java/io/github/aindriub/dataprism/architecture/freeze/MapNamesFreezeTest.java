package io.github.aindriub.dataprism.architecture.freeze;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Freezes the Hazelcast map and protection names (register section 8.1; they are a migration contract once keys
 * are sealed). The names are read by reflection from the constants of {@code PrivacyCluster}: every
 * {@code static final String} ending {@code _MAP} is a map, one ending {@code _PROTECTION} is a split-brain
 * protection, and {@code PRE_0_8_MAPS} lists the unsealed maps 0.8 checks for entries. The list is
 * {@code <kind> <name>}, in the order of the names. Regenerate with {@code -Dfreeze.write=<directory>}.
 */
class MapNamesFreezeTest {

    private static final String CLUSTER = "io.github.aindriub.dataprism.hazelcast.PrivacyCluster";

    @Test
    void theClusterMapNamesMatchTheFrozenList() throws Exception {
        List<String> actual = new ArrayList<>();
        Class<?> cluster = Class.forName(CLUSTER);
        for (Field field : cluster.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            if (field.getType() == String.class && field.getName().endsWith("_MAP")) {
                actual.add("map " + field.get(null));
            } else if (field.getType() == String.class && field.getName().endsWith("_PROTECTION")) {
                actual.add("protection " + field.get(null));
            } else if (field.getName().equals("PRE_0_8_MAPS")) {
                for (Object name : (List<?>) field.get(null)) {
                    actual.add("legacy-map " + name);
                }
            }
        }
        actual.sort(null);
        FreezeLists.assertMatches("map-names.txt", actual);
    }
}
