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
import java.util.LinkedHashSet;
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
            Set.of("base-url", "path", "timeout", "model-version", "subject-json-path", "fields",
                    "nested-catalogues");
    private static final Set<String> IDENTIFIER_FIELD_KEYS = Set.of("identifier");
    private static final Set<String> NON_SENSITIVE_FIELD_KEYS = Set.of("nonSensitive");
    private static final Set<String> SENSITIVE_FIELD_KEYS =
            Set.of("classifications", "namespace", "action");
    private static final Set<String> NESTED_FIELD_KEYS = Set.of("nested");

    /**
     * The fixed prefix a {@code nested:} field's {@code FieldMetadata.nonSensitiveReason()}
     * always carries, followed by the catalogue's own name. {@link
     * ConfiguredJsonFieldMetadataResolver} reads this back to rebuild its
     * {@code Class}-token index without {@link ConfiguredJsonSource} needing a
     * second public component to carry that index.
     */
    static final String NESTED_FIELD_REASON_PREFIX = "nested catalogue ";

    // Reuses RestSources.YAML rather than constructing a second ObjectMapper:
    // see that field's Javadoc.
    private static final ObjectMapper YAML = RestSources.YAML;

    private ConfiguredJsonSources() {
    }

    public static ConfiguredJsonSourcesConfig fromYaml(InputStream in) {
        return fromYaml(in, false);
    }

    /**
     * @param fixtureDevelopment mirrors {@code dataprism.transport.fixture-development},
     *                           the one narrow exception {@code
     *                           DataPrismProperties.trustedUri} grants a Java-first
     *                           source's {@code dataprism.sources.<name>.base-url}: a
     *                           plaintext {@code http} base URL is permitted only for
     *                           a loopback host ({@code localhost} or {@code 127.0.0.1})
     *                           and only when this is set. Every other base URL,
     *                           regardless of this flag, must be {@code https}. False
     *                           is the default every caller except a fixture harness
     *                           wants; the standalone server distribution never sets
     *                           it, since fixture development is stdio-only and
     *                           refused outright for an HTTP-transport deployment.
     */
    @SuppressWarnings("unchecked")
    public static ConfiguredJsonSourcesConfig fromYaml(InputStream in, boolean fixtureDevelopment) {
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

        Map<String, ConfiguredJsonSource> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (name.isBlank()) {
                throw new IllegalArgumentException("a json-sources entry has a blank name");
            }
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new IllegalArgumentException("json source " + name + " is not a mapping");
            }
            out.put(name, source(name, (Map<String, Object>) body, fixtureDevelopment, tls != null));
        }
        return new ConfiguredJsonSourcesConfig(Map.copyOf(out), tls);
    }

    @SuppressWarnings("unchecked")
    private static ConfiguredJsonSource source(String name, Map<String, Object> body,
                                               boolean fixtureDevelopment, boolean tlsConfigured) {
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

        URI baseUri;
        try {
            baseUri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("json source " + name + " has an unparseable base-url", e);
        }
        // The same gate a Java-first source's dataprism.sources.<name>.base-url must
        // clear (DataPrismProperties.trustedUri), reimplemented here rather than
        // depended on. Unconditional, regardless of the fixture-development
        // exception below: embedded credentials, a query string or a fragment
        // have no legitimate place in a server-owned base URL, and RestSource's
        // own validation does not check for them.
        if (baseUri.getUserInfo() != null || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("json source " + name
                    + " base-url must not carry user-info, a query string or a fragment: " + baseUri);
        }
        // A plaintext base URL is refused unless this is fixture development and
        // the host is loopback, and a tls: block always closes even that
        // exception, since configuring mTLS material for a plaintext connection
        // makes no sense.
        boolean permitPlaintextLoopback = fixtureDevelopment && !tlsConfigured && isLoopback(baseUri.getHost());
        boolean requireHttps = !permitPlaintextLoopback;

        RestSource transport;
        try {
            transport = new RestSource(name, baseUri, path,
                    Duration.parse(String.valueOf(timeoutRaw)), requireHttps);
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

        Map<String, Map<String, FieldMetadata>> nestedCatalogues =
                nestedCatalogues(name, body.get("nested-catalogues"));

        Object fieldsNode = body.get("fields");
        if (!(fieldsNode instanceof Map<?, ?> fieldsMap) || fieldsMap.isEmpty()) {
            throw new IllegalArgumentException("json source " + name + " has no fields catalogue;"
                    + " every property this source may ever emit, including its subject field,"
                    + " must be named here");
        }

        Set<String> referencedNestedCatalogues = new LinkedHashSet<>();
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
            fields.put(fieldName, field(name, fieldName, (Map<String, Object>) fieldBody, true,
                    nestedCatalogues.keySet(), referencedNestedCatalogues));
        }

        for (String catalogueName : nestedCatalogues.keySet()) {
            if (!referencedNestedCatalogues.contains(catalogueName)) {
                throw new IllegalArgumentException("json source " + name + " nested catalogue '"
                        + catalogueName + "' is declared but referenced by no field's `nested:`");
            }
        }

        // Tokens are assigned last, only once every field -- root and nested --
        // has already passed every other shape and reference check. A config
        // that is going to be refused never reaches here, so a rejected config
        // never consumes a pool slot for a catalogue it would not have kept.
        fields = assignNestedTokens(name, fields, nestedCatalogues);

        return new ConfiguredJsonSource(transport, modelVersion, subjectJsonPath, fields, nestedCatalogues);
    }

    /**
     * Replaces each nested-pointing root field's placeholder {@code
     * FieldMetadata} with one carrying a real token from {@link
     * ConfiguredJsonNestedCatalogueTokens}, one per declared catalogue name, in
     * declaration order.
     */
    private static Map<String, FieldMetadata> assignNestedTokens(String sourceName,
            Map<String, FieldMetadata> fields, Map<String, Map<String, FieldMetadata>> nestedCatalogues) {
        if (nestedCatalogues.isEmpty()) {
            return fields;
        }
        Map<String, Class<?>> tokenByCatalogueName = new LinkedHashMap<>();
        int ordinal = 0;
        for (String catalogueName : nestedCatalogues.keySet()) {
            tokenByCatalogueName.put(catalogueName, ConfiguredJsonNestedCatalogueTokens.mint(sourceName, ordinal));
            ordinal++;
        }

        Map<String, FieldMetadata> out = new LinkedHashMap<>();
        for (Map.Entry<String, FieldMetadata> entry : fields.entrySet()) {
            FieldMetadata md = entry.getValue();
            String reason = md.nonSensitiveReason();
            if (reason != null && reason.startsWith(NESTED_FIELD_REASON_PREFIX)) {
                String catalogueName = reason.substring(NESTED_FIELD_REASON_PREFIX.length());
                Class<?> token = tokenByCatalogueName.get(catalogueName);
                md = new FieldMetadata(md.fieldName(), false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", reason, token, token);
            }
            out.put(entry.getKey(), md);
        }
        return out;
    }

    /**
     * Parses the top-level {@code nested-catalogues:} map, if the source
     * declares one. Each entry is itself a flat {@code fields:}-shaped
     * catalogue using the existing three leaf shapes; {@code nested:} and
     * {@code identifier: true} are refused inside one, since nesting is
     * exactly one level deep and a nested catalogue carries no identifier of
     * its own. Mints no tokens: that happens once every field in this source
     * has been validated, see {@link #assignNestedTokens}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, FieldMetadata>> nestedCatalogues(String sourceName, Object rawNode) {
        if (rawNode == null) {
            return Map.of();
        }
        if (!(rawNode instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("json source " + sourceName + " nested-catalogues is not a mapping");
        }
        Map<String, Map<String, FieldMetadata>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String catalogueName = String.valueOf(entry.getKey());
            if (!FIELD_NAME.matcher(catalogueName).matches()) {
                throw new IllegalArgumentException("json source " + sourceName + " nested catalogue name '"
                        + catalogueName + "' is not a bare property name");
            }
            if (!(entry.getValue() instanceof Map<?, ?> catalogueMap) || catalogueMap.isEmpty()) {
                throw new IllegalArgumentException("json source " + sourceName + " nested catalogue '"
                        + catalogueName + "' has no fields; every property it may ever carry must be named here");
            }

            Map<String, FieldMetadata> catalogueFields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> fieldEntry : catalogueMap.entrySet()) {
                String fieldName = String.valueOf(fieldEntry.getKey());
                if (!FIELD_NAME.matcher(fieldName).matches()) {
                    throw new IllegalArgumentException("json source " + sourceName + " nested catalogue '"
                            + catalogueName + "' field '" + fieldName + "' is not a bare property name");
                }
                if (!(fieldEntry.getValue() instanceof Map<?, ?> fieldBody)) {
                    throw new IllegalArgumentException("json source " + sourceName + " nested catalogue '"
                            + catalogueName + "' field " + fieldName + " is not a mapping");
                }
                catalogueFields.put(fieldName,
                        field(sourceName, fieldName, (Map<String, Object>) fieldBody, false, null, null));
            }

            out.put(catalogueName, Map.copyOf(catalogueFields));
        }
        return Map.copyOf(out);
    }

    /**
     * @param topLevel   whether this field is being parsed as part of a
     *                   source's root {@code fields:} catalogue (where {@code
     *                   nested:} and {@code identifier: true} are legal) or
     *                   as part of a nested catalogue's own entries (where
     *                   neither is, since nesting goes exactly one level and
     *                   a nested catalogue has no identifier of its own)
     * @param declaredNestedCatalogueNames only consulted when {@code
     *                   topLevel}; the set of names legally usable after
     *                   {@code nested:} for this source
     * @param referencedNestedCatalogues only consulted when {@code
     *                   topLevel}; a {@code nested:} field records its
     *                   catalogue name here, so the caller can refuse a
     *                   catalogue declared but never referenced
     */
    private static FieldMetadata field(String sourceName, String fieldName, Map<String, Object> body,
                                       boolean topLevel, Set<String> declaredNestedCatalogueNames,
                                       Set<String> referencedNestedCatalogues) {
        String where = "json source " + sourceName + " field " + fieldName;
        boolean identifier = body.containsKey("identifier");
        boolean nonSensitive = body.containsKey("nonSensitive");
        boolean sensitive = body.containsKey("classifications") || body.containsKey("namespace")
                || body.containsKey("action");
        boolean nested = body.containsKey("nested");

        if (nested && !topLevel) {
            throw new IllegalArgumentException(where + " states `nested:` inside a nested catalogue;"
                    + " nesting is exactly one level deep, so a nested catalogue's own entries must be scalar");
        }
        if (identifier && !topLevel) {
            throw new IllegalArgumentException(where
                    + " marks identifier: true inside a nested catalogue; a nested catalogue carries"
                    + " no identifier of its own, and inherits its subject from the enclosing record");
        }

        if ((identifier ? 1 : 0) + (nonSensitive ? 1 : 0) + (sensitive ? 1 : 0) + (nested ? 1 : 0) != 1) {
            throw new IllegalArgumentException(where + " must state exactly one of:"
                    + " `identifier: true`, `nonSensitive: <reason>`,"
                    + " classifications (with optional namespace/action), or `nested: <name>`");
        }

        if (nested) {
            rejectUnknownKeys(body.keySet(), NESTED_FIELD_KEYS, where);
            String catalogueName = String.valueOf(body.get("nested"));
            if (!FIELD_NAME.matcher(catalogueName).matches()) {
                throw new IllegalArgumentException(where + " nested catalogue name '" + catalogueName
                        + "' is not a bare property name");
            }
            if (!declaredNestedCatalogueNames.contains(catalogueName)) {
                throw new IllegalArgumentException(where + " nested: '" + catalogueName
                        + "' does not name an entry declared under this source's nested-catalogues");
            }
            referencedNestedCatalogues.add(catalogueName);
            // No token yet: minted only once every field in this source has been
            // validated (see assignNestedTokens). This placeholder is patched with
            // the real token before ConfiguredJsonSource is ever constructed.
            return new FieldMetadata(fieldName, false, null, List.of(), PrivacyNamespace.NONE, null, "",
                    NESTED_FIELD_REASON_PREFIX + catalogueName, null, null);
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

    /** Exactly the two hosts {@code DataPrismProperties.trustedUri} treats as local. */
    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
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
