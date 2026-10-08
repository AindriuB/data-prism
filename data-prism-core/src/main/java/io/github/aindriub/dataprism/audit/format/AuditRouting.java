package io.github.aindriub.dataprism.audit.format;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Operator-configured constants that route a rendered event to its own index: {@code event.dataset}
 * and the three {@code data_stream.*} fields. Each is optional ({@code null} leaves it out) and
 * follows the Elastic data stream naming rules: type {@code logs}, dataset {@code [a-z0-9_.]} and
 * namespace {@code [a-z0-9_]}, each 1 to 100 characters. These are the only values a rendering
 * adds that do not come from the event.
 */
public record AuditRouting(String eventDataset, String dataStreamType, String dataStreamDataset,
                           String dataStreamNamespace) {

    public static final String INVALID_VALUE = "INVALID_AUDIT_ROUTING_VALUE";

    private static final Pattern DATASET = Pattern.compile("[a-z0-9_.]{1,100}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_]{1,100}");

    public AuditRouting {
        require("eventDataset", eventDataset, DATASET);
        require("dataStreamType", dataStreamType, Pattern.compile("logs"));
        require("dataStreamDataset", dataStreamDataset, DATASET);
        require("dataStreamNamespace", dataStreamNamespace, NAMESPACE);
    }

    /** No routing constants. */
    public static AuditRouting none() {
        return new AuditRouting(null, null, null, null);
    }

    private static void require(String name, String value, Pattern rule) {
        if (value != null && !rule.matcher(value).matches()) {
            throw new IllegalArgumentException(INVALID_VALUE + ": " + name + " must match " + rule.pattern());
        }
    }

    /** The output path and constant of each routing value that is set, in a fixed order. */
    java.util.Map<String, String> entries() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (eventDataset != null) {
            out.put("event.dataset", eventDataset);
        }
        if (dataStreamType != null) {
            out.put("data_stream.type", dataStreamType);
        }
        if (dataStreamDataset != null) {
            out.put("data_stream.dataset", dataStreamDataset);
        }
        if (dataStreamNamespace != null) {
            out.put("data_stream.namespace", dataStreamNamespace);
        }
        return out;
    }

    /** Throws {@code AUDIT_FIELD_MAPPING_CONFLICT} if a set routing path collides with a mapped path. */
    public void checkAgainst(AuditFieldMapping mapping) {
        List<String> all = new ArrayList<>(mapping.paths().values());
        mapping.outcomePath().ifPresent(all::add);
        all.addAll(entries().keySet());
        AuditFieldMapping.requireNoConflict(all);
    }
}
