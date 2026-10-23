package io.github.aindriub.dataprism.core.link;

import java.util.Objects;

/** The outcome of {@link LinkCodec#sealDetailed}: a link, or the reason none was issued. */
public sealed interface SealResult permits SealResult.Issued, SealResult.Omitted {

    /** Why no link was issued; the caller counts the link as omitted. */
    enum OmitReason {
        /** The chained key is over 256 bytes. */
        KEY_TOO_LONG,
        /** The encoded link is longer than {@code maxLength}. */
        TOO_LONG,
        /** The computed expiry is not after now: the scope or the caller token has already ended. */
        ALREADY_EXPIRED,
        /** The current link key cannot be read from the provider right now. */
        KEY_UNAVAILABLE,
        /** The scope id or the principal id is empty or over 65535 UTF-8 bytes. */
        BINDING_TOO_LONG,
        /** The target label is over 64 UTF-8 bytes. */
        TARGET_TOO_LONG
    }

    record Issued(SealedLink link) implements SealResult {
        public Issued {
            Objects.requireNonNull(link, "link");
        }
    }

    record Omitted(OmitReason reason) implements SealResult {
        public Omitted {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
