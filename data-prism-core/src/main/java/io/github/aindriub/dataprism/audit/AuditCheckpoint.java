package io.github.aindriub.dataprism.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One external statement of a writer's chain head: "writer {@code instanceId}
 * had reached {@code sequence} with head hash {@code headHash}". Written to an
 * {@link AuditCheckpointSink} held apart from the audit file, so that deleting
 * the audit file's tail or a whole boot can be noticed by comparing the two.
 *
 * <p>{@link Kind#RETENTION_ANCHOR} is written by {@link io.github.aindriub.dataprism.audit.retention.AuditRetention} for each
 * writer's last record in a segment it is about to delete; the verifier accepts
 * a chain that starts right after one. An anchor also carries {@code segmentDate}, the UTC
 * date of the purged segment it covers, so the verifier can refuse an anchor that claims to
 * cover a segment too recent to have been legitimately purged. {@code segmentDate} is
 * {@code null} for every other kind and for anchors written before the field existed; such an
 * anchor covers nothing.
 *
 * <p>{@link Kind#CRASH_LINE_ACCEPTED} records an operator's confirmation of one unhashed audit line (the verifier
 * CLI's {@code accept-crash-line}). {@code headHash} holds the SHA-256 of the line's bytes, and {@code segmentName}
 * and {@code byteOffset} say where the line is. {@code bootWaived} is {@code true} only when the operator waived
 * the check that the store holds a {@code BOOT} for the line's instance. These three fields exist for that kind
 * only.
 */
public record AuditCheckpoint(Kind kind, String instanceId, long sequence, String headHash, Instant recordedAt,
                              LocalDate segmentDate, String segmentName, Long byteOffset, boolean bootWaived) {

    // Streaming API only: no mapper is built here (ArchitectureTest allows only the designated mapper classes).
    private static final JsonFactory JSON = JsonFactory.builder().build();

    public enum Kind { BOOT, PERIODIC, SHUTDOWN, RETENTION_ANCHOR, CRASH_LINE_ACCEPTED }

    /** A checkpoint with no segment name, offset or waiver: every kind but {@link Kind#CRASH_LINE_ACCEPTED}. */
    public AuditCheckpoint(Kind kind, String instanceId, long sequence, String headHash, Instant recordedAt,
                           LocalDate segmentDate) {
        this(kind, instanceId, sequence, headHash, recordedAt, segmentDate, null, null, false);
    }

    /** A checkpoint with no segment date: every kind but {@link Kind#RETENTION_ANCHOR}, or a legacy anchor. */
    public AuditCheckpoint(Kind kind, String instanceId, long sequence, String headHash, Instant recordedAt) {
        this(kind, instanceId, sequence, headHash, recordedAt, null);
    }

    public AuditCheckpoint {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(headHash, "headHash");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (headHash.isBlank()) {
            throw new IllegalArgumentException("headHash must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        if (kind == Kind.CRASH_LINE_ACCEPTED) {
            if (segmentName == null || segmentName.isBlank() || byteOffset == null || byteOffset < 0
                    || segmentDate == null) {
                throw new IllegalArgumentException(
                        "CRASH_LINE_ACCEPTED needs segmentName, byteOffset and segmentDate");
            }
        } else if (segmentName != null || byteOffset != null || bootWaived) {
            throw new IllegalArgumentException("segmentName, byteOffset and bootWaived belong to CRASH_LINE_ACCEPTED");
        }
    }

    /** One JSON object on one line, with no trailing newline. */
    public String toJsonLine() {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("kind", kind.name());
            g.writeStringProperty("instanceId", instanceId);
            g.writeNumberProperty("sequence", sequence);
            g.writeStringProperty("headHash", headHash);
            g.writeStringProperty("recordedAt", recordedAt.toString());
            if (segmentDate != null) {
                g.writeStringProperty("segmentDate", segmentDate.toString());
            }
            if (segmentName != null) {
                g.writeStringProperty("segmentName", segmentName);
            }
            if (byteOffset != null) {
                g.writeNumberProperty("byteOffset", byteOffset);
            }
            if (bootWaived) {
                g.writeBooleanProperty("bootWaived", true);
            }
            g.writeEndObject();
        }
        return out.toString();
    }

    /** Inverse of {@link #toJsonLine()}; throws {@link IllegalArgumentException} on anything malformed. */
    public static AuditCheckpoint fromJsonLine(String line) {
        Map<String, String> fields = new HashMap<>();
        boolean bootWaived = false;
        try (JsonParser p = JSON.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("checkpoint line is not a JSON object");
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if ("bootWaived".equals(name)) {
                    // Only the literal true: false or a string is invalid, so the line fails closed.
                    if (value != JsonToken.VALUE_TRUE || bootWaived) {
                        throw new IllegalArgumentException("bootWaived must be the literal true, once");
                    }
                    bootWaived = true;
                    continue;
                }
                if (value != JsonToken.VALUE_STRING && value != JsonToken.VALUE_NUMBER_INT) {
                    throw new IllegalArgumentException("checkpoint field has an unsupported type: " + name);
                }
                if (fields.put(name, p.getString()) != null) {
                    throw new IllegalArgumentException("checkpoint field repeated: " + name);
                }
            }
            if (p.currentToken() != JsonToken.END_OBJECT || p.nextToken() != null) {
                throw new IllegalArgumentException("checkpoint line is not a single JSON object");
            }
            return new AuditCheckpoint(
                    Kind.valueOf(required(fields, "kind")),
                    required(fields, "instanceId"),
                    Long.parseLong(required(fields, "sequence")),
                    required(fields, "headHash"),
                    Instant.parse(required(fields, "recordedAt")),
                    fields.containsKey("segmentDate") ? LocalDate.parse(fields.get("segmentDate")) : null,
                    fields.get("segmentName"),
                    fields.containsKey("byteOffset") ? Long.valueOf(fields.get("byteOffset")) : null,
                    bootWaived);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("checkpoint line could not be parsed: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("checkpoint line could not be parsed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("checkpoint field missing: " + name);
        }
        return value;
    }
}
