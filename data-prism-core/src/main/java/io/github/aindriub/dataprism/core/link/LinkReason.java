package io.github.aindriub.dataprism.core.link;

/**
 * Why a link was refused. The client sees one refusal for all of them; the reason is for the audit only, as
 * {@code REFUSED:LINK_<REASON>} (sealed-links.md section 4).
 *
 * <p>The first five are decided before the token authenticates and are counted by the failed-attempt limiter.
 * The rest are never counted: most are decided only after authentication and are reachable only with a genuine
 * link; {@code KEY_UNAVAILABLE} is a key-provider outage on a key id this member holds, which a genuine user cannot
 * cause and must not be locked out by.
 */
public enum LinkReason {
    MALFORMED(true),
    TOO_LONG(true),
    VERSION(true),
    UNKNOWN_KEY(true),
    AUTH_FAILED(true),
    KEY_UNAVAILABLE(false),
    EXPIRED(false),
    ISSUED_IN_FUTURE(false),
    WRONG_TOOL(false),
    TARGET_NOT_PERMITTED(false),
    RELATION_UNDECLARED(false),
    PAYLOAD_MISMATCH(false),
    ORIGIN_DRIFT(false),
    FILTER_MISMATCH(false),
    LABELS_CHANGED(false),
    CATALOG_CHANGED(false);

    private final boolean counted;

    LinkReason(boolean counted) {
        this.counted = counted;
    }

    /** Whether the failed-attempt limiter counts this reason. */
    public boolean counted() {
        return counted;
    }

    /** The audit disposition value, for example {@code REFUSED:LINK_AUTH_FAILED}. */
    public String auditValue() {
        return "REFUSED:LINK_" + name();
    }
}
