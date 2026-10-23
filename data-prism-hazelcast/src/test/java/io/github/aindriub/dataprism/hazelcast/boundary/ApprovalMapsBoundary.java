package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval entries, the id claims and the pending sets. The consumed-approval map is written by nothing until
 * task 224, so for now it must be empty.
 *
 * <p>The values are decoded by the {@link Decoders} the test supplies, because the codecs of the stores are
 * package-private.
 */
public final class ApprovalMapsBoundary implements AreaBoundary {

    /** Reads the stored forms; supplied by the test because those codecs are package-private. */
    public interface Decoders {
        /** The approval id and scope id of an approval entry's value. */
        String[] approval(String value);

        /** The entry key and the token a claim names, or null if it does not read. */
        String[] claim(String value);

        /** The approval ids a pending set holds. */
        Set<String> pendingIds(String value);
    }

    private final Decoders decoders;

    public ApprovalMapsBoundary(Decoders decoders) {
        this.decoders = decoders;
    }

    @Override
    public Set<String> maps() {
        return Set.of(PrivacyCluster.APPROVAL_MAP, PrivacyCluster.APPROVAL_ID_MAP,
                PrivacyCluster.APPROVAL_PENDING_MAP, PrivacyCluster.APPROVAL_CONSUMED_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        switch (map) {
            case PrivacyCluster.APPROVAL_MAP -> {
                in.assertKeys(map, contents, in.approvalKey());
                Object value = contents.get(in.approvalKey());
                assertThat(value).isInstanceOf(String.class);
                String[] decoded = decoders.approval((String) value);
                assertThat(decoded[0]).isEqualTo(in.approvalId());
                assertThat(decoded[1]).isEqualTo(in.scopeId());
            }
            case PrivacyCluster.APPROVAL_ID_MAP -> {
                // One claim per id, under the id's keyed name with no scope token; it names the entry's keyed key and
                // the random token of the creation, which carries no personal data.
                in.assertKeys(map, contents, in.claimKey());
                String[] named = decoders.claim((String) contents.get(in.claimKey()));
                assertThat(named).isNotNull();
                assertThat(named[0]).isEqualTo(in.approvalKey());
                assertThat(UUID.fromString(named[1]).toString()).isEqualTo(named[1]);
            }
            case PrivacyCluster.APPROVAL_PENDING_MAP -> {
                in.assertKeys(map, contents, in.pendingKey());
                Object value = contents.get(in.pendingKey());
                assertThat(value).isInstanceOf(String.class);
                assertThat(decoders.pendingIds((String) value)).containsExactly(in.approvalId());
            }
            case PrivacyCluster.APPROVAL_CONSUMED_MAP -> Inputs.assertEmpty(map, contents);
            default -> throw new AssertionError("not an approval map: " + map);
        }
    }
}
