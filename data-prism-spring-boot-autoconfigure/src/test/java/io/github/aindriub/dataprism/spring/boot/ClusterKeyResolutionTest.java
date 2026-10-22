package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
                () -> ClusterKeyResolution.resolve(properties(), resolver));
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
                () -> ClusterKeyResolution.resolve(properties(), resolver));
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
        ClusterKeys keys = ClusterKeyResolution.resolve(properties(), caching).orElseThrow();
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
                () -> ClusterKeyResolution.resolve(properties(), (id, ref) -> cache.get(ref)));
        assertThat(refused.getMessage()).startsWith("CLUSTER_KEY_WEAK:");
        assertThat(weak).containsExactly(1, 2, 3);
    }
}
