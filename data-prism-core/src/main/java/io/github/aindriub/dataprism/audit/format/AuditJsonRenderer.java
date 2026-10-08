package io.github.aindriub.dataprism.audit.format;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;

import io.github.aindriub.dataprism.audit.AuditEvent;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders an {@link AuditEvent} as one line of JSON under an {@link AuditFieldMapping}.
 *
 * <p>The rendering renames and reshapes the event's own fields. The only values it adds are the
 * operator's {@link AuditRouting} constants and, for a mapping with an outcome path, the
 * {@code event.outcome} derived from {@code policyDecision}. Sets render as sorted arrays, the
 * field dispositions as an object, {@code sequence} and {@code recordVersion} as numbers and the
 * timestamp as {@code Instant.toString()}, the form the hash is computed over.
 *
 * <p>Output is ASCII (non-ASCII characters are written as unicode escapes), so no character that
 * any reader treats as a line break can occur inside a line. Streaming API only:
 * {@code ArchitectureTest} allows exactly one {@code ObjectMapper} in the build.
 */
public final class AuditJsonRenderer {

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .build();

    private AuditJsonRenderer() {
    }

    /**
     * The derived ECS outcome: {@code ALLOW} or {@code ALLOW:...} is {@code success}, an empty
     * decision is {@code unknown}, anything else (including {@code DENY}, {@code DENY:<code>} and
     * a bare refusal code) is {@code failure}.
     */
    public static String outcome(String policyDecision) {
        if (policyDecision == null || policyDecision.isEmpty()) {
            return "unknown";
        }
        if (policyDecision.equals("ALLOW") || policyDecision.startsWith("ALLOW:")) {
            return "success";
        }
        return "failure";
    }

    /** One line of JSON, with no line break inside it. */
    public static String render(AuditEvent event, AuditFieldMapping mapping, AuditRouting routing) {
        Map<String, Object> flat = keyValues(event, mapping, routing);
        Node root = new Node();
        flat.forEach((path, value) -> insert(root, path, value));
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            writeNode(g, root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /**
     * Every output path and its value: the mapped fields, then the routing constants, then the
     * derived outcome. Values are {@code String}, {@code Long}, {@code Integer}, a sorted
     * {@code List} for a set, or a sorted {@code Map} for the field dispositions.
     */
    public static Map<String, Object> keyValues(AuditEvent event, AuditFieldMapping mapping, AuditRouting routing) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(routing, "routing");
        routing.checkAgainst(mapping);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : mapping.paths().entrySet()) {
            out.put(field.getValue(), valueOf(event, field.getKey()));
        }
        out.putAll(routing.entries());
        mapping.outcomePath().ifPresent(path -> out.put(path, outcome(event.policyDecision())));
        return out;
    }

    private static Object valueOf(AuditEvent e, String field) {
        return switch (field) {
            case "eventId" -> e.eventId();
            case "timestamp" -> e.timestamp() == null ? null : e.timestamp().toString();
            case "principalId" -> e.principalId();
            case "clientId" -> e.clientId();
            case "tool" -> e.tool();
            case "entityType" -> e.entityType();
            case "subjectPseudonym" -> e.subjectPseudonym();
            case "parameterFingerprint" -> e.parameterFingerprint();
            case "privacyProfile" -> e.privacyProfile();
            case "scopeId" -> e.scopeId();
            case "purpose" -> e.purpose();
            case "caseId" -> e.caseId();
            case "policyDecision" -> e.policyDecision();
            case "sourceSystems" -> sorted(e.sourceSystems());
            case "rejectedArguments" -> sorted(e.rejectedArguments());
            case "correlationId" -> e.correlationId();
            case "instanceId" -> e.instanceId();
            case "sequence" -> e.sequence();
            case "previousHash" -> e.previousHash();
            case "eventHash" -> e.eventHash();
            case "recordVersion" -> e.recordVersion();
            case "fieldDispositions" -> e.fieldDispositions();
            case "approvalId" -> e.approvalId();
            case "approverId" -> e.approverId();
            case "externalCorrelationId" -> e.externalCorrelationId();
            default -> throw new IllegalStateException("no value for audit field " + field);
        };
    }

    private static List<String> sorted(Collection<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(null);
        return out;
    }

    /** An object built from dotted paths; distinct from a leaf {@code Map}, which is a value. */
    private static final class Node extends LinkedHashMap<String, Object> {
    }

    private static void insert(Node root, String path, Object value) {
        String[] segments = path.split("\\.");
        Node node = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = node.get(segments[i]);
            if (next == null) {
                next = new Node();
                node.put(segments[i], next);
            }
            node = (Node) next;
        }
        node.put(segments[segments.length - 1], value);
    }

    private static void writeNode(JsonGenerator g, Node node) throws IOException {
        g.writeStartObject();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            g.writeFieldName(entry.getKey());
            writeValue(g, entry.getValue());
        }
        g.writeEndObject();
    }

    private static void writeValue(JsonGenerator g, Object value) throws IOException {
        if (value == null) {
            g.writeNull();
        } else if (value instanceof Node n) {
            writeNode(g, n);
        } else if (value instanceof String s) {
            g.writeString(s);
        } else if (value instanceof Long l) {
            g.writeNumber(l);
        } else if (value instanceof Integer i) {
            g.writeNumber(i);
        } else if (value instanceof Collection<?> c) {
            g.writeStartArray();
            for (Object element : c) {
                writeValue(g, element);
            }
            g.writeEndArray();
        } else if (value instanceof Map<?, ?> m) {
            g.writeStartObject();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                g.writeFieldName(String.valueOf(entry.getKey()));
                writeValue(g, entry.getValue());
            }
            g.writeEndObject();
        } else {
            throw new IllegalStateException("unrenderable audit value of type " + value.getClass().getName());
        }
    }
}
