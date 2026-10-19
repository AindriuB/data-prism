package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.model.ConsistencyFinding;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** Mapper-free bounds for the compact context body, not its transport envelope. */
final class ResponseSize {
    private ResponseSize() { }

    static long bytes(ContextResponse response) {
        long sources = 2;
        boolean first = true;
        for (var entry : response.sources().entrySet()) {
            SourceResult source = entry.getValue();
            long value = object(List.of("status"), List.of(string(source.status())));
            if (source.entity() != null) {
                value = object(List.of("status", "entity"), List.of(string(source.status()), tree(source.entity())));
            }
            sources = add(sources, (first ? 0 : 1) + string(entry.getKey()) + 1);
            sources = add(sources, value);
            first = false;
        }
        long findings = 2;
        first = true;
        for (ConsistencyFinding finding : response.findings()) {
            long groups = 2;
            boolean firstGroup = true;
            for (List<String> group : finding.agreementGroups()) {
                long names = 2;
                boolean firstName = true;
                for (String name : group) {
                    names = add(names, (firstName ? 0 : 1) + string(name));
                    firstName = false;
                }
                groups = add(groups, (firstGroup ? 0 : 1) + names);
                firstGroup = false;
            }
            long value = object(List.of("field", "namespace", "kind", "agreementGroups", "distinctValues", "detail"),
                    List.of(string(finding.field()), string(finding.namespace() == null ? null : finding.namespace().name()),
                            string(finding.kind() == null ? null : finding.kind().name()), groups,
                            (long) Integer.toString(finding.distinctValues()).length(), string(finding.detail())));
            findings = add(findings, (first ? 0 : 1) + value);
            first = false;
        }
        return object(List.of("entityType", "subject", "sources", "findings"),
                List.of(string(response.entityType()), string(response.subject()), sources, findings));
    }

    private static long object(List<String> keys, List<Long> values) {
        long bytes = 2;
        for (int i = 0; i < keys.size(); i++) {
            bytes = add(bytes, (i == 0 ? 0 : 1) + string(keys.get(i)) + 1);
            bytes = add(bytes, values.get(i));
        }
        return bytes;
    }

    /** Root records plus object elements of arrays, at any depth; scalar elements do not count. */
    static long records(ContextResponse response) {
        long records = 0;
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        for (SourceResult source : response.sources().values()) {
            if (source.entity() != null) {
                records = add(records, 1);
                pending.add(source.entity());
            }
        }
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (node.isContainer()) {
                for (JsonNode child : node) {
                    if (node.isArray() && child.isObject()) records = add(records, 1);
                    if (child.isContainer()) pending.add(child);
                }
            }
        }
        return records;
    }

    /**
     * Exact for ordinary JSON scalars; surrogate code units reserve six bytes apiece.
     * For supported JSON trees the result is at most three times the compact UTF-8 size.
     */
    static long tree(JsonNode root) {
        long bytes = 0;
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (node.isObject()) {
                bytes = add(bytes, 2L + Math.max(0, node.size() - 1));
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    bytes = add(bytes, string(field.getKey()) + 1);
                    pending.add(field.getValue());
                }
            } else if (node.isArray()) {
                bytes = add(bytes, 2L + Math.max(0, node.size() - 1));
                node.forEach(pending::add);
            } else if (node.isString()) {
                bytes = add(bytes, string(node.asString()));
            } else if (node.isNull() || node.isMissingNode()) {
                bytes = add(bytes, 4);
            } else if (node.isBoolean()) {
                bytes = add(bytes, node.asBoolean() ? 4 : 5);
            } else if (node.isNumber()) {
                String number = node.asString();
                bytes = add(bytes, number.length());
                // Non-finite floating point values are quoted by the output mapper.
                if (number.equals("NaN") || number.contains("Infinity")) bytes = add(bytes, 2);
            } else if (node.isBinary()) {
                bytes = add(bytes, 2 + 4 * ((node.binaryValue().length + 2L) / 3));
            } else {
                // Opaque nodes can have custom serialisation: never guess a smaller bound.
                return Long.MAX_VALUE;
            }
        }
        return bytes;
    }

    private static long string(String value) {
        if (value == null) return 4;
        long bytes = 2;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            bytes += switch (c) {
                case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                default -> c < 0x20 || Character.isSurrogate(c) ? 6 : c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            };
        }
        return bytes;
    }

    private static long add(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
