package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;
import com.hazelcast.spi.merge.MergingValue;
import com.hazelcast.spi.merge.SplitBrainMergeTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/** The id-claim and pending maps guard the approval map, so they get its protections and a safe merge. */
class ApprovalGuardMapsTest {

    private static final String[] GUARDS = {PrivacyCluster.APPROVAL_ID_MAP, PrivacyCluster.APPROVAL_PENDING_MAP};

    private static Config configured(OptionalInt quorum) {
        return PrivacyCluster.configure(new Config().setClusterName("guard-maps-test"), quorum);
    }

    @ParameterizedTest
    @ValueSource(strings = {PrivacyCluster.APPROVAL_ID_MAP, PrivacyCluster.APPROVAL_PENDING_MAP})
    void aGuardMapNeverEvictsAndTakesTheApprovalQuorumWhenOneIsConfigured(String name) {
        MapConfig plain = configured(OptionalInt.empty()).getMapConfig(name);
        assertThat(plain.getEvictionConfig().getEvictionPolicy()).isEqualTo(EvictionPolicy.NONE);
        assertThat(plain.getSplitBrainProtectionName()).isNull();
        assertThat(plain.getMapStoreConfig().isEnabled()).isFalse();

        Config quorate = configured(OptionalInt.of(3));
        MapConfig guarded = quorate.getMapConfig(name);
        assertThat(guarded.getEvictionConfig().getEvictionPolicy()).isEqualTo(EvictionPolicy.NONE);
        assertThat(guarded.getSplitBrainProtectionName())
                .isEqualTo(quorate.getMapConfig(PrivacyCluster.APPROVAL_MAP).getSplitBrainProtectionName())
                .isEqualTo(PrivacyCluster.APPROVAL_PROTECTION);
    }

    @Test
    void theClaimMapKeepsAHeldClaimAndThePendingMapUnionsTheIds() {
        Config config = configured(OptionalInt.empty());
        assertThat(config.getMapConfig(PrivacyCluster.APPROVAL_ID_MAP).getMergePolicyConfig().getPolicy())
                .isEqualTo("PutIfAbsentMergePolicy");
        assertThat(config.getMapConfig(PrivacyCluster.APPROVAL_PENDING_MAP).getMergePolicyConfig().getPolicy())
                .isEqualTo(PendingMergePolicy.class.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MergingValue entry(String value) {
        return (MergingValue) Proxy.newProxyInstance(ApprovalGuardMapsTest.class.getClassLoader(),
                new Class<?>[]{SplitBrainMergeTypes.MapMergeTypes.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getValue", "getDeserializedValue", "getRawValue" -> value;
                    case "toString" -> "entry";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String pending(String... idsAndExpiries) {
        return String.join("\0", idsAndExpiries);
    }

    @Test
    void healingNeverLowersARequestersPendingCount() {
        var policy = new PendingMergePolicy();
        String left = pending("a", "2030-01-01T01:00:00Z", "b", "2030-01-01T01:00:00Z");
        String right = pending("b", "2030-01-01T02:00:00Z", "c", "2030-01-01T01:00:00Z");
        Map<String, Instant> merged = PendingSet.decode(policy.merge(entry(left), entry(right)));
        assertThat(merged).containsOnlyKeys("a", "b", "c");
        assertThat(merged.get("b")).isEqualTo(Instant.parse("2030-01-01T02:00:00Z"));
        assertThat(PendingSet.decode(policy.merge(entry(right), entry(left)))).containsOnlyKeys("a", "b", "c");
    }

    @Test
    void aSideWithNoCopyKeepsTheOtherSidesIdsAndAnUnreadableSideNeverLowersTheReadableOne() {
        var policy = new PendingMergePolicy();
        String readable = pending("a", "2030-01-01T01:00:00Z");
        assertThat(policy.merge(entry(readable), null)).isEqualTo(readable);
        assertThat(policy.merge(entry("garbage"), entry(readable))).isEqualTo(readable);
        assertThat(policy.merge(entry(readable), entry("garbage"))).isEqualTo(readable);
        assertThat(policy.merge(entry("garbage"), entry("more garbage"))).isNull();
    }

    @Test
    void theGuardMapNamesAreNotApprovalKeysOrEachOther() {
        assertThat(GUARDS).doesNotContain(PrivacyCluster.APPROVAL_MAP).doesNotHaveDuplicates();
    }
}
