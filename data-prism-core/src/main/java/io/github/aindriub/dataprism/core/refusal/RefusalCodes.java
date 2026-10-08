package io.github.aindriub.dataprism.core.refusal;

import java.util.regex.Pattern;

/**
 * Keeps application-supplied refusal codes from carrying data.
 *
 * <p>A refusal code can come from a scrubber, a validator, an authorisation
 * service or an admission decision, all of which an application writes. Before
 * a code is written to the audit file or into a tool result for the model it
 * has to be a plain upper-case token; anything else is replaced by
 * {@link #INVALID}. The exception that carried it keeps the raw code.
 */
public final class RefusalCodes {

    /** Stands in for a code that is not an upper-case token. */
    public static final String INVALID = "INVALID_REFUSAL_CODE";

    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private RefusalCodes() {
    }

    /** {@code code} when it matches {@code [A-Z][A-Z0-9_]{0,63}} in full, {@link #INVALID} otherwise, including for {@code null}. */
    public static String sanitise(String code) {
        return code != null && TOKEN.matcher(code).matches() ? code : INVALID;
    }
}
