package io.github.aindriub.dataprism.core.link;

import java.time.Instant;
import java.util.Objects;

/** The outcome of {@link LinkCodec#open}: a valid payload, or a refusal with its audit reason. */
public sealed interface LinkOpenResult permits LinkOpenResult.Opened, LinkOpenResult.Refused {

    /** The link authenticated and passed expiry, issue time and relation. {@code keyId} is the key it names. */
    record Opened(LinkPayload payload, Instant expiry, String keyId) implements LinkOpenResult {
        public Opened {
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(expiry, "expiry");
            Objects.requireNonNull(keyId, "keyId");
        }

        @Override
        public String toString() {
            return "Opened[redacted]";
        }
    }

    /** The link was refused. {@code counted} says whether the failed-attempt limiter records it. */
    record Refused(LinkReason reason, boolean counted) implements LinkOpenResult {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
