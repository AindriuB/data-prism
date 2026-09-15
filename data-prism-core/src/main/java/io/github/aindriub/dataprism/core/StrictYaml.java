package io.github.aindriub.dataprism.core;

import java.util.Locale;

/**
 * Hand-written YAML parsing shared by {@code ModelDescriptors} and
 * {@code PrivacyProfiles}.
 *
 * <p>Both load configuration that is parsed from a generic map rather than
 * data-bound, so that an unknown classification, action or other enum
 * constant is a startup failure naming the offending key rather than a null
 * that binding would have produced silently. This class carries that one
 * shared piece of the contract; each caller still owns its {@code ObjectMapper}
 * and the rest of its own parsing.
 */
public final class StrictYaml {

    private StrictYaml() {
    }

    /**
     * Resolves {@code raw} against {@code type}, case- and whitespace-insensitively,
     * failing with an {@link IllegalArgumentException} naming the enum, the
     * offending value and {@code where} in the source document if it does not
     * match a constant.
     */
    public static <E extends Enum<E>> E enumValue(Class<E> type, Object raw, String where) {
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown " + type.getSimpleName() + " '" + value + "' at " + where, e);
        }
    }
}
