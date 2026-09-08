package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads source definitions from YAML.
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
    public static Map<String, RestSource> fromYaml(InputStream in) {
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

        Map<String, RestSource> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new IllegalArgumentException("source " + name + " is not a mapping");
            }
            out.put(name, source(name, (Map<String, Object>) body));
        }
        return Map.copyOf(out);
    }

    private static RestSource source(String name, Map<String, Object> body) {
        String baseUrl = required(body, "base-url", name);
        String path = required(body, "path", name);
        Object timeout = body.get("timeout");

        try {
            return new RestSource(name, new URI(baseUrl), path,
                    timeout == null ? Duration.ofSeconds(3) : Duration.parse(String.valueOf(timeout)));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "source " + name + " has an unparseable base-url", e);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("source " + name
                    + " has an unparseable timeout; use ISO-8601, e.g. PT2S", e);
        }
    }

    private static String required(Map<String, Object> body, String key, String name) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("source " + name + " has no " + key);
        }
        return String.valueOf(value);
    }
}
