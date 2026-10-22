package io.github.aindriub.dataprism.core.link;

/**
 * The relation byte of a link: a cleartext header field that is also in the associated data. The table is that
 * of sealed-links.md section 3 and batch-and-bulk-follow.md 3.2. {@link #COLLECTION_PAGE} and {@link #GROUP}
 * are reserved for 0.9 and have no payload type in 0.8, so a link carrying either is never opened.
 */
public enum LinkRelation {
    DIRECT(0x00),
    TRANSLATED(0x01),
    CATALOG_PAGE(0x02),
    COLLECTION_PAGE(0x03),
    GROUP(0x04);

    private final int code;

    LinkRelation(int code) {
        this.code = code;
    }

    /** The byte written to the header. */
    public int code() {
        return code;
    }

    /** The relation for a header byte, or {@code null} for the reserved range {@code 0x05} to {@code 0xFF}. */
    static LinkRelation fromCode(int code) {
        for (LinkRelation relation : values()) {
            if (relation.code == code) {
                return relation;
            }
        }
        return null;
    }
}
