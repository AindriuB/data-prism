package io.github.aindriub.dataprism.core.link;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/** The sealed payload of a link: what the codec seals and, after authentication, returns. */
public sealed interface LinkPayload permits FollowPayload, CatalogPagePayload {

    byte[] linkId();

    Instant issueTime();

    LinkRelation relation();

    /** Validation shared by the payload types. No message names a value. */
    final class Check {
        private Check() {}

        static byte[] linkId(byte[] linkId) {
            Objects.requireNonNull(linkId, "linkId");
            if (linkId.length != LinkLayout.LINK_ID_BYTES) {
                throw new IllegalArgumentException("a link id is 8 bytes");
            }
            return linkId.clone();
        }

        static Instant seconds(Instant instant, String name) {
            Objects.requireNonNull(instant, name);
            return Instant.ofEpochSecond(instant.getEpochSecond());
        }

        /** UTF-8 length within {@code max}; {@code min} is 0 or 1. */
        static String text(String value, int min, int max, String name) {
            Objects.requireNonNull(value, name);
            int length = value.getBytes(StandardCharsets.UTF_8).length;
            if (length < min || length > max) {
                throw new IllegalArgumentException(name + " is outside " + min + " to " + max + " bytes");
            }
            return value;
        }
    }
}
