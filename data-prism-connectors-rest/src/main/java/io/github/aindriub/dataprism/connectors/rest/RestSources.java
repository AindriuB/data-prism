package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private RestSources() {
    }

    @SuppressWarnings("unchecked")
    public static RestSourcesConfig fromYaml(InputStream in) {
        Map<String, Object> root;
        try {
            root = YAML.readValue(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("source configuration could not be read", e);
        }

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
            out.put(name, source(name, (Map<String, Object>) body, requireHttps));
        }
        return new RestSourcesConfig(Map.copyOf(out), tls);
    }

    private static RestSource source(String name, Map<String, Object> body, boolean requireHttps) {
        String baseUrl = required(body, "base-url", "source " + name);
        String path = required(body, "path", "source " + name);
        Object timeout = body.get("timeout");

        try {
            return new RestSource(name, new URI(baseUrl), path,
                    timeout == null ? Duration.ofSeconds(3) : Duration.parse(String.valueOf(timeout)),
                    requireHttps);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "source " + name + " has an unparseable base-url", e);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("source " + name
                    + " has an unparseable timeout; use ISO-8601, e.g. PT2S", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static TlsSettings tls(Map<String, Object> root) {
        Object node = root.get("tls");
        if (node == null) {
            return null;
        }
        if (!(node instanceof Map<?, ?> body)) {
            throw new IllegalArgumentException("tls configuration is not a mapping");
        }
        Map<String, Object> tlsBody = (Map<String, Object>) body;

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

    private static String required(Map<String, Object> body, String key, String context) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(context + " has no " + key);
        }
        return String.valueOf(value);
    }
}
