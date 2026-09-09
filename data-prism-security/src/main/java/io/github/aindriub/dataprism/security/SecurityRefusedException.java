package io.github.aindriub.dataprism.security;

/**
 * Thrown when a caller, purpose or session cannot be trusted and the request
 * must stop rather than proceed with a defaulted or partial field.
 *
 * <p>Carries a stable code and nothing else — never the claim, purpose or
 * argument value that caused the refusal. Mirrors
 * {@code io.github.aindriub.dataprism.core.PrivacyRefusedException} for the
 * same reason: an exception message is one of the places sensitive data most
 * often escapes. See docs/pack.md §51.
 */
public class SecurityRefusedException extends RuntimeException {

    private final String code;

    public SecurityRefusedException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
