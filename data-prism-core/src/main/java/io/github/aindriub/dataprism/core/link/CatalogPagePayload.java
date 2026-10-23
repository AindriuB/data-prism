package io.github.aindriub.dataprism.core.link;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The payload of a {@code list_sources} page link (relation {@code CATALOG_PAGE}): the filters, the label
 * kind and page size the walk began with, the last label returned and the catalog tag
 * (batch-and-bulk-follow.md A.4). The header target is empty.
 *
 * <p>{@code toString} shows no value: with real names in effect the last label is a real source name.
 */
public record CatalogPagePayload(
        byte[] linkId,
        Instant issueTime,
        Optional<String> entityTypeFilter,
        Optional<String> keyNamespaceFilter,
        LinkLabelKind labelKind,
        int pageSize,
        String lastLabel,
        byte[] catalogTag) implements LinkPayload {

    public CatalogPagePayload {
        linkId = PayloadCheck.linkId(linkId);
        issueTime = PayloadCheck.seconds(issueTime, "issueTime");
        Objects.requireNonNull(entityTypeFilter, "entityTypeFilter");
        entityTypeFilter.ifPresent(v -> PayloadCheck.text(v, 1, LinkLayout.MAX_ENTITY_TYPE, "entityTypeFilter"));
        Objects.requireNonNull(keyNamespaceFilter, "keyNamespaceFilter");
        keyNamespaceFilter.ifPresent(v -> PayloadCheck.text(v, 1, LinkLayout.MAX_NAMESPACE, "keyNamespaceFilter"));
        Objects.requireNonNull(labelKind, "labelKind");
        if (pageSize < 1 || pageSize > 0xFFFF) {
            throw new IllegalArgumentException("pageSize is outside 1 to 65535");
        }
        PayloadCheck.text(lastLabel, 1, LinkLayout.MAX_LABEL, "lastLabel");
        Objects.requireNonNull(catalogTag, "catalogTag");
        if (catalogTag.length != LinkLayout.CATALOG_TAG_BYTES) {
            throw new IllegalArgumentException("a catalog tag is 16 bytes");
        }
        catalogTag = catalogTag.clone();
    }

    @Override
    public LinkRelation relation() {
        return LinkRelation.CATALOG_PAGE;
    }

    @Override
    public byte[] linkId() {
        return linkId.clone();
    }

    @Override
    public byte[] catalogTag() {
        return catalogTag.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CatalogPagePayload that
                && Arrays.equals(linkId, that.linkId)
                && issueTime.equals(that.issueTime)
                && entityTypeFilter.equals(that.entityTypeFilter)
                && keyNamespaceFilter.equals(that.keyNamespaceFilter)
                && labelKind == that.labelKind
                && pageSize == that.pageSize
                && lastLabel.equals(that.lastLabel)
                && Arrays.equals(catalogTag, that.catalogTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(linkId), issueTime, entityTypeFilter, keyNamespaceFilter, labelKind,
                pageSize, lastLabel, Arrays.hashCode(catalogTag));
    }

    @Override
    public String toString() {
        return "CatalogPagePayload[redacted]";
    }
}
