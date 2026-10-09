package io.github.aindriub.dataprism.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the generated Spring configuration metadata and {@code docs/configuration.md} in step,
 * exactly in both directions.
 *
 * <p>Metadata is {@code META-INF/spring-configuration-metadata.json} (generated from the
 * {@code *Properties} classes) merged with {@code META-INF/additional-spring-configuration-metadata.json},
 * both read from the test classpath.
 *
 * <p>A documented key is a backticked token made only of {@code dataprism} and dot-separated
 * lower-case/hyphen segments, or {@code <placeholder>} segments, anywhere in the document; plus the
 * first-column key of a table whose first header cell is {@code Property}. A first-column key that
 * does not start with {@code dataprism.} is relative to the nearest preceding heading of the form
 * {@code ### `dataprism.x`}. A trailing {@code .<name>} is the entry of a map property, so it names
 * the map itself; a placeholder elsewhere becomes {@code *}. A key that is only a prefix of
 * metadata keys is a group and is accepted.
 *
 * <p>Failures: a documented key absent from the metadata, a metadata key absent from the docs, a
 * documented default that differs from the metadata default, and a {@code dataprism.*} metadata
 * property without a description. Defaults are compared only for table rows with a
 * {@code Default} column. A cell starting with {@code unset}, {@code none} or {@code empty} means
 * no default. Otherwise the cell, stripped of backticks, is compared by the property's type:
 * {@code Duration} (ISO {@code PT5M} or simple {@code 5m}), {@code Period} (ISO), booleans and
 * integers by value, anything else as text.
 *
 * <p>Each exception is a line {@code category|key|reason} in {@code configuration-metadata-gaps.txt}.
 * Categories: {@code outside-binding} (documented, not in the metadata),
 * {@code not-a-property} (a backticked name that is not a property), {@code internal} (in the
 * metadata, deliberately undocumented) and {@code default-not-inferred} (the default is not
 * comparable). An entry that suppresses nothing fails the test, so the list cannot rot.
 */
class ConfigurationMetadataDocumentedTest {
    private static final Pattern KEY = Pattern.compile(
            "`(dataprism(?:\\.(?:[a-z][a-z0-9-]*|<[a-z-]+>))+)`");
    private static final Pattern HEADING = Pattern.compile("^#{2,4} `(dataprism(?:\\.[a-z][a-z0-9-]*)+)`\\s*$");
    private static final Pattern SINGLE_TICK = Pattern.compile("^`([^`]+)`$");

    private record Prop(String type, String defaultValue, String description) {}

    private record DocKey(String key, boolean tableRow, boolean hasDefaultColumn, String defaultCell) {}

    @Test
    void metadataAndDocsAgree() throws IOException {
        Map<String, Prop> metadata = loadMetadata();
        Map<String, Set<String>> gaps = loadGaps();
        Set<String> used = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();

        for (Map.Entry<String, Prop> e : metadata.entrySet()) {
            if (e.getKey().startsWith("dataprism.")
                    && (e.getValue().description() == null || e.getValue().description().isBlank())) {
                failures.add("no description (add field Javadoc): " + e.getKey());
            }
        }

        Map<String, DocKey> docs = loadDocKeys();
        Set<String> documented = new TreeSet<>();
        for (DocKey d : docs.values()) {
            documented.add(d.key());
            if (metadata.containsKey(d.key()) || isGroup(d.key(), metadata)) {
                continue;
            }
            if (!gap(gaps, used, "outside-binding", d.key()) && !gap(gaps, used, "not-a-property", d.key())) {
                failures.add("documented but missing from the metadata: " + d.key());
            }
        }
        for (String key : metadata.keySet()) {
            if (key.startsWith("dataprism.") && !documented.contains(key)
                    && !gap(gaps, used, "internal", key)) {
                failures.add("in the metadata but missing from docs/configuration.md: " + key);
            }
        }
        for (DocKey d : docs.values()) {
            Prop p = metadata.get(d.key());
            if (p == null || !d.hasDefaultColumn()) {
                continue;
            }
            String documentedDefault = noDefault(d.defaultCell()) ? null : strip(d.defaultCell());
            String reason = differ(p.type(), documentedDefault, p.defaultValue());
            if (reason != null && !gap(gaps, used, "default-not-inferred", d.key())) {
                failures.add("default differs for " + d.key() + ": docs " + show(documentedDefault)
                        + ", metadata " + show(p.defaultValue()) + " (" + reason + ")");
            }
        }
        for (Map.Entry<String, Set<String>> g : gaps.entrySet()) {
            for (String key : g.getValue()) {
                if (!used.contains(g.getKey() + "|" + key)) {
                    failures.add("unused allow-list entry (remove it): " + g.getKey() + "|" + key);
                }
            }
        }
        assertThat(failures).as("docs/configuration.md and the configuration metadata").isEmpty();
    }

    private static boolean gap(Map<String, Set<String>> gaps, Set<String> used, String category, String key) {
        if (gaps.getOrDefault(category, Set.of()).contains(key)) {
            used.add(category + "|" + key);
            return true;
        }
        return false;
    }

    private static boolean isGroup(String key, Map<String, Prop> metadata) {
        return metadata.keySet().stream().anyMatch(k -> k.startsWith(key + "."));
    }

    private static String show(String v) {
        return v == null ? "none" : "'" + v + "'";
    }

    private static boolean noDefault(String cell) {
        String c = strip(cell).toLowerCase(java.util.Locale.ROOT);
        return c.equals("unset") || c.startsWith("unset ") || c.equals("none") || c.equals("empty");
    }

    private static String strip(String cell) {
        return cell.replace("`", "").trim();
    }

    /** Returns null when equal, else a short reason. */
    private static String differ(String type, String documented, String actual) {
        if (documented == null || actual == null) {
            return documented == actual ? null : "one side has no default";
        }
        try {
            switch (type) {
                case "java.time.Duration":
                    return duration(documented).equals(duration(actual)) ? null : "different durations";
                case "java.time.Period":
                    return Period.parse(documented).equals(Period.parse(actual)) ? null : "different periods";
                case "java.lang.Boolean":
                    return documented.equalsIgnoreCase(actual) ? null : "different booleans";
                case "java.lang.Integer":
                    return Integer.parseInt(documented) == Integer.parseInt(actual) ? null : "different integers";
                default:
                    return documented.equals(actual) ? null : "different text";
            }
        } catch (RuntimeException unparseable) {
            return "documented value not parseable as " + type;
        }
    }

    private static Duration duration(String v) {
        if (v.startsWith("P") || v.startsWith("p")) {
            return Duration.parse(v);
        }
        Matcher m = Pattern.compile("(\\d+)(ns|us|ms|s|m|h|d)?").matcher(v);
        if (!m.matches()) {
            throw new IllegalArgumentException(v);
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "ms" : m.group(2);
        return switch (unit) {
            case "ns" -> Duration.ofNanos(n);
            case "us" -> Duration.ofNanos(n * 1000);
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    private static String normalise(String key) {
        List<String> parts = new ArrayList<>(List.of(key.split("\\.")));
        if (parts.get(parts.size() - 1).startsWith("<")) {
            parts.remove(parts.size() - 1);
        }
        parts.replaceAll(p -> p.startsWith("<") ? "*" : p);
        return String.join(".", parts);
    }

    private static Map<String, DocKey> loadDocKeys() throws IOException {
        Path doc = Path.of("..", "docs", "configuration.md");
        List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
        Map<String, DocKey> out = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = KEY.matcher(line);
            while (m.find()) {
                String k = normalise(m.group(1));
                out.putIfAbsent(k, new DocKey(k, false, false, null));
            }
        }
        String heading = null;
        int defaultColumn = -1;
        boolean inPropertyTable = false;
        for (String line : lines) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                heading = h.group(1);
            }
            if (!line.startsWith("|")) {
                inPropertyTable = false;
                defaultColumn = -1;
                continue;
            }
            List<String> cells = cells(line);
            if (cells.get(0).equals("Property")) {
                inPropertyTable = true;
                defaultColumn = cells.indexOf("Default");
                continue;
            }
            if (!inPropertyTable || cells.get(0).startsWith("---")) {
                continue;
            }
            Matcher t = SINGLE_TICK.matcher(cells.get(0));
            if (!t.matches()) {
                continue;
            }
            String raw = t.group(1);
            String full = raw.startsWith("dataprism.") ? raw : null;
            if (full == null) {
                assertThat(heading).as("relative key '" + raw + "' needs a preceding dataprism.* heading").isNotNull();
                full = heading + "." + raw;
            }
            String key = normalise(full);
            String cell = defaultColumn >= 0 && defaultColumn < cells.size() ? cells.get(defaultColumn) : null;
            DocKey previous = out.put(key, new DocKey(key, true, cell != null, cell));
            assertThat(previous == null || !previous.tableRow())
                    .as("key documented in two property tables (keep one row): " + key).isTrue();
        }
        return out;
    }

    private static List<String> cells(String line) {
        String body = line.strip();
        body = body.substring(1, body.endsWith("|") ? body.length() - 1 : body.length());
        List<String> out = new ArrayList<>();
        for (String c : body.split("(?<!\\\\)\\|", -1)) {
            out.add(c.trim());
        }
        return out;
    }

    private static Map<String, Prop> loadMetadata() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        Map<String, Prop> out = new TreeMap<>();
        for (String name : new String[] {
            "META-INF/spring-configuration-metadata.json", "META-INF/additional-spring-configuration-metadata.json"}) {
            Enumeration<java.net.URL> urls = ConfigurationMetadataDocumentedTest.class.getClassLoader().getResources(name);
            assertThat(urls.hasMoreElements()).as(name + " on the test classpath").isTrue();
            while (urls.hasMoreElements()) {
                try (InputStream in = urls.nextElement().openStream()) {
                    JsonNode root = mapper.readTree(in);
                    for (JsonNode p : root.path("properties")) {
                        String key = p.path("name").asString();
                        Prop old = out.get(key);
                        out.put(key, new Prop(
                                text(p, "type", old == null ? null : old.type()),
                                text(p, "defaultValue", old == null ? null : old.defaultValue()),
                                text(p, "description", old == null ? null : old.description())));
                    }
                }
            }
        }
        return out;
    }

    private static String text(JsonNode p, String field, String fallback) {
        JsonNode n = p.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return fallback;
        }
        if (n.isArray()) {
            List<String> items = new ArrayList<>();
            n.forEach(i -> items.add(i.asString()));
            return items.isEmpty() ? null : String.join(",", items);
        }
        return n.asString();
    }

    private static Map<String, Set<String>> loadGaps() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        try (InputStream in = ConfigurationMetadataDocumentedTest.class.getResourceAsStream("/configuration-metadata-gaps.txt")) {
            assertThat(in).as("configuration-metadata-gaps.txt").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", 3);
                assertThat(f).as("category|key|reason: " + line).hasSize(3);
                assertThat(f[2].isBlank()).as("a reason is required: " + line).isFalse();
                assertThat(out.computeIfAbsent(f[0].trim(), c -> new LinkedHashSet<>()).add(f[1].trim()))
                        .as("duplicate entry: " + line).isTrue();
            }
        }
        return out;
    }
}
