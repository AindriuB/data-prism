package io.github.aindriub.dataprism.spring.boot;

/**
 * Selects the built-in {@code IdentityResolver} for operators with no
 * Java to write, rather than one being inferred from other configuration.
 * Absent (the default), nothing is selected here: an {@code IdentityResolver}
 * bean must still come from the application, or
 * {@code Preflights#dataPrismIdentityResolverPreflight}
 * refuses startup with {@code MISSING_IDENTITY_RESOLVER}, exactly as before
 * this property existed.
 */
public class IdentityProperties {
    /** Built-in IdentityResolver to select. pass-through is the only accepted value; unset selects nothing. */
    private String resolver;

    public String getResolver() {
        return resolver;
    }

    public void setResolver(String v) {
        resolver = v;
    }
}
