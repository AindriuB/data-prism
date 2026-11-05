package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aindriub.dataprism.orchestration.links.IssuedLink;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
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
 * @param links  the sealed links toward other sources; empty, and then not serialised, unless links are
 *               enabled and the source is {@code ANSWERED}
 * @param linksOmitted how many links the source lost to the response cap or to a limit; {@code null}, and
 *               then not serialised, when none were lost
 */
public record SourceResult(String status, @JsonInclude(JsonInclude.Include.NON_NULL) ObjectNode entity,
                           @JsonProperty("_links") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<IssuedLink> links,
                           @JsonProperty("_linksOmitted") @JsonInclude(JsonInclude.Include.NON_NULL) Integer linksOmitted) {

    public SourceResult {
        Objects.requireNonNull(status, "status");
        links = links == null ? List.of() : List.copyOf(links);
        boolean answered = SourceOutcome.Status.ANSWERED.name().equals(status);
        if (entity != null && !answered) {
            throw new IllegalArgumentException("only an ANSWERED source carries an entity");
        }
        if ((!links.isEmpty() || linksOmitted != null) && !answered) {
            throw new IllegalArgumentException("only an ANSWERED source carries links");
        }
        if (linksOmitted != null && linksOmitted < 1) {
            throw new IllegalArgumentException("linksOmitted is a count of at least 1, or absent");
        }
    }

    /** A source result with no links. */
    public SourceResult(String status, ObjectNode entity) {
        this(status, entity, List.of(), null);
    }

    /** A source that answered with {@code entity} and carries these links. */
    static SourceResult answered(ObjectNode entity, List<IssuedLink> links, int omitted) {
        return new SourceResult(SourceOutcome.Status.ANSWERED.name(), Objects.requireNonNull(entity, "entity"),
                links, omitted > 0 ? omitted : null);
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
