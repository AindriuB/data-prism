package io.github.aindriub.dataprism.spring.boot;

/** A stable, non-secret diagnostic for a deployment contract refusal. */
public final class DataPrismConfigurationException extends IllegalStateException {
    private final String code;

    public DataPrismConfigurationException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    /** As above, keeping a cause that carries no secret (a coded refusal from another module, say). */
    public DataPrismConfigurationException(String code, String detail, Throwable cause) {
        super(code + ": " + detail, cause);
        this.code = code;
    }

    /**
     * The stable refusal code (e.g. {@code MISSING_IDENTITY_RESOLVER}) alone, without the
     * detail text passed to the constructor — which may carry a configured value and so is
     * not safe to render to an operator-facing surface. See
     * {@link DataPrismConfigurationFailureAnalyzer}.
     */
    public String code() { return code; }
}
