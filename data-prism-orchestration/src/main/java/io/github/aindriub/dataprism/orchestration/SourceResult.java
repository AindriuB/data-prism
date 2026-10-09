package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * One source's entry in {@link ContextResponse#sources()}: how it answered and, only when it did, what it said.
 *
 * <p>{@code entity} is that source's own scrubbed record. It is {@code null} for every status other than
 * {@code ANSWERED}, and for a {@code REFUSED} source in particular nothing of the record survives: no field,
 * no partial tree, no count. A client treats a status it does not recognise as "not answered".
 *
 * @param status one of {@link SourceOutcome.Status}'s names; a {@code String} so that a client reading a
 *               later release's code does not fail
 * @param entity the scrubbed record, or {@code null}
 */
public record SourceResult(String status, @JsonInclude(JsonInclude.Include.NON_NULL) ObjectNode entity) {

    public SourceResult {
        Objects.requireNonNull(status, "status");
        if (entity != null && !SourceOutcome.Status.ANSWERED.name().equals(status)) {
            throw new IllegalArgumentException("only an ANSWERED source carries an entity");
        }
    }

    /** A source that did not answer, or whose answer was withheld. */
    public static SourceResult of(SourceOutcome.Status status) {
        return new SourceResult(status.name(), null);
    }

    /** A source that answered with {@code entity}. */
    public static SourceResult answered(ObjectNode entity) {
        return new SourceResult(SourceOutcome.Status.ANSWERED.name(), Objects.requireNonNull(entity, "entity"));
    }

    @JsonIgnore
    public boolean isAnswered() {
        return SourceOutcome.Status.ANSWERED.name().equals(status);
    }
}
