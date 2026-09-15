package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.FieldMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads configuration-driven JSON REST sources from YAML.
 *
 * <p>This is a separate configuration surface from {@link RestSources}, read
 * from its own {@code json-sources:} root key, never from {@code sources:}. That
 * separation is deliberate: docs/configuration.md is explicit that generic JSON
 * sources are not a hidden interpretation of the Java-first vocabulary, and
 * mixing the two root keys would make a typo in one file capable of turning a
 * reviewed Java-first source into an unreviewed generic one.
 *
 * <p>Every property this mode can ever emit for a source must be named in that
 * source's {@code fields:} map, including the field carrying the subject
 * identifier. There is no default that lets a property through unclassified:
 * an entry not listed here never reaches {@link ConfiguredJsonFieldMetadataResolver}
 * and is refused by {@link io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine}
 * as an unknown field before anything is returned.
 */
public final class ConfiguredJsonSources {

    /**
     * A bare property name: letters, digits and underscore, not starting with a
     * digit. This is the entire JSON-path grammar this mode accepts, for both
     * {@code subject-json-path} and every key of {@code fields}. It cannot spell
     * a host, a scheme, a query string, a second path segment, or anything a
     * general JSONPath expression could reach outside the single object the
     * response already is: there is nowhere in the grammar for one to go.
     */
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final Set<String> SOURCE_KEYS =
            Set.of("base-url", "path", "timeout", "model-version", "subject-json-path", "fields");
    private static final Set<String> IDENTIFIER_FIELD_KEYS = Set.of("identifier");
    private static final Set<String> NON_SENSITIVE_FIELD_KEYS = Set.of("nonSensitive");
    private static final Set<String> SENSITIVE_FIELD_KEYS =
            Set.of("classifications", "namespace", "action");

    // Reuses RestSources.YAML rather than constructing a second ObjectMapper:
    // see that field's Javadoc.
    private static final ObjectMapper YAML = RestSources.YAML;

    private ConfiguredJsonSources() {
    }

    @SuppressWarnings("unchecked")
    public static ConfiguredJsonSourcesConfig fromYaml(InputStream in) {
        Map<String, Object> root;
        try {
            root = YAML.readValue(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("configured JSON source configuration could not be read", e);
        }

        Object sources = root.get("json-sources");
        if (!(sources instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException(
                    "configured JSON source configuration has no `json-sources` section");
        }

        TlsSettings tls = RestSources.tls(root);
        boolean requireHttps = tls != null;

        Map<String, ConfiguredJsonSource> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (name.isBlank()) {
                throw new IllegalArgumentException("a json-sources entry has a blank name");
            }
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new IllegalArgumentException("json source " + name + " is not a mapping");
            }
            out.put(name, source(name, (Map<String, Object>) body, requireHttps));
        }
        return new ConfiguredJsonSourcesConfig(Map.copyOf(out), tls);
    }

    @SuppressWarnings("unchecked")
    private static ConfiguredJsonSource source(String name, Map<String, Object> body, boolean requireHttps) {
        rejectUnknownKeys(body.keySet(), SOURCE_KEYS, "json source " + name);

        String baseUrl = RestSources.required(body, "base-url", "json source " + name);
        String path = RestSources.required(body, "path", "json source " + name);
        String modelVersion = RestSources.required(body, "model-version", "json source " + name);
        String subjectJsonPath = RestSources.required(body, "subject-json-path", "json source " + name);
        Object timeoutRaw = body.get("timeout");
        if (timeoutRaw == null) {
            throw new IllegalArgumentException("json source " + name + " has no timeout;"
                    + " this mode requires an explicit bounded timeout, it does not default one");
        }

        RestSource transport;
        try {
            transport = new RestSource(name, new URI(baseUrl), path,
                    Duration.parse(String.valueOf(timeoutRaw)), requireHttps);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("json source " + name + " has an unparseable base-url", e);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "json source " + name + " has an unparseable timeout; use ISO-8601, e.g. PT2S", e);
        }

        if (!FIELD_NAME.matcher(subjectJsonPath).matches()) {
            throw new IllegalArgumentException("json source " + name
                    + " subject-json-path '" + subjectJsonPath
                    + "' is not a bare property name; this mode allows only a single top-level"
                    + " field name, never a host, a query, a nested path or an expression");
        }

