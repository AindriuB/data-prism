package io.github.aindriub.dataprism.core.link;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The payload of a {@code follow} link (relation {@code DIRECT} or {@code TRANSLATED}). The target label is
 * carried in the cleartext header and the associated data, not in the sealed payload; it is part of this record
 * so that a link round-trips as one value.
 *
 * <p>{@code toString} shows no value: the chained key is a real identifier.
 */
public record FollowPayload(
        byte[] linkId,
        Instant issueTime,
        String keyNamespace,
        Optional<String> resolverName,
        byte[] chainedKey,
        String target,
        LinkRelation relation) implements LinkPayload {

    public FollowPayload {
        linkId = Check.linkId(linkId);
        issueTime = Check.seconds(issueTime, "issueTime");
        Check.text(keyNamespace, 1, LinkLayout.MAX_NAMESPACE, "keyNamespace");
        Objects.requireNonNull(resolverName, "resolverName");
        resolverName.ifPresent(name -> Check.text(name, 1, LinkLayout.MAX_RESOLVER, "resolverName"));
        Objects.requireNonNull(chainedKey, "chainedKey");
        if (chainedKey.length == 0) {
            throw new IllegalArgumentException("the chained key is empty");
        }
        chainedKey = chainedKey.clone();
        Check.text(target, 1, LinkLayout.MAX_TARGET, "target");
        Objects.requireNonNull(relation, "relation");
        if (relation != LinkRelation.DIRECT && relation != LinkRelation.TRANSLATED) {
            throw new IllegalArgumentException("a follow payload is DIRECT or TRANSLATED");
        }
        if (relation == LinkRelation.DIRECT && resolverName.isPresent()) {
            throw new IllegalArgumentException("a DIRECT follow payload names no resolver");
        }
        if (relation == LinkRelation.TRANSLATED && resolverName.isEmpty()) {
            throw new IllegalArgumentException("a TRANSLATED follow payload names its resolver");
        }
    }

    @Override
    public byte[] linkId() {
        return linkId.clone();
    }

    @Override
    public byte[] chainedKey() {
        return chainedKey.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FollowPayload that
                && Arrays.equals(linkId, that.linkId)
                && issueTime.equals(that.issueTime)
                && keyNamespace.equals(that.keyNamespace)
                && resolverName.equals(that.resolverName)
                && Arrays.equals(chainedKey, that.chainedKey)
                && target.equals(that.target)
                && relation == that.relation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(linkId), issueTime, keyNamespace, resolverName,
                Arrays.hashCode(chainedKey), target, relation);
    }

    @Override
    public String toString() {
        return "FollowPayload[" + relation + ", redacted]";
    }
}
