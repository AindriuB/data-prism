package io.github.aindriub.dataprism.core.link;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/**
 * The constants of the link layout (sealed-links.md section 3, with the OD-200-4 caps). Package-private: the
 * sizes are a property of the format, not an API. Tests recompute the worst-case lengths from these.
 */
final class LinkLayout {

    static final int VERSION = 0x01;
    static final int MAX_KEY_ID = 16;
    static final int MAX_TARGET = 64;
    static final int MAX_NAMESPACE = 32;
    static final int MAX_RESOLVER = 32;
    static final int MAX_ENTITY_TYPE = 64;
    static final int MAX_LABEL = 64;
    static final int EXPIRY_BYTES = 8;
    static final int NONCE_BYTES = 12;
    static final int TAG_BYTES = 16;
    static final int LINK_ID_BYTES = 8;
    static final int ISSUE_BYTES = 8;
    static final int CALLER_TAG_BYTES = 16;
    static final int CATALOG_TAG_BYTES = 16;
    static final int FINGERPRINT_BYTES = 12;
    static final int PAD_BUCKET = 64;
    static final int MAX_CHAINED_KEY = 256;
    /** The smallest {@code maxLength} that holds the worst-case follow link. */
    static final int MIN_MAX_LENGTH = 616;
    static final int MAX_MAX_LENGTH = 4096;

    private LinkLayout() {}

    /** {@code instant + by}, saturating at {@link Instant#MAX} instead of throwing. */
    static Instant plus(Instant instant, Duration by) {
        try {
            return instant.plus(by);
        } catch (DateTimeException | ArithmeticException overflow) {
            return Instant.MAX;
        }
    }

    /**
     * The padded base64 length, {@code 4 * ceil(bytes / 3)}: the design's figure (616 for the worst-case follow
     * link). The token is sent without padding, so a real token is up to two characters shorter; the bound is
     * deliberately the larger number.
     */
    static int base64Length(int bytes) {
        return 4 * ((bytes + 2) / 3);
    }

    /** Header bytes before the ciphertext: version, key id, expiry, target, relation, nonce. */
    static int headerBytes(int keyId, int target) {
        return 1 + 1 + keyId + EXPIRY_BYTES + 1 + target + 1 + NONCE_BYTES;
    }

    static int followPayloadBytes(int namespace, int resolver, int chainedKey) {
        return LINK_ID_BYTES + ISSUE_BYTES + 1 + 1 + namespace + 1 + resolver + 2 + paddedLength(chainedKey);
    }

    static int pagePayloadBytes(int entityType, int namespace, int lastLabel) {
        return LINK_ID_BYTES + ISSUE_BYTES + 1 + 1 + entityType + 1 + namespace + 1 + 2 + 1 + lastLabel
                + CATALOG_TAG_BYTES;
    }

    static int paddedLength(int length) {
        return ((length + PAD_BUCKET - 1) / PAD_BUCKET) * PAD_BUCKET;
    }

    static int worstCaseFollowChars() {
        return base64Length(headerBytes(MAX_KEY_ID, MAX_TARGET)
                + followPayloadBytes(MAX_NAMESPACE, MAX_RESOLVER, MAX_CHAINED_KEY) + TAG_BYTES);
    }

    static int worstCasePageChars() {
        return base64Length(headerBytes(MAX_KEY_ID, 0)
                + pagePayloadBytes(MAX_ENTITY_TYPE, MAX_NAMESPACE, MAX_LABEL) + TAG_BYTES);
    }
}
