package io.github.aindriub.dataprism.orchestration.links;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * One sealed link as the client sees it (sealed-links.md section 5). It holds the token and the public facts
 * about it, never the chained key: the key leaves the engine only as ciphertext inside {@code link}.
 *
 * @param rel         {@code direct} or {@code translated}
 * @param target      the target's label in the effective setting
 * @param field       the concrete indexed path of the chainable value, such as {@code depots[2]}
 * @param namespace   the key namespace of the field
 * @param toNamespace the namespace after the one declared resolver; null for a direct link
 * @param link        the sealed token
 * @param expiresAt   the link's expiry, ISO-8601 in UTC
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssuedLink(String rel, String target, String field, String namespace, String toNamespace,
                         String link, String expiresAt) {

    public IssuedLink {
        Objects.requireNonNull(rel, "rel");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Shows no token. */
    @Override
    public String toString() {
        return "IssuedLink[" + rel + ", redacted]";
    }
}
