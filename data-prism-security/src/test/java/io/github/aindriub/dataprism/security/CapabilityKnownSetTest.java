package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.Capability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up carried from wave 1's review: {@code Capability.KNOWN} had no test
 * pinning its membership, so a fifth capability constant added later without
 * also adding it to {@code KNOWN} would fail silently. Mirrors
 * {@code core.PrivacyMetricsTest}'s exact-set assertion for {@code Metric};
 * here the reflective scan over every declared constant plays the role
 * {@code Metric.values()} plays there, so a new constant is caught without
 * this test needing to name it first.
 */
class CapabilityKnownSetTest {

    @Test
    @DisplayName("Capability.KNOWN covers exactly every declared capability constant")
    void knownCoversExactlyEveryDeclaredCapability() throws IllegalAccessException {
        Set<String> declared = new LinkedHashSet<>();
        for (Field field : Capability.class.getDeclaredFields()) {
            if (field.getName().equals("KNOWN")) {
                continue;
            }
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                declared.add((String) field.get(null));
            }
        }

        assertThat(declared).isNotEmpty();
        assertThat(Capability.KNOWN).isEqualTo(declared);
    }
}
