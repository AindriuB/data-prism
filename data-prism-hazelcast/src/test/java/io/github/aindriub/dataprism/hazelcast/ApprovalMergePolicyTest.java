package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.spi.merge.SplitBrainMergePolicy;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the cluster keeps when two halves that each hold the same approval heal. Whatever a member on
 * either side recorded as used, refused or expired must survive the merge.
 */
class ApprovalMergePolicyTest {

    private static final Set<Status> DECIDED = EnumSet.of(
            Status.APPROVED, Status.REJECTED, Status.CONSUMED, Status.EXPIRED);

    /** The policy exactly as the approval map is configured, not the class by name. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SplitBrainMergePolicy configuredPolicy() throws Exception {
        Config config = PrivacyCluster.configure(new Config().setClusterName("merge-policy-test"));
        String name = config.getMapConfig(PrivacyCluster.APPROVAL_MAP).getMergePolicyConfig().getPolicy();
        return (SplitBrainMergePolicy) Class.forName(name).getDeclaredConstructor()
                .newInstance();
    }

    /** A merging entry that answers the value accessors, which is all the policy may read. */
    @SuppressWarnings("rawtypes")
    private static com.hazelcast.spi.merge.MergingValue entry(String encoded) {
        return (com.hazelcast.spi.merge.MergingValue) Proxy.newProxyInstance(ApprovalMergePolicyTest.class.getClassLoader(),
                new Class<?>[]{com.hazelcast.spi.merge.SplitBrainMergeTypes.MapMergeTypes.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getValue", "getDeserializedValue", "getRawValue" -> encoded;
                    case "getKey", "getRawKey" -> "scope\0id";
                    case "toString" -> "entry";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_TOKEN = "00000000-0000-0000-0000-000000000002";

    private static String encoded(Status status) {
        return encoded(status, TOKEN);
    }

    private static String encoded(Status status, String token) {
        return HazelcastApprovalStore.Codec.encode(TestMembers.request("a-1", "s-1", status), token);
    }

    private static Status merged(Status merging, Status existing) throws Exception {
        Object result = configuredPolicy().merge(entry(encoded(merging)), entry(encoded(existing)));
        return HazelcastApprovalStore.Codec.decode((String) result).status();
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"APPROVED", "REJECTED", "EXPIRED", "PENDING"})
    void consumedBeatsEveryOtherStateInEitherDirection(Status other) throws Exception {
        assertThat(merged(Status.CONSUMED, other)).isEqualTo(Status.CONSUMED);
        assertThat(merged(other, Status.CONSUMED)).isEqualTo(Status.CONSUMED);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"REJECTED", "EXPIRED"})
    void rejectedAndExpiredBeatApprovedInEitherDirection(Status closed) throws Exception {
        assertThat(merged(closed, Status.APPROVED)).isEqualTo(closed);
        assertThat(merged(Status.APPROVED, closed)).isEqualTo(closed);
    }

    @Test
    void pendingNeverBeatsADecidedState() throws Exception {
        for (Status decided : DECIDED) {
            assertThat(merged(Status.PENDING, decided)).isEqualTo(decided);
            assertThat(merged(decided, Status.PENDING)).isEqualTo(decided);
        }
    }

    @Test
    void aSideWithNoCopyKeepsTheOtherSidesEntry() throws Exception {
        Object result = configuredPolicy().merge(entry(encoded(Status.CONSUMED)), null);
        assertThat(HazelcastApprovalStore.Codec.decode((String) result).status()).isEqualTo(Status.CONSUMED);
    }

    @Test
    void anUnreadableEntryIsDroppedRatherThanKept() throws Exception {
        assertThat(configuredPolicy().merge(entry("not an approval"), entry(encoded(Status.APPROVED)))).isNull();
        assertThat(configuredPolicy().merge(entry(encoded(Status.APPROVED)), entry("not an approval"))).isNull();
    }

    @Test
    void differentTokensKeepTheExistingSideForEveryPairOfStatusesInBothOrders() throws Exception {
        for (Status incoming : Status.values()) {
            for (Status current : Status.values()) {
                String merging = encoded(incoming, OTHER_TOKEN);
                String existing = encoded(current, TOKEN);
                assertThat(configuredPolicy().merge(entry(merging), entry(existing))).isEqualTo(existing);
                assertThat(configuredPolicy().merge(entry(existing), entry(merging))).isEqualTo(merging);
            }
        }
    }

    @Test
    void equalTokensApplyTheStatusRankInBothOrders() throws Exception {
        for (Status incoming : Status.values()) {
            for (Status current : Status.values()) {
                Object result = configuredPolicy().merge(entry(encoded(incoming, OTHER_TOKEN)),
                        entry(encoded(current, OTHER_TOKEN)));
                Status expected = incoming.compareTo(current) == 0 ? current : merged(incoming, current);
                assertThat(HazelcastApprovalStore.Codec.decode((String) result).status()).isEqualTo(expected);
                assertThat(HazelcastApprovalStore.Codec.tokenOf((String) result)).isEqualTo(OTHER_TOKEN);
            }
        }
    }

    @Test
    void aSideInTheOldSixteenFieldFormIsDropped() throws Exception {
        String old = encoded(Status.APPROVED);
        old = old.substring(0, old.lastIndexOf('\0'));
        assertThat(configuredPolicy().merge(entry(old), entry(encoded(Status.APPROVED)))).isNull();
        assertThat(configuredPolicy().merge(entry(encoded(Status.APPROVED)), entry(old))).isNull();
    }
}
