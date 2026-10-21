package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static io.github.aindriub.dataprism.hazelcast.seal.Fixtures.keys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SealerConcurrencyTest {

    private static final String MAP = "dataprism.budget.s1";

    @Test
    @DisplayName("T16: 100,000 seals across 16 threads under one salt repeat no (subkey, nonce) pair")
    void noRepeatedNonce() throws Exception {
        Sealer sealer = Sealer.builder(keys(), PrivacyMetrics.none()).build();
        int threads = 16;
        int perThread = 100_000 / threads + 1;
        Set<String> subkeyAndNonce = ConcurrentHashMap.newKeySet();
        Set<String> salts = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    int sealed = 0;
                    for (int i = 0; i < perThread && sealed + 0 < perThread; i++) {
                        String[] f = sealer.seal(MAP, "key", "v".getBytes(StandardCharsets.UTF_8)).split("\\.", -1);
                        // One map and one key id under one salt is one subkey, so the nonce alone must be unique.
                        subkeyAndNonce.add(f[1] + "." + f[2] + "." + f[3]);
                        salts.add(f[2]);
                        sealed++;
                    }
                    return sealed;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> r : results) {
                total += r.get();
            }
            assertThat(total).isGreaterThanOrEqualTo(100_000);
            assertThat(subkeyAndNonce).hasSize(total);
            assertThat(salts).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("T16: two sealers (a simulated restart) draw different salts")
    void restartDrawsANewSalt() {
        String a = Sealer.builder(keys(), PrivacyMetrics.none()).build().seal(MAP, "k", new byte[1]).split("\\.")[2];
        String b = Sealer.builder(keys(), PrivacyMetrics.none()).build().seal(MAP, "k", new byte[1]).split("\\.")[2];
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("the nonce counter refuses to wrap rather than repeat a nonce")
    void counterExhaustion() {
        Sealer sealer = Sealer.builder(keys(), PrivacyMetrics.none()).initialCounter(Long.MAX_VALUE - 1).build();
        sealer.seal(MAP, "k", new byte[1]);
        assertThatThrownBy(() -> sealer.seal(MAP, "k", new byte[1]))
                .isInstanceOf(io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException.class);
    }
}
