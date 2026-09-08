package io.github.aindriub.dataprism.core;

/**
 * Thrown when a response cannot be made safe and must not be returned.
 *
 * <p>Carries a stable code and the JSON path that caused it, and never the
 * offending value — an exception message is one of the places sensitive data
 * most often escapes, since it ends up in logs, traces and error responses at
 * once. See docs/pack.md §48.
 */
public class PrivacyRefusedException extends RuntimeException {

    private final String code;
    private final String path;

    public PrivacyRefusedException(String code, String path, String detail) {
        super(code + " at " + path + ": " + detail);
        this.code = code;
        this.path = path;
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }
}
