package io.github.aindriub.dataprism.orchestration.catalog;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of {@code list_sources}: the entries and the page object (batch-and-bulk-follow.md A.3). It is the
 * one definition of the response body; the tool serialises {@link #toJson} and the validators scan the same tree.
 *
 * @param entries   in ascending byte order of label
 * @param size      the page size that applied, after the ceiling
 * @param next      the sealed link to the next page; present exactly when {@code last} is false
 * @param expiresAt when {@code next} stops being accepted; present exactly when {@code next} is
 */
public record CatalogPage(SourceCatalog.LabelKind labels, List<Entry> entries, int size, boolean last,
                          Optional<String> next, Optional<Instant> expiresAt) {

    /** One listed source. Optional parts are omitted when undeclared, never null. */
    public record Entry(String source, String description, List<String> entityTypes, List<String> inputKeys,
                        Optional<String> cost, Optional<String> freshness) {
    }

    public CatalogPage {
        Objects.requireNonNull(labels, "labels");
        entries = List.copyOf(entries);
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (last == next.isPresent() || next.isPresent() != expiresAt.isPresent()) {
            throw new IllegalArgumentException("next and expiresAt are present exactly when last is false");
        }
    }

    public ObjectNode toJson(String correlationId) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode root = f.objectNode();
        root.put("correlationId", correlationId);
        root.put("labels", labels.name());
        ArrayNode sources = root.putArray("sources");
        for (Entry e : entries) {
            ObjectNode node = sources.addObject();
            node.put("source", e.source());
            node.put("description", e.description());
            if (!e.entityTypes().isEmpty()) {
                ArrayNode types = node.putArray("entityTypes");
                e.entityTypes().forEach(types::add);
            }
            ArrayNode keys = node.putArray("inputKeys");
            e.inputKeys().forEach(keys::add);
            e.cost().ifPresent(v -> node.put("cost", v));
            e.freshness().ifPresent(v -> node.put("freshness", v));
        }
        ObjectNode page = root.putObject("page");
        page.put("size", size);
        page.put("last", last);
        next.ifPresent(v -> page.put("next", v));
        expiresAt.ifPresent(v -> page.put("expiresAt", v.toString()));
        return root;
    }
}
