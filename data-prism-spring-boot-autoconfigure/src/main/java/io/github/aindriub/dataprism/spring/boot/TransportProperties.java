package io.github.aindriub.dataprism.spring.boot;

public class TransportProperties {
    public enum Mode { HTTP, STDIO }

    private Mode mode = Mode.HTTP;
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
        private String path = "/mcp";

        public String getPath() {
            return path;
        }

        public void setPath(String v) {
            path = v;
        }
    }
}
