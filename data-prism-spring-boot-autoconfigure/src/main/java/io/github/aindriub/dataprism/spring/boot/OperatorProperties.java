package io.github.aindriub.dataprism.spring.boot;

/** {@code dataprism.operator.*}: the second, separately secured port the operator endpoints will use. */
public class OperatorProperties {
    private boolean enabled;
    private Integer port;
    private String requiredAudience, requiredScope;
    /** The address the operator connector binds to; unset means the same as {@code server.address}. */
    private String address;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String v) {
        address = v;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer v) {
        port = v;
    }

    public String getRequiredAudience() {
        return requiredAudience;
    }

    public void setRequiredAudience(String v) {
        requiredAudience = v;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public void setRequiredScope(String v) {
        requiredScope = v;
    }
}
