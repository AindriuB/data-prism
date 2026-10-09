package io.github.aindriub.dataprism.spring.boot;

public class TransportProperties {
    public enum Mode { HTTP, STDIO }

    /** Inbound transport mode. http for protected deployments; stdio is refused. */
    private Mode mode = Mode.HTTP;
    /** Marks a local fixture-development run. Defaults to false; never set for a protected deployment. */
    private boolean fixtureDevelopment;
    private Http http = new Http();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode v) {
        mode = v;
    }

    public boolean isFixtureDevelopment() {
        return fixtureDevelopment;
    }

    public void setFixtureDevelopment(boolean v) {
        fixtureDevelopment = v;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http v) {
        http = v == null ? new Http() : v;
    }

    public static class Http {
        /** Rooted HTTP path of the MCP endpoint. Defaults to /mcp. */
        private String path = "/mcp";

        public String getPath() {
            return path;
        }

        public void setPath(String v) {
            path = v;
        }
    }
}
