package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceLabels;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which permitted sources a key of one namespace can be followed to, from declarations only: no resolver is
 * called and no adapter is read. A source accepts the key directly when its declared input namespace is equal
 * to the field's (exact comparison: no case folding, no trimming), or through exactly one declared resolver.
 * A direct match wins over a translated one for the same target (key-resolvers.md section 7), and a target is
 * listed once per target namespace.
 */
public final class LinkTargets {

    /** The longest real source name a link may be issued toward (OD-200-4). */
    static final int MAX_NAME_BYTES = 64;

    /**
     * @param relation     {@code DIRECT} or {@code TRANSLATED}
     * @param name         the target's real name
     * @param label        the target's label in the effective setting
     * @param toNamespace  the namespace after translation; null when direct
     * @param resolverName the one resolver; null when direct
     * @param eligible     false when the real name is over 64 bytes, or the source declares several entity
     *                     types and none is mapped to the read namespace: no link, counted as omitted
     * @param entityType   the type the read carries: {@code entity} when the source declares none, its one type,
     *                     or the mapped one; null when not eligible for want of a mapping
     */
    record Target(LinkRelation relation, String name, String label, KeyNamespace toNamespace, String resolverName,
                  boolean eligible, String entityType) {
    }

    private LinkTargets() {
    }

    /** The targets for {@code namespace}, never the origin itself, in ascending label order. */
    static List<Target> forNamespace(KeyNamespace namespace, String originName, SourceLabels labels,
                                     KeyResolvers resolvers) {
        Objects.requireNonNull(namespace, "namespace");
        Map<String, SourceCatalog.Entry> permitted = labels.permittedEntries();
        // A direct target is keyed by name; a translated one by name and target namespace, so two resolvers
        // reaching one source into different namespaces each give a link.
        Map<String, Target> byName = new LinkedHashMap<>();
        Set<String> direct = new HashSet<>();
        for (SourceCatalog.Entry entry : permitted.values()) {
            if (!entry.name().equals(originName) && entry.description().inputKeys().contains(namespace)) {
                direct.add(entry.name());
                byName.put(entry.name(), target(LinkRelation.DIRECT, entry, labels, namespace, null, null));
            }
        }
        for (KeyResolver resolver : resolvers.from(namespace)) {
            KeyNamespace to = resolver.to();
            for (SourceCatalog.Entry entry : permitted.values()) {
                if (!entry.name().equals(originName) && !direct.contains(entry.name())
                        && entry.description().inputKeys().contains(to)) {
                    byName.putIfAbsent(entry.name() + '\0' + to.value(),
                            target(LinkRelation.TRANSLATED, entry, labels, namespace, to, resolver.name()));
                }
            }
        }
        List<Target> out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparing(Target::label)
                .thenComparing(t -> t.toNamespace() == null ? "" : t.toNamespace().value()));
        return out;
    }

    private static Target target(LinkRelation relation, SourceCatalog.Entry entry, SourceLabels labels,
                                 KeyNamespace from, KeyNamespace to, String resolverName) {
        boolean eligible = entry.name().getBytes(StandardCharsets.UTF_8).length <= MAX_NAME_BYTES;
        // The type follows the namespace the target reads: the translated one when there is a resolver.
        KeyNamespace read = to != null ? to : from;
        SourceDescription description = entry.description();
        String entityType;
        if (description.entityTypes().isEmpty()) {
            entityType = SourceDescription.UNDECLARED_ENTITY_TYPE;
        } else if (description.entityTypes().size() == 1) {
            entityType = description.entityTypes().iterator().next();
        } else {
            entityType = description.namespaceTypes().get(read);
            if (entityType == null) {
                eligible = false;
            }
        }
        return new Target(relation, entry.name(), labels.labelOf(entry.name()), to, resolverName, eligible,
                entityType);
    }
}