        Object fieldsNode = body.get("fields");
        if (!(fieldsNode instanceof Map<?, ?> fieldsMap) || fieldsMap.isEmpty()) {
            throw new IllegalArgumentException("json source " + name + " has no fields catalogue;"
                    + " every property this source may ever emit, including its subject field,"
                    + " must be named here");
        }

        Map<String, FieldMetadata> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : fieldsMap.entrySet()) {
            String fieldName = String.valueOf(entry.getKey());
            if (!FIELD_NAME.matcher(fieldName).matches()) {
                throw new IllegalArgumentException("json source " + name + " field '" + fieldName
                        + "' is not a bare property name");
            }
            if (!(entry.getValue() instanceof Map<?, ?> fieldBody)) {
                throw new IllegalArgumentException(
                        "json source " + name + " field " + fieldName + " is not a mapping");
            }
            fields.put(fieldName, field(name, fieldName, (Map<String, Object>) fieldBody));
        }

        return new ConfiguredJsonSource(transport, modelVersion, subjectJsonPath, fields);
    }

    private static FieldMetadata field(String sourceName, String fieldName, Map<String, Object> body) {
        String where = "json source " + sourceName + " field " + fieldName;
        boolean identifier = body.containsKey("identifier");
        boolean nonSensitive = body.containsKey("nonSensitive");
        boolean sensitive = body.containsKey("classifications") || body.containsKey("namespace")
                || body.containsKey("action");

        if ((identifier ? 1 : 0) + (nonSensitive ? 1 : 0) + (sensitive ? 1 : 0) != 1) {
            throw new IllegalArgumentException(where + " must state exactly one of:"
                    + " `identifier: true`, `nonSensitive: <reason>`,"
                    + " or classifications (with optional namespace/action)");
        }

        if (identifier) {
            rejectUnknownKeys(body.keySet(), IDENTIFIER_FIELD_KEYS, where);
            if (!Boolean.parseBoolean(String.valueOf(body.get("identifier")))) {
                throw new IllegalArgumentException(where + " sets identifier: false;"
                        + " remove the key entirely instead of stating a negative");
            }
            return new FieldMetadata(fieldName, true, FieldMetadata.SELF, List.of(),
                    PrivacyNamespace.NONE, null, "", null, String.class, null);
        }

        if (nonSensitive) {
            rejectUnknownKeys(body.keySet(), NON_SENSITIVE_FIELD_KEYS, where);
            String reason = String.valueOf(body.get("nonSensitive"));
            if (reason.isBlank()) {
                throw new IllegalArgumentException(where + " has a blank nonSensitive reason");
            }
            return new FieldMetadata(fieldName, false, null, List.of(), PrivacyNamespace.NONE,
                    null, "", reason, String.class, null);
        }

        rejectUnknownKeys(body.keySet(), SENSITIVE_FIELD_KEYS, where);
        List<DataClassification> classifications = new ArrayList<>();
        Object raw = body.get("classifications");
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new IllegalArgumentException(where + " has an empty classifications list");
            }
            for (Object value : list) {
                classifications.add(enumValue(DataClassification.class, value, where + ".classifications"));
            }
        } else if (raw != null) {
            classifications.add(enumValue(DataClassification.class, raw, where + ".classifications"));
        } else {
            throw new IllegalArgumentException(where + " has no classifications");
        }

        PrivacyNamespace namespace = body.get("namespace") == null
                ? PrivacyNamespace.NONE
                : enumValue(PrivacyNamespace.class, body.get("namespace"), where + ".namespace");
        PrivacyAction action = body.get("action") == null
                ? PrivacyAction.REDACT
                : enumValue(PrivacyAction.class, body.get("action"), where + ".action");

        return new FieldMetadata(fieldName, false, null, classifications, namespace, action,
                "", null, String.class, null);
    }

    private static void rejectUnknownKeys(Set<?> present, Set<String> allowed, String where) {
        for (Object key : present) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalArgumentException(where + " has an unknown key '" + key + "'");
            }
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object raw, String where) {
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown " + type.getSimpleName() + " '" + value + "' at " + where, e);
        }
    }
}
