package io.github.aindriub.dataprism.security;

import java.util.Objects;
import java.util.Set;

/**
 * Accepts only a purpose in the configured list, case-sensitively.
 *
 * <p>The list is the only lever this validator has: nothing here inspects a
 * purpose's spelling or shape, so adding a purpose to configuration is the
 * only way to make a previously refused purpose pass.
 */
public final class PurposeValidator {

    public static final String UNKNOWN_PURPOSE = "UNKNOWN_PURPOSE";

    private final Set<String> allowedPurposes;

    public PurposeValidator(Set<String> allowedPurposes) {
        Objects.requireNonNull(allowedPurposes, "allowedPurposes");
        this.allowedPurposes = Set.copyOf(allowedPurposes);
    }

    /**
     * @return the validated purpose, unchanged
     * @throws SecurityRefusedException with code {@link #UNKNOWN_PURPOSE} for a null,
     *                                  blank or unlisted purpose
     */
    public String validate(String purpose) {
        if (purpose == null || purpose.isBlank() || !allowedPurposes.contains(purpose)) {
            throw new SecurityRefusedException(UNKNOWN_PURPOSE, "purpose is not in the configured list");
        }
        return purpose;
    }
}
