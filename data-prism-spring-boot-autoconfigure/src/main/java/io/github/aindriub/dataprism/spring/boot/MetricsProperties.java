package io.github.aindriub.dataprism.spring.boot;

public class MetricsProperties {
    private String sink, registryReference;

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
