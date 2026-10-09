package io.github.aindriub.dataprism.spring.boot;

/** {@code dataprism.operator.*}: the second, separately secured port the operator endpoints will use. */
public class OperatorProperties {
    /** Turns the operator surface on. Defaults to false. */
    private boolean enabled;
    /** Operator port. Required when enabled; must differ from server.port. */
    private Integer port;
    /** JWT audience an operator token must carry. Required when enabled; must differ from the MCP audience. */
    private String requiredAudience;
    /** Scope an operator token must carry. Required when enabled. */
    private String requiredScope;
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
