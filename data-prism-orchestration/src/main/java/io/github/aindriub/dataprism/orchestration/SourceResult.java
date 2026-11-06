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
 * @param items  only for a one-to-many {@code follow}: one entry per key read, in the order of the keyed
 *               fingerprint; empty, and then not serialised, otherwise. A result has {@code entity} or
 *               {@code items}, never both
 * @param itemsTruncated {@code true} when the resolver knew of more keys or the budget stopped the reads before
 *               every key was read; {@code null}, and then not serialised, otherwise
 */
public record SourceResult(String status, @JsonInclude(JsonInclude.Include.NON_NULL) ObjectNode entity,
                           @JsonProperty("_links") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<IssuedLink> links,
                           @JsonProperty("_linksOmitted") @JsonInclude(JsonInclude.Include.NON_NULL) Integer linksOmitted,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Item> items,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean itemsTruncated) {

    /** One key's read in a one-to-many follow. Its {@code entity} is present only when it {@code ANSWERED}. */
    public record Item(String status, @JsonInclude(JsonInclude.Include.NON_NULL) ObjectNode entity,
                       @JsonProperty("_links") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<IssuedLink> links,
                       @JsonProperty("_linksOmitted") @JsonInclude(JsonInclude.Include.NON_NULL) Integer linksOmitted) {

        public Item {
            Objects.requireNonNull(status, "status");
            links = links == null ? List.of() : List.copyOf(links);
            boolean answered = SourceOutcome.Status.ANSWERED.name().equals(status);
            if (entity != null && !answered) {
                throw new IllegalArgumentException("only an ANSWERED item carries an entity");
            }
            if ((!links.isEmpty() || linksOmitted != null) && !answered) {
                throw new IllegalArgumentException("only an ANSWERED item carries links");
            }
            if (linksOmitted != null && linksOmitted < 1) {
                throw new IllegalArgumentException("linksOmitted is a count of at least 1, or absent");
            }
        }
    }

    public SourceResult {
        Objects.requireNonNull(status, "status");
        links = links == null ? List.of() : List.copyOf(links);
        items = items == null ? List.of() : List.copyOf(items);
        boolean answered = SourceOutcome.Status.ANSWERED.name().equals(status);
        if (!items.isEmpty() && entity != null) {
            throw new IllegalArgumentException("a result carries an entity or items, never both");
        }
        if (itemsTruncated != null && !itemsTruncated) {
            throw new IllegalArgumentException("itemsTruncated is true, or absent");
        }
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

    /** A source result with no links and no items. */
    public SourceResult(String status, ObjectNode entity) {
        this(status, entity, List.of(), null, List.of(), null);
    }

    /** As before items existed. */
    public SourceResult(String status, ObjectNode entity, List<IssuedLink> links, Integer linksOmitted) {
        this(status, entity, links, linksOmitted, List.of(), null);
    }

    /** A one-to-many follow's result: no entity of its own, one item per key read. */
    public static SourceResult ofItems(SourceOutcome.Status status, List<Item> items, boolean truncated) {
        return new SourceResult(status.name(), null, List.of(), null, items, truncated ? Boolean.TRUE : null);
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
