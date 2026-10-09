package io.github.aindriub.dataprism.spring.boot;

public class CorrelationProperties {
    private final Inbound inbound = new Inbound();
    private final Outbound outbound = new Outbound();

    public Inbound getInbound() {
        return inbound;
    }

    public Outbound getOutbound() {
        return outbound;
    }

    public static class Inbound {
        /** HTTP request header carrying the external correlation id. Unset disables the feature. */
        private String header;
        /** opaque accepts a value matching the pattern; traceparent accepts a W3C traceparent value and records its trace id. */
        private String format = "opaque";
        /** Regular expression the whole value must match with format opaque. Unset uses the strict default. */
        private String pattern;
        /** If true, a call without a valid id is refused and audited before any source is called. */
        private boolean required;

        public String getHeader() {
            return header;
        }

        public void setHeader(String v) {
            header = v;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String v) {
            format = v;
        }

        /** Unset means {@code CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN}. */
        public String getPattern() {
            return pattern;
        }

        public void setPattern(String v) {
            pattern = v;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean v) {
            required = v;
        }

        public String effectivePattern() {
            return pattern == null
                    ? io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN
                    : pattern;
        }

        /** The policy for the configured format; only meaningful after {@code validate()}. */
        public io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy policy() {
            return "traceparent".equals(format)
                    ? io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.traceparent()
                    : io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.opaque(effectivePattern());
        }
    }

    public static class Outbound {
        /** Header name that sends the correlation id to a source. Unset sends nothing. */
        private String header;

        public String getHeader() {
            return header;
        }

        public void setHeader(String v) {
            header = v;
        }
    }

    /** SLF4J MDC key under which the validated id is set for the duration of a tool call. Unset turns MDC off; needs inbound.header. */
    private String mdcKey;

    /**
     * The SLF4J MDC key the validated external correlation id is put under for the duration of a
     * tool call. Unset (the default) means MDC is never touched.
     */
    public String getMdcKey() {
        return mdcKey;
    }

    public void setMdcKey(String v) {
        mdcKey = v;
    }
}
