package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.merge.SplitBrainMergePolicy;
import com.hazelcast.spi.merge.MergingValue;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;

/**
 * What the approval map keeps when two halves of a split cluster heal and both hold the same entry.
 *
 * <p>The state that is further along wins: {@code CONSUMED}, then {@code REJECTED}, then {@code EXPIRED},
 * then {@code APPROVED}, then {@code PENDING}. An approval either side used therefore cannot be used again
 * after the heal, which Hazelcast's default policy would allow by keeping the larger side's {@code APPROVED}.
 * A tie keeps the entry already in the surviving cluster. An entry that cannot be read is dropped, so the
 * approval is refused rather than guessed.
 *
 * <p>Needs only the merging values, not per-entry statistics. Public only because Hazelcast instantiates a configured policy by class name.
 * It reads the stored string, so sealed values would need the rule to move out of the map (see the 0.8 notes).
 */
public final class ApprovalMergePolicy implements SplitBrainMergePolicy<String, MergingValue<String>, String> {

    @Override
    public String merge(MergingValue<String> merging, MergingValue<String> existing) {
        if (existing == null) {
            return merging.getValue();
        }
        Status incoming;
        Status current;
        try {
            incoming = HazelcastApprovalStore.Codec.decode(merging.getValue()).status();
            current = HazelcastApprovalStore.Codec.decode(existing.getValue()).status();
        } catch (RuntimeException unreadable) {
            return null;
        }
        return rank(incoming) > rank(current) ? merging.getValue() : existing.getValue();
    }

    private static int rank(Status status) {
        return switch (status) {
            case CONSUMED -> 4;
            case REJECTED -> 3;
            case EXPIRED -> 2;
            case APPROVED -> 1;
            case PENDING -> 0;
        };
    }

    @Override
    public void writeData(ObjectDataOutput out) {
        // No state.
    }

    @Override
    public void readData(ObjectDataInput in) {
        // No state.
    }
}
