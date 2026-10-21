package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MergePolicyConfig;
import com.hazelcast.config.SplitBrainProtectionConfig;
import com.hazelcast.splitbrainprotection.SplitBrainProtectionOn;

import java.util.List;
import java.util.OptionalInt;

/**
 * The approval entries, the id claims, the pending sets and the consumed-approval tombstones, with the split-brain
 * protection they share.
 */
final class ApprovalMaps {

    static final List<String> NAMES = List.of(PrivacyCluster.APPROVAL_MAP, PrivacyCluster.APPROVAL_ID_MAP,
            PrivacyCluster.APPROVAL_PENDING_MAP, PrivacyCluster.APPROVAL_CONSUMED_MAP);

    private ApprovalMaps() {
    }

    static void configure(Config config, OptionalInt approvalMinimumMembers) {
        // A minimum of 1 is met by any member that is running, so it needs no protection. Configuring it would
        // only refuse approvals for the first seconds after start, until Hazelcast first evaluates the members.
        boolean protect = approvalMinimumMembers.isPresent() && approvalMinimumMembers.getAsInt() > 1;
        MapConfig approval = PrivacyCluster.privacyMap(new MapConfig(PrivacyCluster.APPROVAL_MAP));
        // After a heal an approval either side used stays used; the default keeps the larger side's copy.
        approval.setMergePolicyConfig(new MergePolicyConfig(ApprovalMergePolicy.class.getName(),
                MergePolicyConfig.DEFAULT_BATCH_SIZE));
        if (protect) {
            config.addSplitBrainProtectionConfig(new SplitBrainProtectionConfig(PrivacyCluster.APPROVAL_PROTECTION,
                    true, approvalMinimumMembers.getAsInt()).setProtectOn(SplitBrainProtectionOn.WRITE));
            approval.setSplitBrainProtectionName(PrivacyCluster.APPROVAL_PROTECTION);
        }
        config.addMapConfig(approval);
        // The id claims, pending sets and consumed tombstones guard the approval map, so they get its write quorum,
        // never evict, and a heal never frees a held claim, lowers a requester's pending set or forgets a use.
        guard(config, PrivacyCluster.APPROVAL_ID_MAP, "PutIfAbsentMergePolicy", protect);
        guard(config, PrivacyCluster.APPROVAL_PENDING_MAP, PendingMergePolicy.class.getName(), protect);
        guard(config, PrivacyCluster.APPROVAL_CONSUMED_MAP, "PutIfAbsentMergePolicy", protect);
    }

    private static void guard(Config config, String name, String mergePolicy, boolean protect) {
        MapConfig map = PrivacyCluster.privacyMap(new MapConfig(name));
        // An evicted claim, pending id or tombstone reopens the path it closes. Entries leave by removal or TTL.
        map.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        map.setMergePolicyConfig(new MergePolicyConfig(mergePolicy, MergePolicyConfig.DEFAULT_BATCH_SIZE));
        if (protect) {
            map.setSplitBrainProtectionName(PrivacyCluster.APPROVAL_PROTECTION);
        }
        config.addMapConfig(map);
    }
}
