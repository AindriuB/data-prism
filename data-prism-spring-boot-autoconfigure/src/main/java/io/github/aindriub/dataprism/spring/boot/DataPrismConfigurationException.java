package io.github.aindriub.dataprism.spring.boot;

/** A stable, non-secret diagnostic for a deployment contract refusal. */
public final class DataPrismConfigurationException extends IllegalStateException {
    public DataPrismConfigurationException(String code, String detail) { super(code + ": " + detail); }
}
