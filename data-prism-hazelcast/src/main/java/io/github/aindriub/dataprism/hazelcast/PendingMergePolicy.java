package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.merge.MergingValue;
import com.hazelcast.spi.merge.SplitBrainMergePolicy;

/**
 * What the pending-approval map keeps when two halves of a split cluster heal and both hold the same requester.
 *
 * <p>The union of both sides' ids, each with the later expiry. A heal therefore never lowers a requester's pending
 * count, so the cap cannot be exceeded by splitting and healing. An entry that cannot be read on either side is
 * dropped in favour of the readable one; if neither is readable the existing value is kept, so the cap refuses (fail closed)
 * rather than counting from zero.
 *
 * <p>Public only because Hazelcast instantiates a configured policy by class name.
 */
public final class PendingMergePolicy implements SplitBrainMergePolicy<String, MergingValue<String>, String> {

    @Override
    public String merge(MergingValue<String> merging, MergingValue<String> existing) {
        if (existing == null) {
            return merging.getValue();
        }
        boolean incomingReadable = readable(merging.getValue());
        boolean currentReadable = readable(existing.getValue());
        if (incomingReadable && currentReadable) {
            return PendingSet.union(merging.getValue(), existing.getValue());
        }
        if (incomingReadable) {
            return merging.getValue();
        }
        // Neither side is readable: keep the existing value. The next reserve throws on it and the create is refused,
        // which fails closed; dropping it would reset the requester's count to zero.
        return existing.getValue();
    }

    private static boolean readable(String raw) {
        try {
            PendingSet.decode(raw);
            return true;
        } catch (RuntimeException unreadable) {
            return false;
        }
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
