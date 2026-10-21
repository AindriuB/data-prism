package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.seal.SealerTestSupport.CountingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.aindriub.dataprism.hazelcast.seal.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SealerTest {

    private static final String MAP = "dataprism.approval.s1";
    private static final String OTHER_MAP = "dataprism.budget.s1";
    private static final String KEY = "scopetoken.keyedname";
    private static final byte[] PLAIN = "plaintext-entry".getBytes(StandardCharsets.UTF_8);

    private CountingMetrics metrics;
    private Sealer sealer;

    @BeforeEach
    void setUp() {
        metrics = new CountingMetrics();
        sealer = Sealer.builder(keys(), metrics).build();
    }

    private void assertOnly(Metric expected) {
        assertThat(metrics.all().entrySet().stream().filter(e -> e.getValue().get() > 0).map(e -> e.getKey()))
                .containsExactly(expected);
    }

    private static String replaceField(String envelope, int index, String value) {
        String[] f = envelope.split("\\.", -1);
        f[index] = value;
        return String.join(".", f);
    }

    private static String flipFirstBit(String field) {
        byte[] raw = Base64.getUrlDecoder().decode(field);
        raw[0] ^= 1;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Test
    @DisplayName("an envelope has five fields: dps1, key id, salt, nonce, ciphertext+tag")
    void envelopeGrammar() {
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        String[] f = envelope.split("\\.", -1);
        assertThat(f).hasSize(5);
        assertThat(f[0]).isEqualTo("dps1");
        assertThat(f[1]).isEqualTo("k1");
        assertThat(Base64.getUrlDecoder().decode(f[2])).hasSize(16);
        assertThat(Base64.getUrlDecoder().decode(f[3])).hasSize(12);
        assertThat(Base64.getUrlDecoder().decode(f[4])).hasSize(PLAIN.length + 16);
        assertThat(envelope).doesNotContain("plaintext").doesNotContain("=");
    }

    @Test
    @DisplayName("a sealed value opens to the same bytes, from another sealer sharing the keys")
    void roundTrip() {
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        Sealer peer = Sealer.builder(keys(), metrics).build();
        Opened opened = peer.open(MAP, KEY, envelope);
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(opened.keyId()).isEqualTo("k1");
        assertThat(opened.plaintext()).isEqualTo(PLAIN);
        assertThat(metrics.all()).isEmpty();
    }

    @Test
    @DisplayName("an empty plaintext round-trips")
    void emptyPlaintext() {
        Opened opened = sealer.open(MAP, KEY, sealer.seal(MAP, KEY, new byte[0]));
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(opened.plaintext()).isEmpty();
    }

    @Test
    @DisplayName("T2: another map name is tampered")
    void otherMap() {
        Opened opened = sealer.open(OTHER_MAP, KEY, sealer.seal(MAP, KEY, PLAIN));
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.TAMPERED);
        assertOnly(Metric.CLUSTER_SEAL_TAMPER);
    }

    @Test
    @DisplayName("T2: another map key is tampered")
    void otherMapKey() {
        Opened opened = sealer.open(MAP, "other.key", sealer.seal(MAP, KEY, PLAIN));
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.TAMPERED);
        assertOnly(Metric.CLUSTER_SEAL_TAMPER);
    }

    @Test
    @DisplayName("T2: a flipped bit in the salt, nonce or ciphertext is tampered")
    void flippedBits() {
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        for (int field : new int[] {2, 3, 4}) {
            String[] f = envelope.split("\\.", -1);
            String bad = replaceField(envelope, field, flipFirstBit(f[field]));
            Opened opened = sealer.open(MAP, KEY, bad);
            assertThat(opened.outcome()).as("field %d", field).isEqualTo(Opened.Outcome.TAMPERED);
            assertThat(opened.plaintext()).isEmpty();
        }
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isEqualTo(3);
        assertThat(metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT)).isZero();
    }

    @Test
    @DisplayName("T2: a flipped bit in the key id or version is unsupported, not an exception")
    void flippedVersionAndKeyId() {
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        assertThat(sealer.open(MAP, KEY, replaceField(envelope, 1, "k3")).outcome())
                .isEqualTo(Opened.Outcome.UNSUPPORTED);
        assertThat(sealer.open(MAP, KEY, replaceField(envelope, 0, "dps3")).outcome())
                .isEqualTo(Opened.Outcome.UNSUPPORTED);
        assertThat(metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT)).isEqualTo(2);
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isZero();
    }

    @Test
    @DisplayName("T2: a key id swapped for another held key id fails to authenticate")
    void swappedKeyId() {
        Sealer rotating = Sealer.builder(rotatingKeys(), metrics).build();
        String envelope = rotating.seal(MAP, KEY, PLAIN);
        Opened opened = rotating.open(MAP, KEY, replaceField(envelope, 1, "k2"));
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.TAMPERED);
    }

    @Test
    @DisplayName("T2: a truncated envelope is tampered")
    void truncated() {
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        assertThat(sealer.open(MAP, KEY, envelope.substring(0, envelope.length() - 5)).outcome())
                .isEqualTo(Opened.Outcome.TAMPERED);
        String[] f = envelope.split("\\.", -1);
        assertThat(sealer.open(MAP, KEY, String.join(".", Arrays.copyOf(f, 4))).outcome())
                .isEqualTo(Opened.Outcome.TAMPERED);
        assertThat(sealer.open(MAP, KEY, replaceField(envelope, 4, "AA")).outcome())
                .isEqualTo(Opened.Outcome.TAMPERED);
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isEqualTo(3);
    }

    @Test
    @DisplayName("T2: an unknown format version (dps2) is unsupported")
    void unknownVersion() {
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        Opened opened = sealer.open(MAP, KEY, "dps2" + envelope.substring(4));
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.UNSUPPORTED);
        assertOnly(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
    }

    @Test
    @DisplayName("T2: an unknown key id is unsupported")
    void unknownKeyId() {
        Sealer other = Sealer.builder(
                new ClusterKeys("zz", bytes(9, 32), null, null, null, null, NAME_ID, nameKey()), metrics).build();
        Opened opened = sealer.open(MAP, KEY, other.seal(MAP, KEY, PLAIN));
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.UNSUPPORTED);
        assertOnly(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
    }

    @Test
    @DisplayName("a same-id key with other material is tampered (a mixed-key deployment)")
    void sameIdOtherMaterial() {
        Sealer other = Sealer.builder(
                new ClusterKeys("k1", bytes(9, 32), null, null, null, null, NAME_ID, nameKey()), metrics).build();
        assertThat(sealer.open(MAP, KEY, other.seal(MAP, KEY, PLAIN)).outcome()).isEqualTo(Opened.Outcome.TAMPERED);
    }

    @Test
    @DisplayName("garbage, null and empty input are non-authentic and never throw")
    void garbage() {
        for (String junk : new String[] {null, "", ".", "....", "dps1", "dps1....", "not an envelope", "dps1.k1.!!.!!.!!"}) {
            assertThat(sealer.open(MAP, KEY, junk).outcome()).isNotEqualTo(Opened.Outcome.AUTHENTIC);
        }
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER) + metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT))
                .isEqualTo(8);
    }

    @Test
    @DisplayName("every held key opens; the write key is the current one by default")
    void readsEveryHeldKey() {
        Sealer old = Sealer.builder(
                new ClusterKeys("k0", previousKey(), null, null, null, null, NAME_ID, nameKey()), metrics).build();
        Sealer rotating = Sealer.builder(rotatingKeys(), metrics).build();
        assertThat(rotating.open(MAP, KEY, old.seal(MAP, KEY, PLAIN)).keyId()).isEqualTo("k0");
        assertThat(rotating.open(MAP, KEY, rotating.seal(MAP, KEY, PLAIN)).keyId()).isEqualTo("k1");
    }

    @Test
    @DisplayName("a write-key policy chooses the key; a refusing policy counts rotation.blocked and surfaces the 0.7 code")
    void writeKeyPolicy() {
        Sealer fallback = Sealer.builder(rotatingKeys(), metrics)
                .writeKeyPolicy(keys -> keys.previous().orElseThrow()).build();
        assertThat(fallback.open(MAP, KEY, fallback.seal(MAP, KEY, PLAIN)).keyId()).isEqualTo("k0");

        Sealer blocked = Sealer.builder(rotatingKeys(), metrics).writeKeyPolicy(WriteKeyPolicy.refusing()).build();
        assertThatThrownBy(() -> blocked.seal(MAP, KEY, PLAIN)).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(metrics.count(Metric.CLUSTER_ROTATION_BLOCKED)).isEqualTo(1);
        assertThat(blocked.open(MAP, KEY, fallback.seal(MAP, KEY, PLAIN)).outcome())
                .as("a blocked write does not stop reads").isEqualTo(Opened.Outcome.AUTHENTIC);
    }

    @Test
    @DisplayName("a tamper is reported to the listener with the map name and nothing about the entry")
    void tamperListener() {
        List<String> reported = new ArrayList<>();
        Sealer listening = Sealer.builder(keys(), metrics).tamperListener(reported::add).build();
        String envelope = listening.seal(MAP, KEY, PLAIN);
        listening.open(OTHER_MAP, KEY, envelope);
        listening.open(MAP, KEY, "dps2.x");
        assertThat(reported).containsExactly(OTHER_MAP);
    }

    @Test
    @DisplayName("a listener that throws cannot change the result")
    void throwingListener() {
        Sealer listening = Sealer.builder(keys(), metrics).tamperListener(map -> { throw new IllegalStateException(); }).build();
        assertThat(listening.open(OTHER_MAP, KEY, listening.seal(MAP, KEY, PLAIN)).outcome())
                .isEqualTo(Opened.Outcome.TAMPERED);
    }

    @Test
    @DisplayName("a closed serving gate refuses seal and open with the cluster refusal")
    void servingGate() {
        AtomicBoolean serving = new AtomicBoolean(true);
        Sealer gated = Sealer.builder(keys(), metrics).servingGate(serving::get).build();
        String envelope = gated.seal(MAP, KEY, PLAIN);
        serving.set(false);
        assertThatThrownBy(() -> gated.seal(MAP, KEY, PLAIN)).isInstanceOf(ClusterOperationRefusedException.class);
        assertThatThrownBy(() -> gated.open(MAP, KEY, envelope)).isInstanceOf(ClusterOperationRefusedException.class);
        serving.set(true);
        assertThat(gated.open(MAP, KEY, envelope).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
    }

    @Test
    @DisplayName("a JCA failure in open gives a constant message with no exception text or input")
    void jcaFailureInOpen() {
        String secretText = "SECRET-PROVIDER-TEXT";
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        Sealer failing = Sealer.builder(keys(), metrics)
                .cipherSource(() -> { throw new GeneralSecurityException(secretText + KEY + MAP); })
                .build();
        Opened opened = failing.open(MAP, KEY, envelope);
        assertThat(opened.outcome()).isNotEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(opened.message()).isEqualTo(Opened.UNAUTHENTIC_MESSAGE)
                .doesNotContain(secretText).doesNotContain(KEY).doesNotContain(MAP);
        assertThat(opened.toString()).doesNotContain(secretText).doesNotContain(KEY);
        assertThat(opened.plaintext()).isEmpty();
    }

    @Test
    @DisplayName("a JCA failure in seal is a cluster refusal whose message and cause carry no provider text")
    void jcaFailureInSeal() {
        String secretText = "SECRET-PROVIDER-TEXT";
        Sealer failing = Sealer.builder(keys(), metrics)
                .cipherSource(() -> { throw new GeneralSecurityException(secretText + KEY); })
                .build();
        assertThatThrownBy(() -> failing.seal(MAP, KEY, PLAIN))
                .isInstanceOf(ClusterOperationRefusedException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(secretText).doesNotContain(KEY);
                    assertThat(e.getCause().getMessage()).doesNotContain(secretText).doesNotContain(KEY);
                    assertThat(e.getCause()).hasNoCause();
                });
    }

    @Test
    @DisplayName("a runtime fault in the provider is contained at the boundary too")
    void runtimeFaultInOpen() {
        String envelope = sealer.seal(MAP, KEY, PLAIN);
        Sealer failing = Sealer.builder(keys(), metrics)
                .cipherSource(() -> { throw new IllegalStateException("boom " + KEY); }).build();
        assertThat(failing.open(MAP, KEY, envelope).outcome()).isNotEqualTo(Opened.Outcome.AUTHENTIC);
    }

    private static String forgedSalt(String envelope, int i) {
        String[] f = envelope.split("\\.", -1);
        f[2] = b64(java.nio.ByteBuffer.allocate(16).putLong(i).putLong(0x5A5A).array());
        return String.join(".", f);
    }

    @Test
    @DisplayName("a spray of forged salts never enters or evicts the cache, and the derivation limiter bounds the work")
    void forgedSaltSpray() {
        MutableClock clock = new MutableClock();
        Sealer reader = Sealer.builder(keys(), metrics).clock(clock).saltCacheBound(2)
                .saltDerivationsPerWindow(50).build();
        Sealer peer = Sealer.builder(keys(), metrics).build();
        String real = peer.seal(MAP, KEY, PLAIN);
        assertThat(reader.open(MAP, KEY, real).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(reader.saltCacheSize()).isEqualTo(1);

        int rejected = 0;
        for (int i = 0; i < 10_000; i++) {
            Opened opened = reader.open(MAP, KEY, forgedSalt(real, i));
            if (opened.outcome() == Opened.Outcome.UNSUPPORTED) {
                rejected++;
            }
        }
        assertThat(reader.saltCacheSize()).as("forged salts never enter the cache").isEqualTo(1);
        assertThat(metrics.count(Metric.CLUSTER_SEAL_SALT_REJECTED)).isEqualTo(rejected).isGreaterThan(9_900);
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).as("derivations are bounded").isLessThanOrEqualTo(50);
        assertThat(reader.open(MAP, KEY, real).outcome()).as("the authenticated peer salt is still cached")
                .isEqualTo(Opened.Outcome.AUTHENTIC);
    }

    @Test
    @DisplayName("authenticated peer salts fill the cache to its bound and the least recently used one is evicted")
    void lruEviction() {
        MutableClock clock = new MutableClock();
        Sealer reader = Sealer.builder(keys(), metrics).clock(clock).saltCacheBound(3)
                .saltDerivationsPerWindow(5).build();
        List<String> envelopes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            envelopes.add(Sealer.builder(keys(), metrics).build().seal(MAP, KEY, PLAIN));
        }
        for (int i = 0; i < 3; i++) {
            assertThat(reader.open(MAP, KEY, envelopes.get(i)).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        }
        assertThat(reader.saltCacheSize()).isEqualTo(3);
        // A hit makes peer 0 the most recently used (no derivation spent).
        assertThat(reader.open(MAP, KEY, envelopes.get(0)).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(reader.open(MAP, KEY, envelopes.get(3)).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(reader.open(MAP, KEY, envelopes.get(4)).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(reader.saltCacheSize()).as("bounded").isEqualTo(3);

        // All five derivations of this window are spent, so only a cached salt can still open.
        assertThat(reader.open(MAP, KEY, envelopes.get(0)).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(reader.open(MAP, KEY, envelopes.get(1)).outcome()).as("evicted").isEqualTo(Opened.Outcome.UNSUPPORTED);
        assertThat(reader.open(MAP, KEY, envelopes.get(2)).outcome()).as("evicted").isEqualTo(Opened.Outcome.UNSUPPORTED);
        clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(reader.open(MAP, KEY, envelopes.get(1)).outcome()).as("re-derived").isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(reader.saltCacheSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unknown real salt is refused over the limit, and admitted again in the next window")
    void limiterRecovers() {
        MutableClock clock = new MutableClock();
        Sealer reader = Sealer.builder(keys(), metrics).clock(clock).saltDerivationsPerWindow(3).build();
        String real = Sealer.builder(keys(), metrics).build().seal(MAP, KEY, PLAIN);
        for (int i = 0; i < 3; i++) {
            reader.open(MAP, KEY, forgedSalt(real, i));
        }
        assertThat(reader.open(MAP, KEY, real).outcome()).isEqualTo(Opened.Outcome.UNSUPPORTED);
        clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(reader.open(MAP, KEY, real).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
    }

    @Test
    @DisplayName("a clock stepped back resets the limiter window")
    void clockStepBack() {
        MutableClock clock = new MutableClock();
        Sealer reader = Sealer.builder(keys(), metrics).clock(clock).saltDerivationsPerWindow(1).build();
        String real = Sealer.builder(keys(), metrics).build().seal(MAP, KEY, PLAIN);
        clock.advance(java.time.Duration.ofHours(1));
        reader.open(MAP, KEY, forgedSalt(real, 1));
        assertThat(reader.open(MAP, KEY, real).outcome()).isEqualTo(Opened.Outcome.UNSUPPORTED);
        clock.advance(java.time.Duration.ofHours(-2));
        assertThat(reader.open(MAP, KEY, real).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
    }

    @Test
    @DisplayName("a policy that throws a runtime fault is a blocked write with no policy text")
    void throwingPolicy() {
        Sealer s = Sealer.builder(keys(), metrics)
                .writeKeyPolicy(k -> { throw new IllegalStateException("POLICY-SECRET"); }).build();
        assertThatThrownBy(() -> s.seal(MAP, KEY, PLAIN)).isInstanceOf(ClusterOperationRefusedException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("POLICY-SECRET");
                    assertThat(e.getCause().getMessage()).doesNotContain("POLICY-SECRET");
                });
        assertThat(metrics.count(Metric.CLUSTER_ROTATION_BLOCKED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a key from another ClusterKeys with a held id is refused: only held material seals")
    void foreignKeyWithHeldId() {
        ClusterKeys.Key foreign = new ClusterKeys("k1", bytes(9, 32), null, null, null, null, NAME_ID, nameKey())
                .current();
        Sealer s = Sealer.builder(keys(), metrics).writeKeyPolicy(k -> foreign).build();
        assertThatThrownBy(() -> s.seal(MAP, KEY, PLAIN)).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(metrics.count(Metric.CLUSTER_ROTATION_BLOCKED)).isEqualTo(1);
        Sealer honest = Sealer.builder(keys(), metrics).build();
        assertThat(honest.open(MAP, KEY, honest.seal(MAP, KEY, PLAIN)).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
