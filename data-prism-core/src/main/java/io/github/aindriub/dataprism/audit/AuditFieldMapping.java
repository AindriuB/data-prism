package io.github.aindriub.dataprism.audit;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where each {@link AuditEvent} field goes in a JSON rendering: a dotted output path per
 * canonical field. The canonical names are the {@code AuditEvent} component names. The mapping
 * is total, so every canonical field is emitted exactly once, and it only renames and reshapes:
 * it never adds a value. The one derived field is the ECS {@code event.outcome}, which
 * {@link #ecs()} reserves a path for and which comes from {@code policyDecision} alone.
 *
 * <p>A path is {@code [A-Za-z_@][A-Za-z0-9_@]*(\.[A-Za-z0-9_@]+)*}; a dot nests. Two fields may
 * not share a path, and one path may not be a prefix segment of another.
 */
public final class AuditFieldMapping {

    public static final String UNKNOWN_FIELD = "UNKNOWN_AUDIT_FIELD";
    public static final String INVALID_PATH = "INVALID_AUDIT_FIELD_PATH";
    public static final String CONFLICT = "AUDIT_FIELD_MAPPING_CONFLICT";

    /** The ECS path of the derived outcome. */
    static final String ECS_OUTCOME_PATH = "event.outcome";

    /** The {@link AuditEvent} component names, in declaration order. */
    public static final List<String> CANONICAL_FIELDS;

    static {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : AuditEvent.class.getRecordComponents()) {
            names.add(component.getName());
        }
        CANONICAL_FIELDS = List.copyOf(names);
    }

    private static final Pattern PATH = Pattern.compile("[A-Za-z_@][A-Za-z0-9_@]*(\\.[A-Za-z0-9_@]+)*");

    private final Map<String, String> paths;
    private final String outcomePath;

    private AuditFieldMapping(Map<String, String> paths, String outcomePath) {
        this.paths = Collections.unmodifiableMap(new LinkedHashMap<>(paths));
        this.outcomePath = outcomePath;
        List<String> all = new ArrayList<>(paths.values());
        if (outcomePath != null) {
            all.add(outcomePath);
        }
        requireNoConflict(all);
    }

    /** Each field under its own name, and no derived field. */
    public static AuditFieldMapping canonical() {
        Map<String, String> paths = new LinkedHashMap<>();
        CANONICAL_FIELDS.forEach(f -> paths.put(f, f));
        return new AuditFieldMapping(paths, null);
    }

    /** Elastic Common Schema names, the rest under {@code dataprism.*}, and the derived {@code event.outcome}. */
    public static AuditFieldMapping ecs() {
        Map<String, String> fixed = Map.of(
                "timestamp", "@timestamp",
                "eventId", "event.id",
                "tool", "event.action",
                "principalId", "user.id",
                "externalCorrelationId", "trace.id");
        Map<String, String> paths = new LinkedHashMap<>();
        for (String field : CANONICAL_FIELDS) {
            paths.put(field, fixed.containsKey(field) ? fixed.get(field) : "dataprism." + snakeCase(field));
        }
        return new AuditFieldMapping(paths, ECS_OUTCOME_PATH);
    }

    /** A copy with the given canonical-to-path overrides applied; this mapping is unchanged. */
    public AuditFieldMapping withOverrides(Map<String, String> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        Map<String, String> next = new LinkedHashMap<>(paths);
        overrides.forEach((field, path) -> {
            if (!paths.containsKey(field)) {
                throw new IllegalArgumentException(UNKNOWN_FIELD + ": '" + field + "' is not an audit field");
            }
            if (path == null || !PATH.matcher(path).matches()) {
                throw new IllegalArgumentException(INVALID_PATH + ": the path for '" + field
                        + "' must match " + PATH.pattern());
            }
            next.put(field, path);
        });
        return new AuditFieldMapping(next, outcomePath);
    }

    /** Canonical field to output path, in canonical order. */
    public Map<String, String> paths() {
        return paths;
    }

    public String pathOf(String field) {
        String path = paths.get(field);
        if (path == null) {
            throw new IllegalArgumentException(UNKNOWN_FIELD + ": '" + field + "' is not an audit field");
        }
        return path;
    }

    /** The path of the derived {@code event.outcome}, present for the ECS preset only. */
    public Optional<String> outcomePath() {
        return Optional.ofNullable(outcomePath);
    }

    /** Throws {@code AUDIT_FIELD_MAPPING_CONFLICT} if two paths are equal or one is a prefix segment of another. */
    static void requireNoConflict(Collection<String> allPaths) {
        List<String> list = List.copyOf(allPaths);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                String a = list.get(i);
                String b = list.get(j);
                if (a.equals(b) || b.startsWith(a + ".") || a.startsWith(b + ".")) {
                    throw new IllegalArgumentException(CONFLICT + ": '" + a + "' and '" + b
                            + "' cannot both be output paths");
                }
            }
        }
    }

    private static String snakeCase(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
