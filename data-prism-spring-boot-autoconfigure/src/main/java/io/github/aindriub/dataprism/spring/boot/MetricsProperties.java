package io.github.aindriub.dataprism.spring.boot;

public class MetricsProperties {
    /** Metrics sink to use; micrometer is the accepted value. Required in production. */
    private String sink;
    /** Reference to the approved registry binding. */
    private String registryReference;

    public String getSink() {
        return sink;
    }

    public void setSink(String v) {
        sink = v;
    }

    public String getRegistryReference() {
        return registryReference;
    }

    public void setRegistryReference(String v) {
        registryReference = v;
    }
}
