package io.github.aindriub.dataprism.audit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One external statement of a writer's chain head: "writer {@code instanceId}
 * had reached {@code sequence} with head hash {@code headHash}". Written to an
 * {@link AuditCheckpointSink} held apart from the audit file, so that deleting
 * the audit file's tail or a whole boot can be noticed by comparing the two.
 *
 * <p>{@link Kind#RETENTION_ANCHOR} is written by {@link AuditRetention} for each
 * writer's last record in a segment it is about to delete; the verifier accepts
 * a chain that starts right after one.
 */
public record AuditCheckpoint(Kind kind, String instanceId, long sequence, String headHash, Instant recordedAt) {

    // Streaming API only: ArchitectureTest allows exactly one ObjectMapper in the build.
    private static final JsonFactory JSON = new JsonFactory();

    public enum Kind { BOOT, PERIODIC, SHUTDOWN, RETENTION_ANCHOR }

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
    }

    /** One JSON object on one line, with no trailing newline. */
    public String toJsonLine() {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("kind", kind.name());
            g.writeStringField("instanceId", instanceId);
            g.writeNumberField("sequence", sequence);
            g.writeStringField("headHash", headHash);
            g.writeStringField("recordedAt", recordedAt.toString());
            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** Inverse of {@link #toJsonLine()}; throws {@link IllegalArgumentException} on anything malformed. */
    public static AuditCheckpoint fromJsonLine(String line) {
        Map<String, String> fields = new HashMap<>();
        try (JsonParser p = JSON.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("checkpoint line is not a JSON object");
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if (value != JsonToken.VALUE_STRING && value != JsonToken.VALUE_NUMBER_INT) {
                    throw new IllegalArgumentException("checkpoint field has an unsupported type: " + name);
                }
                if (fields.put(name, p.getText()) != null) {
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
                    Instant.parse(required(fields, "recordedAt")));
        } catch (IOException e) {
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
