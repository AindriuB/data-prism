package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Review of task 222: what only the resolved material can show, and what happens to it afterwards. */
class ClusterKeyResolutionTest {

    private static byte[] bytes(char c) {
        byte[] out = new byte[32];
        Arrays.fill(out, (byte) c);
        return out;
    }

    private static DataPrismProperties properties() {
        DataPrismProperties p = new DataPrismProperties();
        p.getPrivacy().getHmacKey().setKeyId("hmac-1");
        p.getPrivacy().getHmacKey().setEnvironmentVariable("HMAC_REF");
        HazelcastProperties.Security s = p.getHazelcast().getSecurity();
        s.getClusterKey().setKeyId("cluster-1");
        s.getClusterKey().setEnvironmentVariable("CLUSTER_REF");
        s.getNameKey().setKeyId("name-1");
        s.getNameKey().setEnvironmentVariable("NAME_REF");
        return p;
    }

    @Test
    void anHmacKeyThatCannotBeResolvedAgainIsRefusedEvenWhenTheIdsDiffer() {
        HmacKeyReferenceResolver resolver = (id, reference) -> {
            if (reference.equals("HMAC_REF")) {
                throw new IllegalStateException("provider unavailable");
            }
            return reference.equals("CLUSTER_REF") ? bytes('c') : bytes('n');
        };
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(properties(), () -> resolver));
        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).startsWith("HMAC_KEY_UNRESOLVED:");
    }

    @Test
    void anHmacKeyThatResolvesToNothingIsRefused() {
        HmacKeyReferenceResolver resolver = (id, reference) -> switch (reference) {
            case "HMAC_REF" -> null;
            case "CLUSTER_REF" -> bytes('c');
            default -> bytes('n');
        };
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(properties(), () -> resolver));
        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).startsWith("HMAC_KEY_UNRESOLVED:");
    }

    @Test
    void aCachingResolverKeepsItsLiveKeys() {
        Map<String, byte[]> cache = new HashMap<>();
        cache.put("HMAC_REF", bytes('h'));
        cache.put("CLUSTER_REF", bytes('c'));
        cache.put("NAME_REF", bytes('n'));
        List<byte[]> handedOut = new ArrayList<>();
        HmacKeyReferenceResolver caching = (id, reference) -> {
            handedOut.add(cache.get(reference));
            return cache.get(reference);
        };
        ClusterKeys keys = ClusterKeyResolution.resolve(properties(), () -> caching).orElseThrow();
        assertThat(keys.current().id()).isEqualTo("cluster-1");
        assertThat(handedOut).hasSize(3);
        assertThat(cache.get("CLUSTER_REF")).isEqualTo(bytes('c'));
        assertThat(cache.get("NAME_REF")).isEqualTo(bytes('n'));
        assertThat(cache.get("HMAC_REF")).isEqualTo(bytes('h'));
    }

    @Test
    void aWeakKeyFromACachingResolverIsNotZeroedEither() {
        byte[] weak = new byte[] {1, 2, 3};
        Map<String, byte[]> cache = Map.of("CLUSTER_REF", weak, "NAME_REF", bytes('n'), "HMAC_REF", bytes('h'));
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(properties(), () -> (id, ref) -> cache.get(ref)));
        assertThat(refused.getMessage()).startsWith("CLUSTER_KEY_WEAK:");
        assertThat(weak).containsExactly(1, 2, 3);
    }

    @Test
    void aWeakNameKeyIsRefusedBeforeTheHmacKeyIsResolved() {
        HmacKeyReferenceResolver resolver = (id, reference) -> switch (reference) {
            case "HMAC_REF" -> throw new IllegalStateException("provider unavailable");
            case "CLUSTER_REF" -> bytes('c');
            default -> new byte[] {1};
        };
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(properties(), () -> resolver));
        assertThat(refused.getMessage()).startsWith("NAME_KEY_WEAK:");
    }

    @Test
    void anUnresolvedHmacKeyIsRefusedBeforeTheClusterKeysAreComparedWithIt() {
        DataPrismProperties p = properties();
        p.getHazelcast().getSecurity().getClusterKey().setKeyId("hmac-1");
        HmacKeyReferenceResolver resolver = (id, reference) -> {
            if (reference.equals("HMAC_REF")) {
                throw new IllegalStateException("provider unavailable");
            }
            return reference.equals("CLUSTER_REF") ? bytes('c') : bytes('n');
        };
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(p, () -> resolver));
        assertThat(refused.getMessage()).startsWith("HMAC_KEY_UNRESOLVED:");
    }

    @Test
    void aProviderReferenceIsResolvedAsAProviderReferenceAndAnEnvironmentVariableAsOne() {
        DataPrismProperties p = properties();
        HazelcastProperties.ClusterKey cluster = p.getHazelcast().getSecurity().getClusterKey();
        cluster.setEnvironmentVariable(null);
        cluster.setProviderReference("vault:cluster");
        List<String> calls = new ArrayList<>();
        HmacKeyReferenceResolver resolver = new HmacKeyReferenceResolver() {
            @Override public byte[] resolve(String keyId, String reference) {
                calls.add("env:" + reference);
                return reference.equals("HMAC_REF") ? bytes('h') : bytes('n');
            }

            @Override public byte[] resolveProviderReference(String keyId, String reference) {
                calls.add("provider:" + reference);
                return bytes('c');
            }
        };
        assertThat(ClusterKeyResolution.resolve(p, () -> resolver)).isPresent();
        assertThat(calls).contains("provider:vault:cluster", "env:NAME_REF").doesNotContain("env:vault:cluster");
    }

    @Test
    void aResolverThatCannotServeProviderReferencesRefusesWithACodeNamingTheProperty() {
        DataPrismProperties p = properties();
        p.getHazelcast().getSecurity().getNameKey().setEnvironmentVariable(null);
        p.getHazelcast().getSecurity().getNameKey().setProviderReference("vault:name");
        HmacKeyReferenceResolver environmentOnly = new HmacKeyReferenceResolver() {
            @Override public byte[] resolve(String keyId, String reference) {
                return reference.equals("HMAC_REF") ? bytes('h') : bytes('c');
            }

            @Override public byte[] resolveProviderReference(String keyId, String reference) {
                throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER", "no provider");
            }
        };
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(p, () -> environmentOnly));
        assertThat(refused.getMessage()).startsWith("MISSING_KEY_PROVIDER:")
                .contains("dataprism.hazelcast.security.name-key.provider-reference")
                .doesNotContain("vault:name");
    }

    @Test
    void anHmacKeyGivenAsAProviderReferenceIsComparedThroughTheProviderMethod() {
        DataPrismProperties p = properties();
        p.getPrivacy().getHmacKey().setEnvironmentVariable(null);
        p.getPrivacy().getHmacKey().setProviderReference("vault:hmac");
        HmacKeyReferenceResolver resolver = new HmacKeyReferenceResolver() {
            @Override public byte[] resolve(String keyId, String reference) {
                return reference.equals("CLUSTER_REF") ? bytes('c') : bytes('n');
            }

            @Override public byte[] resolveProviderReference(String keyId, String reference) {
                return bytes('c');
            }
        };
        // The HMAC key has the cluster key's material, which only the provider method reveals.
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(p, () -> resolver));
        assertThat(refused.getMessage()).startsWith("CLUSTER_KEY_SHARED:");
    }

    @Test
    void aResolverThatDoesNotOverrideTheProviderMethodRefusesAProviderReferenceWithoutNamingIt() {
        HmacKeyReferenceResolver envOnly = (id, reference) -> bytes('e');
        assertThatThrownBy(() -> envOnly.resolveProviderReference("k", "vault:secret-ref"))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_KEY_PROVIDER:")
                .hasMessageNotContaining("vault:secret-ref");
    }

    private static DataPrismProperties keyedAudit() {
        DataPrismProperties p = properties();
        p.getAudit().getHmacKey().setKeyId("audit-1");
        p.getAudit().getHmacKey().setEnvironmentVariable("AUDIT_REF");
        AuditProperties.RetiredKey retired = new AuditProperties.RetiredKey();
        retired.setEnvironmentVariable("RETIRED_REF");
        p.getAudit().getRetiredHmacKeys().put("audit-0", retired);
        return p;
    }

    private static HmacKeyReferenceResolver auditResolver(Map<String, byte[]> overrides) {
        Map<String, byte[]> keys = new HashMap<>(Map.of("HMAC_REF", bytes('h'), "CLUSTER_REF", bytes('c'),
                "NAME_REF", bytes('n'), "AUDIT_REF", bytes('a'), "RETIRED_REF", bytes('r')));
        keys.putAll(overrides);
        return (id, reference) -> keys.get(reference);
    }

    @Test
    void distinctAuditKeysDoNotStopTheClusterKeys() {
        assertThat(ClusterKeyResolution.resolve(keyedAudit(), () -> auditResolver(Map.of()))).isPresent();
    }

    @Test
    void aClusterKeyWithTheMaterialOfTheAuditKeyOrARetiredOneIsRefused() {
        for (String shared : List.of("AUDIT_REF", "RETIRED_REF")) {
            DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                    () -> ClusterKeyResolution.resolve(keyedAudit(),
                            () -> auditResolver(Map.of("CLUSTER_REF", bytes(shared.equals("AUDIT_REF") ? 'a' : 'r')))));
            assertThat(refused.getMessage()).startsWith("CLUSTER_KEY_SHARED:");
        }
    }

    @Test
    void aNameKeyWithTheMaterialOfTheAuditKeyOrARetiredOneIsRefused() {
        for (char shared : new char[] {'a', 'r'}) {
            DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                    () -> ClusterKeyResolution.resolve(keyedAudit(), () -> auditResolver(Map.of("NAME_REF", bytes(shared)))));
            assertThat(refused.getMessage()).startsWith("NAME_KEY_SHARED:");
        }
    }

    @Test
    void aClusterOrNameKeyWithTheKeyIdOfTheAuditKeyOrARetiredOneIsRefused() {
        for (String id : List.of("audit-1", "audit-0")) {
            DataPrismProperties clusterShared = keyedAudit();
            clusterShared.getHazelcast().getSecurity().getClusterKey().setKeyId(id);
            assertThat(catchThrowableOfType(DataPrismConfigurationException.class,
                    () -> ClusterKeyResolution.resolve(clusterShared, () -> auditResolver(Map.of()))).getMessage())
                    .startsWith("CLUSTER_KEY_SHARED:");
            DataPrismProperties nameShared = keyedAudit();
            nameShared.getHazelcast().getSecurity().getNameKey().setKeyId(id);
            assertThat(catchThrowableOfType(DataPrismConfigurationException.class,
                    () -> ClusterKeyResolution.resolve(nameShared, () -> auditResolver(Map.of()))).getMessage())
                    .startsWith("NAME_KEY_SHARED:");
        }
    }

    @Test
    void anAuditKeyThatCannotBeResolvedAgainIsRefusedWithoutItsReference() {
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(keyedAudit(), () -> auditResolver(Map.of("AUDIT_REF", new byte[0]))));
        assertThat(refused.getMessage()).startsWith("AUDIT_HMAC_KEY_TOO_SHORT:").doesNotContain("AUDIT_REF");
    }

    @Test
    void theAuditKeysAreResolvedOnceAndTheRecorderRetentionAndClusterChecksSeeTheFirstMaterial() {
        DataPrismProperties p = keyedAudit();
        Map<String, Integer> calls = new HashMap<>();
        HmacKeyReferenceResolver changing = (id, reference) -> {
            int n = calls.merge(reference, 1, Integer::sum);
            return switch (reference) {
                case "AUDIT_REF" -> bytes(n == 1 ? 'a' : 'z');
                case "RETIRED_REF" -> bytes(n == 1 ? 'r' : 'y');
                case "HMAC_REF" -> bytes('h');
                case "CLUSTER_REF" -> bytes('a');
                default -> bytes('n');
            };
        };
        AuditChainKeyResolution.Keys recorder = AuditChainKeyResolution.resolve(p, changing);
        AuditChainKeyResolution.Keys retention = AuditChainKeyResolution.resolve(p, changing);
        assertThat(retention).isSameAs(recorder);
        // the cluster key equals the audit key's FIRST material; a second resolution would have returned 'z'
        DataPrismConfigurationException refused = catchThrowableOfType(DataPrismConfigurationException.class,
                () -> ClusterKeyResolution.resolve(p, () -> changing));
        assertThat(refused.getMessage()).startsWith("CLUSTER_KEY_SHARED:");
        assertThat(calls).containsEntry("AUDIT_REF", 1).containsEntry("RETIRED_REF", 1);
    }
}
