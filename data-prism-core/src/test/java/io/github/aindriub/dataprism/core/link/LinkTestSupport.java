package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Shared fixtures. Every key, id and name here is invented. */
final class LinkTestSupport {

    static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    static final String SENTINEL = "SENTINEL-REF-7f3a";

    private LinkTestSupport() {}

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(Duration by) {
            this.now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A provider over a map; an unknown id throws, with a message that names a sentinel reference. */
    static class MapKeys implements SecretKeyProvider {
        final Map<String, byte[]> keys = new LinkedHashMap<>();

        MapKeys with(String id, byte[] material) {
            keys.put(id, material);
            return this;
        }

        @Override
        public byte[] secret(String keyId) {
            byte[] material = keys.get(keyId);
            if (material == null) {
                throw new IllegalStateException("no key " + SENTINEL + " for " + keyId);
            }
            return material.clone();
        }
    }

    static byte[] material(int seed, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 7);
        }
        return out;
    }

    static LinkSettings settings(String keyId, boolean bind, Duration skew) {
        return new LinkSettings(keyId, Optional.of(Duration.ofMinutes(30)), 1024, bind, skew);
    }

    static LinkCodec codec(MapKeys provider, LinkSettings settings, Clock clock) {
        LinkKeys keys = new LinkKeys(provider, settings.keyId(), "pseudo-key",
                () -> Set.copyOf(provider.keys.keySet()));
        return new LinkCodec(settings, keys, clock);
    }

    static LinkBinding binding(String scope, String principal) {
        return new LinkBinding(scope, principal, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)));
    }

    static byte[] linkId() {
        return new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    }

    static FollowPayload follow(byte[] chainedKey, String target) {
        return new FollowPayload(linkId(), T0, "ns-a", Optional.empty(), chainedKey, target, LinkRelation.DIRECT);
    }

    static FollowPayload follow(int keyLength) {
        return follow(material(40, keyLength), "source-one");
    }

    static CatalogPagePayload page() {
        return new CatalogPagePayload(linkId(), T0, Optional.of("thing"), Optional.of("ns-a"),
                LinkLabelKind.ALIAS, 25, "source-one", material(90, 16));
    }

    static byte[] raw(String token) {
        return Base64.getUrlDecoder().decode(token);
    }

    static String token(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static Set<LinkRelation> follows() {
        return Set.of(LinkRelation.DIRECT, LinkRelation.TRANSLATED);
    }

    static Set<LinkRelation> pages() {
        return Set.of(LinkRelation.CATALOG_PAGE);
    }

    static <T> Supplier<T> constant(T value) {
        return () -> value;
    }

    static java.util.List<String> noLabels() {
        return new ArrayList<>();
    }
}
