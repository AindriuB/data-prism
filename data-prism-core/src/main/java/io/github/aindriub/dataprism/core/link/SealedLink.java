package io.github.aindriub.dataprism.core.link;

import java.time.Instant;
import java.util.Objects;

/** A sealed link: the token the client sees and the expiry written into it. {@code toString} omits the token. */
public record SealedLink(String token, Instant expiry) {

    public SealedLink {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiry, "expiry");
    }

    @Override
    public String toString() {
        return "SealedLink[length=" + token.length() + "]";
    }
}
