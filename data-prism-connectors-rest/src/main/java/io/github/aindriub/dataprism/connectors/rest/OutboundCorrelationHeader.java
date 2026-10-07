package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of the outbound correlation header name, shared by the per-source
 * {@code correlation-header} key and the global
 * {@code dataprism.correlation.outbound.header} default.
 *
 * <p>The name must be an RFC 9110 token, and must not be one that carries
 * credentials or frames the message: letting configuration set any of those to
 * a correlation id would break authentication or the HTTP exchange itself.
 */
final class OutboundCorrelationHeader {

    static final String CODE = "INVALID_CORRELATION_HEADER";
    static final String KEY = "correlation-header";

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> FORBIDDEN = Set.of("authorization", "proxy-authorization", "cookie",
            "host", "content-length", "transfer-encoding", "forwarded");

    private OutboundCorrelationHeader() {
    }

    /**
     * @param raw   the configured name, or null for none
     * @param where what the value belongs to, for the message
     * @param line  the 1-based YAML line, or {@code <= 0} when unknown or not from YAML
     * @return the name, or null when none was configured
     */
    static String validate(String raw, String where, int line) {
        if (raw == null) {
            return null;
        }
        String reason = null;
        if (!TOKEN.matcher(raw).matches()) {
            reason = "is not an RFC 9110 token";
        } else if (FORBIDDEN.contains(raw.toLowerCase(Locale.ROOT))) {
            reason = "names a header that must not carry a correlation id";
        }
        if (reason != null) {
            throw new IllegalArgumentException(CODE + ": " + where + " " + KEY + " " + reason
                    + (line > 0 ? " (line " + line + ")" : ""));
        }
        return raw;
    }

    /** Line of {@code correlation-header} under {@code root.source}, or 0 when it cannot be found. */
    static int lineOf(byte[] yaml, String rootKey, String source) {
        try (JsonParser parser = RestSources.YAML.getFactory().createParser(yaml)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME && KEY.equals(parser.currentName())) {
                    JsonStreamContext in = parser.getParsingContext();
                    JsonStreamContext sourceCtx = in.getParent();
                    JsonStreamContext rootCtx = sourceCtx == null ? null : sourceCtx.getParent();
                    if (sourceCtx != null && rootCtx != null
                            && source.equals(sourceCtx.getCurrentName())
                            && rootKey.equals(rootCtx.getCurrentName())) {
                        return parser.currentTokenLocation().getLineNr();
                    }
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return 0;
    }
}
