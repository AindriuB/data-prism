package io.github.aindriub.dataprism.connectors.rest;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import io.github.aindriub.dataprism.core.model.StrictYaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Loads source definitions, and the optional outbound TLS configuration, from
 * YAML.
 *
 * <p>Parsed by hand for the same reason the privacy profiles and model
 * descriptors are: a malformed entry must be a startup failure naming the line,
 * not a source that silently does not exist. A missing source is not obviously
 * wrong at runtime — the response simply comes back assembled from fewer systems,
 * with a NO_DATA outcome that looks like the subject genuinely was not there.
 */
public final class RestSources {

    private static final String KIND = "source configuration";
    private static final Set<String> ROOT_KEYS = Set.of("sources", "tls");
    private static final Set<String> SOURCE_KEYS =
            Set.of("base-url", "path", "timeout", OutboundCorrelationHeader.KEY);
    static final Set<String> TLS_KEYS = Set.of("key-store", "key-store-password-env", "trust-store",
            "trust-store-password-env", "store-type");

    /**
     * Package-private, and no longer what the readers parse with: both readers go
     * through {@link StrictYaml#readMapping}, which refuses duplicate keys and
     * trailing content. What is left is a token-stream factory for {@code
     * OutboundCorrelationHeader.lineOf}. Architecture rule {@code
     * onlyDesignatedClassesCreateMappers} allowlists this class by name, so the
     * instance stays here rather than needing an entry for a sibling.
     */
    static final ObjectMapper YAML = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private RestSources() {
    }

    @SuppressWarnings("unchecked")
    public static RestSourcesConfig fromYaml(InputStream in) {
        return fromYaml(in, null);
    }

    /**
     * @param defaultCorrelationHeader the global {@code dataprism.correlation.outbound.header},
     *                                 used by every source without its own
     *                                 {@code correlation-header}; null for none
     */
    @SuppressWarnings("unchecked")
    public static RestSourcesConfig fromYaml(InputStream in, String defaultCorrelationHeader) {
        OutboundCorrelationHeader.validate(defaultCorrelationHeader, "the global outbound header default", 0);
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("source configuration could not be read", e);
        }
        Map<String, Object> root = StrictYaml.readMapping(bytes, KIND);
        if (root == null) {
            throw new IllegalArgumentException("source configuration has no `sources` section");
        }
        StrictYaml.requireOnlyKeys(root.keySet(), ROOT_KEYS, KIND);

        Object sources = root.get("sources");
        if (!(sources instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException("source configuration has no `sources` section");
        }

        TlsSettings tls = tls(root);
        boolean requireHttps = tls != null;

        Map<String, RestSource> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new IllegalArgumentException("source " + name + " is not a mapping");
            }
            StrictYaml.requireOnlyKeys(body.keySet(), SOURCE_KEYS, KIND + " sources." + StrictYaml.shown(name));
            out.put(name, source(name, (Map<String, Object>) body, requireHttps,
                    defaultCorrelationHeader, bytes));
        }
        return new RestSourcesConfig(Map.copyOf(out), tls);
    }

    private static RestSource source(String name, Map<String, Object> body, boolean requireHttps,
                                     String defaultCorrelationHeader, byte[] yaml) {
        String baseUrl = required(body, "base-url", "source " + name);
        String path = required(body, "path", "source " + name);
        String timeout = StrictYaml.optionalString(body, "timeout",
                "source configuration sources." + StrictYaml.shown(name));

        String header = correlationHeader(name, body, defaultCorrelationHeader, "sources", yaml);
        try {
            return new RestSource(name, new URI(baseUrl), path,
                    timeout == null ? Duration.ofSeconds(3) : Duration.parse(timeout),
                    requireHttps, header);
        } catch (URISyntaxException e) {
            // No cause: URISyntaxException's message repeats the whole input, user-info included.
            throw new IllegalArgumentException("source " + name + " has an unparseable base-url");
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("source " + name
                    + " has an unparseable timeout; use ISO-8601, e.g. PT2S", e);
        }
    }

    /** Package-private: {@link ConfiguredJsonSources} parses the same per-source key. */
    static String correlationHeader(String name, Map<String, Object> body, String defaultHeader,
                                    String rootKey, byte[] yaml) {
        if (!body.containsKey(OutboundCorrelationHeader.KEY)) {
            return defaultHeader;
        }
        Object raw = body.get(OutboundCorrelationHeader.KEY);
        String where = "source " + name;
        return OutboundCorrelationHeader.validate(
                raw == null ? "" : StrictYaml.text(raw, rootKey + "." + StrictYaml.shown(name) + "."
                        + OutboundCorrelationHeader.KEY),
                where,
                OutboundCorrelationHeader.lineOf(yaml, rootKey, name));
    }

    /** Package-private: {@link ConfiguredJsonSources} parses the same {@code tls:} shape. */
    @SuppressWarnings("unchecked")
    static TlsSettings tls(Map<String, Object> root) {
        Map<String, Object> tlsBody = StrictYaml.optionalMapping(root, "tls", "tls configuration");
        if (tlsBody == null) {
            return null;
        }
        StrictYaml.requireOnlyKeys(tlsBody.keySet(), TLS_KEYS, "tls configuration");

        String keyStore = required(tlsBody, "key-store", "tls configuration");
        String keyStorePasswordEnv = required(tlsBody, "key-store-password-env", "tls configuration");
        String trustStore = required(tlsBody, "trust-store", "tls configuration");
        String trustStorePasswordEnv = required(tlsBody, "trust-store-password-env", "tls configuration");
        String storeType = required(tlsBody, "store-type", "tls configuration");

        try {
            return new TlsSettings(Path.of(keyStore), Path.of(trustStore), storeType,
                    keyStorePasswordEnv, trustStorePasswordEnv);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "tls configuration has an unparseable key-store or trust-store path", e);
        }
    }

    /** Package-private: {@link ConfiguredJsonSources} reuses the same required-key check. */
    static String required(Map<String, Object> body, String key, String context) {
        Object value = body.get(key);
        if (value == null) {
            throw new IllegalArgumentException(context + " has no " + key);
        }
        String text = StrictYaml.text(value, context + " " + key);
        if (text.isBlank()) {
            throw new IllegalArgumentException(context + " has no " + key);
        }
        return text;
    }
}
