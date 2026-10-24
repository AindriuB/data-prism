package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.seal.KeyedNames;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical inputs a boundary test wrote, and the names computed from them. Keys are recomputed here from the
 * parts and the NUL separator, not read back from the code under test, so a key that stopped being the keyed name of
 * its inputs, or that leaked one of them, fails.
 *
 * @param names       the keyed names of the cluster under test
 * @param generator   the generator the identity entries were produced with
 * @param scopeId     the scope every scoped entry was written in
 * @param subjectId   the subject of the identity and budget entries
 * @param namespace   the namespace of the identity entries
 * @param synthetic   the pseudonym stored in the re-identification index
 * @param approvalId  the id of the approval written
 * @param requester   the requester of the approval, and the principal of the caller-rate entry
 * @param kind        the kind of the approval
 * @param tool        the tool whose pause was written
 */
public record Inputs(KeyedNames names, SyntheticValueSource generator, String scopeId, String subjectId,
                     PrivacyNamespace namespace, String synthetic, String approvalId, String requester,
                     ApprovalRequest.Kind kind, String tool) {

    private static final char NUL = 0;

    /** The values that must appear in no key: scope, subject, pseudonym, approval id and principal. */
    public List<String> secrets() {
        return List.of(scopeId, subjectId, synthetic, approvalId, requester);
    }

    public String identityKey() {
        return names.scoped(PrivacyCluster.IDENTITY_MAP, scopeId, scopeId + NUL + namespace.name() + NUL + subjectId);
    }

    public String reidentificationKey() {
        return names.scoped(PrivacyCluster.REIDENTIFICATION_MAP, scopeId, scopeId + NUL + namespace.name() + NUL + synthetic);
    }

    public String budgetKey() {
        return names.scoped(PrivacyCluster.BUDGET_MAP, scopeId, scopeId + NUL + subjectId);
    }

    public String approvalKey() {
        return names.scoped(PrivacyCluster.APPROVAL_MAP, scopeId, scopeId + NUL + approvalId);
    }

    public String claimKey() {
        return names.mapKey(PrivacyCluster.APPROVAL_ID_MAP, approvalId);
    }

    public String pendingKey() {
        return names.mapKey(PrivacyCluster.APPROVAL_PENDING_MAP, kind.name() + NUL + "=" + requester);
    }

    public String callerRateKey() {
        return names.mapKey(PrivacyCluster.CALLER_RATE_MAP, requester);
    }

    public String scopePauseKey() {
        return names.scoped(PrivacyCluster.OVERSIGHT_MAP, scopeId, scopeId + NUL + "paused");
    }

    /** Asserts exactly these keys, each a String, none containing a canonical input. */
    public void assertKeys(String map, IMap<Object, Object> contents, String... expected) {
        Set<Object> keys = new HashSet<>(contents.keySet());
        assertThat(keys).as("%s keys", map).containsExactlyInAnyOrder((Object[]) expected);
        for (Object key : keys) {
            assertThat(key).as("%s key", map).isInstanceOf(String.class);
            for (String secret : secrets()) {
                assertThat((String) key).as("%s key must not contain a canonical input", map)
                        .doesNotContain(secret);
            }
        }
    }

    /** Asserts that the map holds nothing. */
    public static void assertEmpty(String map, IMap<Object, Object> contents) {
        assertThat(contents.isEmpty()).as("%s is empty", map).isTrue();
    }
}
