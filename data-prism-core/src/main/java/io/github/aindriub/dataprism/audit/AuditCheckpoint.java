package io.github.aindriub.dataprism.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/**
 * One external statement of a writer's chain head: "writer {@code instanceId}
 * had reached {@code sequence} with head hash {@code headHash}". Written to an
 * {@link AuditCheckpointSink} held apart from the audit file, so that deleting
 * the audit file's tail or a whole boot can be noticed by comparing the two.
 *
 * <p>{@link Kind#RETENTION_ANCHOR} is reserved for retention (task 102); the
 * verifier does not yet draw any conclusion from it.
 */
public record AuditCheckpoint(Kind kind, String instanceId, long sequence, String headHash, Instant recordedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        ObjectNode node = MAPPER.createObjectNode();
        node.put("kind", kind.name());
        node.put("instanceId", instanceId);
        node.put("sequence", sequence);
        node.put("headHash", headHash);
        node.put("recordedAt", recordedAt.toString());
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inverse of {@link #toJsonLine()}; throws {@link IllegalArgumentException} on anything malformed. */
    public static AuditCheckpoint fromJsonLine(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("checkpoint line is not a JSON object");
            }
            return new AuditCheckpoint(
                    Kind.valueOf(text(node, "kind")),
                    text(node, "instanceId"),
                    node.path("sequence").asLong(-1),
                    text(node, "headHash"),
                    Instant.parse(text(node, "recordedAt")));
        } catch (JsonProcessingException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new IllegalArgumentException("checkpoint line could not be parsed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("checkpoint field missing or not text: " + field);
        }
        return value.asText();
    }
}
