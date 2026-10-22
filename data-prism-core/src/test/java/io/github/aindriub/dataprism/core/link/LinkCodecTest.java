package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.link.LinkOpenResult.Opened;
import io.github.aindriub.dataprism.core.link.LinkOpenResult.Refused;
import io.github.aindriub.dataprism.core.link.LinkTestSupport.MapKeys;
import io.github.aindriub.dataprism.core.link.LinkTestSupport.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.aindriub.dataprism.core.link.LinkTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class LinkCodecTest {

    private TestClock clock;
    private MapKeys provider;
    private LinkCodec codec;
    private final LinkBinding binding = binding("case-1", "alice");

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        provider = new MapKeys().with("k1", material(1, 32)).with("ka", material(7, 32)).with("kb", material(7, 32))
                .with("pseudo-key", material(200, 32));
        codec = codec(provider, settings("k1", true, Duration.ofSeconds(30)), clock);
    }

    private String sealed(LinkPayload payload) {
        return codec.seal(payload, binding).orElseThrow().token();
    }

    private static Refused refused(LinkOpenResult result) {
        assertThat(result).isInstanceOf(Refused.class);
        return (Refused) result;
    }

    private static byte[] patched(String token, int index, int value) {
        byte[] raw = raw(token);
        raw[index] = (byte) value;
        return raw;
    }

    // ---- round trip ----

    @Test
    void followRoundTrips() {
        FollowPayload payload = follow(30);
        LinkOpenResult result = codec.open(sealed(payload), binding, follows());
        assertThat(result).isInstanceOf(Opened.class);
        Opened opened = (Opened) result;
        assertThat(opened.payload()).isEqualTo(payload);
        assertThat(opened.keyId()).isEqualTo("k1");
    }

    @Test
    void translatedFollowCarriesItsResolver() {
        FollowPayload payload = new FollowPayload(linkId(), T0, "ns-a", Optional.of("res-one"),
                material(3, 20), "source-two", LinkRelation.TRANSLATED);
        assertThat(((Opened) codec.open(sealed(payload), binding, follows())).payload()).isEqualTo(payload);
    }

    @Test
    void pageRoundTrips() {
        CatalogPagePayload payload = page();
        assertThat(((Opened) codec.open(sealed(payload), binding, pages())).payload()).isEqualTo(payload);
    }

    @Test
    void pageWithoutFiltersRoundTrips() {
        CatalogPagePayload payload = new CatalogPagePayload(linkId(), T0, Optional.empty(), Optional.empty(),
                LinkLabelKind.REAL, 1, "a", material(5, 16));
        assertThat(((Opened) codec.open(sealed(payload), binding, pages())).payload()).isEqualTo(payload);
    }

    // ---- verification order ----

    @Test
    @DisplayName("the length is checked before any decoding")
    void lengthBeforeDecoding() {
        String notBase64 = "!".repeat(1025);
        assertThat(refused(codec.open(notBase64, binding, follows())).reason()).isEqualTo(LinkReason.TOO_LONG);
        assertThat(refused(codec.open("!".repeat(1024), binding, follows())).reason())
                .isEqualTo(LinkReason.MALFORMED);
    }

    @Test
    void structuralFailuresAreMalformedAndCounted() {
        for (String token : List.of("", "A", "not base64!", "AQ", sealed(follow(5)) + "=",
                sealed(follow(5)).substring(0, 30))) {
            Refused refused = refused(codec.open(token, binding, follows()));
            assertThat(refused.reason()).as(token).isIn(LinkReason.MALFORMED);
            assertThat(refused.counted()).isTrue();
        }
        assertThat(refused(codec.open(null, binding, follows())).reason()).isEqualTo(LinkReason.MALFORMED);
    }

    @Test
    void nonCanonicalBase64IsMalformed() {
        // A raw length that is not a multiple of 3 leaves spare bits in the final character.
        String token = null;
        for (int targetLength = 1; targetLength <= 8 && token == null; targetLength++) {
            String candidate = sealed(follow(material(40, 5), "s".repeat(targetLength)));
            if (raw(candidate).length % 3 != 0) {
                token = candidate;
            }
        }
        assertThat(token).isNotNull();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = token.charAt(token.length() - 1);
        byte[] canonical = raw(token);
        int variants = 0;
        for (char candidate : alphabet.toCharArray()) {
            String variant = token.substring(0, token.length() - 1) + candidate;
            if (candidate != last && Arrays.equals(raw(variant), canonical)) {
                variants++;
                assertThat(refused(codec.open(variant, binding, follows())).reason())
                        .as("variant " + candidate).isEqualTo(LinkReason.MALFORMED);
            }
        }
        assertThat(variants).isPositive();
        assertThat(codec.open(token, binding, follows())).isInstanceOf(Opened.class);
    }

    @Test
    void wrongVersionIsVersion() {
        Refused refused = refused(codec.open(token(patched(sealed(follow(5)), 0, 2)), binding, follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.VERSION);
        assertThat(refused.counted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0x05, 0x06, 0x7F, 0x80, 0xFF})
    void reservedRelationBytesAreMalformed(int relation) {
        String token = sealed(follow(5));
        int relationAt = 1 + 1 + 2 + 8 + 1 + "source-one".length();
        Refused refused = refused(codec.open(token(patched(token, relationAt, relation)), binding, follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.MALFORMED);
    }

    @Test
    void forgedHeadersAreAuthFailedWhateverTheyClaim() {
        String token = sealed(follow(5));
        byte[] raw = raw(token);
        int expiryAt = 1 + 1 + 2;
        int targetAt = expiryAt + 8 + 1;
        int relationAt = targetAt + "source-one".length();

        byte[] expired = raw.clone();
        ByteBuffer.wrap(expired).putLong(expiryAt, T0.minusSeconds(1000).getEpochSecond());
        byte[] foreignTarget = raw.clone();
        foreignTarget[targetAt] ^= 0x01;
        byte[] otherRelation = raw.clone();
        otherRelation[relationAt] = (byte) LinkRelation.TRANSLATED.code();
        byte[] catalogRelation = raw.clone();
        catalogRelation[relationAt] = (byte) LinkRelation.CATALOG_PAGE.code();
        byte[] reservedRelation = raw.clone();
        reservedRelation[relationAt] = (byte) LinkRelation.GROUP.code();

        for (byte[] forged : List.of(expired, foreignTarget, otherRelation, catalogRelation, reservedRelation)) {
            Refused refused = refused(codec.open(token(forged), binding, follows()));
            assertThat(refused.reason()).isEqualTo(LinkReason.AUTH_FAILED);
            assertThat(refused.counted()).isTrue();
        }
        // A forged token with garbage ciphertext and a long-past expiry.
        byte[] garbage = raw.clone();
        Arrays.fill(garbage, garbage.length - 40, garbage.length, (byte) 0x5a);
        ByteBuffer.wrap(garbage).putLong(expiryAt, 1000L);
        assertThat(refused(codec.open(token(garbage), binding, follows())).reason())
                .isEqualTo(LinkReason.AUTH_FAILED);
    }

    @Test
    void genuineLinkInAnotherStateIsNeverCounted() {
        String token = sealed(follow(5));
        clock.advance(Duration.ofHours(1));
        Refused expired = refused(codec.open(token, binding, follows()));
        assertThat(expired.reason()).isEqualTo(LinkReason.EXPIRED);
        assertThat(expired.counted()).isFalse();
        clock.set(T0);
        Refused wrongTool = refused(codec.open(token, binding, pages()));
        assertThat(wrongTool.reason()).isEqualTo(LinkReason.WRONG_TOOL);
        assertThat(wrongTool.counted()).isFalse();
        assertThat(refused(codec.open(sealed(page()), binding, follows())).reason())
                .isEqualTo(LinkReason.WRONG_TOOL);
    }

    // ---- associated data ----

    @Test
    void keyIdIsInTheAssociatedData() {
        // ka and kb hold identical material, so only the associated data can tell the ids apart.
        LinkCodec underKa = codec(provider, settings("ka", true, Duration.ofSeconds(30)), clock);
        String token = underKa.seal(follow(5), binding).orElseThrow().token();
        assertThat(codec.open(token, binding, follows())).isInstanceOf(Opened.class);
        byte[] raw = raw(token);
        assertThat(raw[3]).isEqualTo((byte) 'a');
        raw[3] = 'b';
        assertThat(refused(codec.open(token(raw), binding, follows())).reason()).isEqualTo(LinkReason.AUTH_FAILED);
    }

    @Test
    void expiryIsInTheAssociatedData() {
        String token = sealed(follow(5));
        byte[] raw = raw(token);
        raw[1 + 1 + 2 + 7] ^= 0x01;
        assertThat(refused(codec.open(token(raw), binding, follows())).reason()).isEqualTo(LinkReason.AUTH_FAILED);
    }

    @Test
    void targetIsInTheAssociatedData() {
        String token = sealed(follow(5));
        byte[] raw = raw(token);
        raw[1 + 1 + 2 + 8 + 1 + 3] ^= 0x01;
        assertThat(refused(codec.open(token(raw), binding, follows())).reason()).isEqualTo(LinkReason.AUTH_FAILED);
    }

    @Test
    void relationIsInTheAssociatedData() {
        String token = sealed(follow(5));
        int relationAt = 1 + 1 + 2 + 8 + 1 + "source-one".length();
        byte[] raw = patched(token, relationAt, LinkRelation.TRANSLATED.code());
        assertThat(refused(codec.open(token(raw), binding, follows())).reason()).isEqualTo(LinkReason.AUTH_FAILED);
    }

    @Test
    void scopeIsInTheAssociatedDataAndTheKey() {
        String token = sealed(follow(5));
        Refused refused = refused(codec.open(token, binding("case-2", "alice"), follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.AUTH_FAILED);
        assertThat(refused.counted()).isTrue();
    }

    @Test
    void callerIsInTheAssociatedDataWhenBound() {
        String token = sealed(follow(5));
        assertThat(refused(codec.open(token, binding("case-1", "bob"), follows())).reason())
                .isEqualTo(LinkReason.AUTH_FAILED);

        LinkCodec unbound = codec(provider, settings("k1", false, Duration.ofSeconds(30)), clock);
        String free = unbound.seal(follow(5), binding).orElseThrow().token();
        assertThat(unbound.open(free, binding("case-1", "bob"), follows())).isInstanceOf(Opened.class);
    }

    @Test
    void anotherVersionByteIsRefusedBeforeAnyCryptography() {
        assertThat(refused(codec.open(token(patched(sealed(follow(5)), 0, 0)), binding, follows())).reason())
                .isEqualTo(LinkReason.VERSION);
    }

    static Stream<Integer> everyBytePosition() {
        int length = raw(new LinkCodecTest().sealedForPositions()).length;
        return IntStream.range(0, length).boxed();
    }

    String sealedForPositions() {
        setUp();
        return sealed(follow(20));
    }

    @ParameterizedTest
    @MethodSource("everyBytePosition")
    @DisplayName("a bit flipped at any byte position is refused")
    void bitFlipAtEveryPosition(int position) {
        setUp();
        byte[] raw = raw(sealed(follow(20)));
        raw[position] ^= (byte) (1 << (position % 8));
        LinkOpenResult result = codec.open(token(raw), binding, follows());
        assertThat(result).isInstanceOf(Refused.class);
        assertThat(((Refused) result).counted()).isTrue();
    }

    // ---- expiry ----

    private Instant expiryOf(String token) {
        return ((Opened) codec.open(token, binding, follows())).expiry();
    }

    @Test
    void expiryIsTheMinimumOfLifetimeScopeAndToken() {
        FollowPayload payload = follow(5);
        LinkBinding byLifetime = new LinkBinding("case-1", "alice", T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(2)));
        LinkBinding byScope = new LinkBinding("case-1", "alice", T0.plusSeconds(600), T0.plus(Duration.ofDays(2)));
        LinkBinding byToken = new LinkBinding("case-1", "alice", T0.plus(Duration.ofDays(2)), T0.plusSeconds(300));
        assertThat(codec.seal(payload, byLifetime).orElseThrow().expiry()).isEqualTo(T0.plusSeconds(1800));
        assertThat(codec.seal(payload, byScope).orElseThrow().expiry()).isEqualTo(T0.plusSeconds(600));
        assertThat(codec.seal(payload, byToken).orElseThrow().expiry()).isEqualTo(T0.plusSeconds(300));
    }

    @Test
    void scopeLifetimeNeverExceedsTheScope() {
        LinkSettings scope = new LinkSettings("k1", Optional.empty(), 1024, true, Duration.ofSeconds(30));
        LinkCodec scoped = codec(provider, scope, clock);
        LinkBinding byScope = new LinkBinding("case-1", "alice", T0.plusSeconds(5000), T0.plus(Duration.ofDays(2)));
        LinkBinding byToken = new LinkBinding("case-1", "alice", T0.plus(Duration.ofDays(2)), T0.plusSeconds(70));
        assertThat(scoped.seal(follow(5), byScope).orElseThrow().expiry()).isEqualTo(T0.plusSeconds(5000));
        assertThat(scoped.seal(follow(5), byToken).orElseThrow().expiry()).isEqualTo(T0.plusSeconds(70));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 30})
    void expiryBoundaryWithSkew(int skewSeconds) {
        Duration skew = Duration.ofSeconds(skewSeconds);
        LinkCodec c = codec(provider, settings("k1", true, skew), clock);
        String token = c.seal(follow(5), binding).orElseThrow().token();
        Instant expiry = T0.plusSeconds(1800);
        clock.set(expiry.plus(skew).minusSeconds(1));
        assertThat(c.open(token, binding, follows())).isInstanceOf(Opened.class);
        clock.set(expiry.plus(skew));
        assertThat(refused(c.open(token, binding, follows())).reason()).isEqualTo(LinkReason.EXPIRED);
        clock.set(expiry.plus(skew).plusSeconds(1));
        assertThat(refused(c.open(token, binding, follows())).reason()).isEqualTo(LinkReason.EXPIRED);
    }

    @Test
    void skewNeverExtendsTheScopeOrTheToken() {
        Instant later = T0.plus(Duration.ofDays(1));
        LinkBinding shortScope = new LinkBinding("case-1", "alice", T0.plusSeconds(10), later);
        LinkBinding shortToken = new LinkBinding("case-1", "alice", later, T0.plusSeconds(10));
        for (LinkBinding live : List.of(shortScope, shortToken)) {
            String token = codec.seal(follow(5), live).orElseThrow().token();
            clock.set(T0.plusSeconds(9));
            assertThat(codec.open(token, live, follows())).isInstanceOf(Opened.class);
            // 25 s is inside the 30 s skew of the link's own expiry, but past the scope or token end.
            clock.set(T0.plusSeconds(25));
            Refused refused = refused(codec.open(token, live, follows()));
            assertThat(refused.reason()).isEqualTo(LinkReason.EXPIRED);
            assertThat(refused.counted()).isFalse();
            clock.set(T0);
        }
    }

    @Test
    void aScopeOrTokenThatHasAlreadyEndedGetsNoLink() {
        Instant later = T0.plus(Duration.ofDays(1));
        for (LinkBinding ended : List.of(
                new LinkBinding("case-1", "alice", T0.minusSeconds(5), later),
                new LinkBinding("case-1", "alice", later, T0.minusSeconds(5)),
                new LinkBinding("case-1", "alice", T0, later))) {
            assertThat(codec.seal(follow(5), ended)).isEmpty();
            assertThat(codec.sealDetailed(follow(5), ended))
                    .isEqualTo(new SealResult.Omitted(SealResult.OmitReason.ALREADY_EXPIRED));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 30})
    void issueTimeBoundaryWithSkew(int skewSeconds) {
        Duration skew = Duration.ofSeconds(skewSeconds);
        LinkCodec c = codec(provider, settings("k1", true, skew), clock);
        for (int offset : new int[] {skewSeconds - 1, skewSeconds}) {
            FollowPayload issued = new FollowPayload(linkId(), T0.plusSeconds(offset), "ns-a", Optional.empty(),
                    material(4, 5), "source-one", LinkRelation.DIRECT);
            assertThat(c.open(c.seal(issued, binding).orElseThrow().token(), binding, follows()))
                    .as("offset " + offset).isInstanceOf(Opened.class);
        }
        FollowPayload future = new FollowPayload(linkId(), T0.plusSeconds(skewSeconds + 1L), "ns-a",
                Optional.empty(), material(4, 5), "source-one", LinkRelation.DIRECT);
        Refused refused = refused(c.open(c.seal(future, binding).orElseThrow().token(), binding, follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.ISSUED_IN_FUTURE);
        assertThat(refused.counted()).isFalse();
    }

    // ---- review: refusals after authentication are never counted ----

    @Test
    void anAuthenticatedPayloadThatDoesNotParseIsRefusedUncounted() throws Exception {
        byte[] raw = raw(sealed(follow(5)));
        byte[] truncated = LinkVectors.decrypt(material(1, 32), "case-1", "alice", true, raw);
        byte[] broken = Arrays.copyOf(truncated, truncated.length - 3);
        String token = token(LinkVectors.reseal(material(1, 32), "case-1", "alice", true, raw, broken));
        Refused refused = refused(codec.open(token, binding, follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.MALFORMED);
        assertThat(refused.counted()).isFalse();
    }

    @Test
    void aProviderFailureForAHeldIdIsAnUncountedRefusal() {
        MapKeys keys = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32))
                .with("pseudo-key", material(200, 32));
        LinkSettings issuing = settings("k2", true, Duration.ofSeconds(30));
        String token = new LinkCodec(issuing, new LinkKeys(keys, "k2", "pseudo-key", () -> Set.of("k1", "k2")),
                clock).seal(follow(5), binding).orElseThrow().token();
        LinkCodec member = new LinkCodec(settings("k1", true, Duration.ofSeconds(30)),
                new LinkKeys(keys, "k1", "pseudo-key", () -> Set.of("k1", "k2")), clock);
        assertThat(member.open(token, binding, follows())).isInstanceOf(Opened.class);

        keys.keys.remove("k2");
        Refused outage = refused(member.open(token, binding, follows()));
        assertThat(outage.reason()).isEqualTo(LinkReason.KEY_UNAVAILABLE);
        assertThat(outage.counted()).isFalse();

        keys.with("k2", material(2, 31));
        assertThat(refused(member.open(token, binding, follows())).counted()).isFalse();

        // An id this member never held is still unknown, and counted.
        keys.with("k3", material(3, 32));
        String foreign = new LinkCodec(settings("k3", true, Duration.ofSeconds(30)),
                new LinkKeys(keys, "k3", "pseudo-key"), clock).seal(follow(5), binding).orElseThrow().token();
        Refused unknown = refused(member.open(foreign, binding, follows()));
        assertThat(unknown.reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
        assertThat(unknown.counted()).isTrue();
    }

    @Test
    void sealingWhenTheCurrentKeyHasVanishedOmitsWithAReason() {
        MapKeys keys = new MapKeys().with("k1", material(1, 32)).with("pseudo-key", material(200, 32));
        LinkCodec c = new LinkCodec(settings("k1", true, Duration.ofSeconds(30)),
                new LinkKeys(keys, "k1", "pseudo-key"), clock);
        assertThat(c.sealDetailed(follow(5), binding)).isInstanceOf(SealResult.Issued.class);
        keys.keys.remove("k1");
        assertThat(c.sealDetailed(follow(5), binding))
                .isEqualTo(new SealResult.Omitted(SealResult.OmitReason.KEY_UNAVAILABLE));
        assertThat(c.seal(follow(5), binding)).isEmpty();
    }

    // ---- review: the payload rules ----

    @Test
    void aFollowPayloadObeysTheRelationRules() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FollowPayload(linkId(), T0, "ns-a",
                Optional.of("res-one"), material(3, 5), "source-one", LinkRelation.DIRECT))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FollowPayload(linkId(), T0, "ns-a",
                Optional.empty(), material(3, 5), "source-one", LinkRelation.TRANSLATED))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FollowPayload(linkId(), T0, "",
                Optional.empty(), material(3, 5), "source-one", LinkRelation.DIRECT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- review 2: bounds that must never throw ----

    @Test
    void anOversizeBindingIsRefusedUncountedAndOmittedNeverThrown() {
        String big = "x".repeat(65536);
        for (LinkBinding oversize : List.of(
                new LinkBinding(big, "alice", binding.scopeExpiry(), binding.tokenExpiry()),
                new LinkBinding("case-1", big, binding.scopeExpiry(), binding.tokenExpiry()))) {
            Refused refused = refused(codec.open(sealed(follow(5)), oversize, follows()));
            assertThat(refused.reason()).isEqualTo(LinkReason.MALFORMED);
            assertThat(refused.counted()).isFalse();
            assertThat(codec.sealDetailed(follow(5), oversize))
                    .isEqualTo(new SealResult.Omitted(SealResult.OmitReason.BINDING_TOO_LONG));
        }
        LinkBinding atLimit = new LinkBinding("x".repeat(65535), "y".repeat(65535), binding.scopeExpiry(),
                binding.tokenExpiry());
        String token = codec.seal(follow(5), atLimit).orElseThrow().token();
        assertThat(codec.open(token, atLimit, follows())).isInstanceOf(Opened.class);
    }

    @Test
    void aTargetOver64BytesOmitsInsteadOfThrowing() {
        FollowPayload longTarget = follow(material(40, 5), "t".repeat(65));
        assertThat(codec.sealDetailed(longTarget, binding))
                .isEqualTo(new SealResult.Omitted(SealResult.OmitReason.TARGET_TOO_LONG));
        assertThat(codec.sealDetailed(follow(material(40, 5), "t".repeat(64)), binding))
                .isInstanceOf(SealResult.Issued.class);
    }

    @Test
    void aKeyIdMismatchHasAStableCode() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LinkCodec(
                settings("k2", true, Duration.ofSeconds(30)),
                new LinkKeys(provider, "k1", "pseudo-key"), clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("INVALID_LINK_LIMIT");
    }

    // ---- rotation ----

    @Test
    void verificationUsesExactlyTheNamedKeyId() {
        List<String> asked = new ArrayList<>();
        MapKeys spy = new MapKeys() {
            @Override
            public byte[] secret(String keyId) {
                asked.add(keyId);
                return super.secret(keyId);
            }
        }.with("k1", material(1, 32)).with("k2", material(2, 32)).with("pseudo-key", material(200, 32));
        LinkCodec c = codec(spy, settings("k1", true, Duration.ofSeconds(30)), clock);
        String token = c.seal(follow(5), binding).orElseThrow().token();
        asked.clear();
        c.open(token, binding, follows());
        assertThat(asked).containsOnly("k1");
    }

    @Test
    void aProviderHoldingOneKeyAskedForAnotherRefusesUnknownKey() {
        MapKeys onlyNew = new MapKeys().with("k2", material(2, 32)).with("pseudo-key", material(200, 32));
        MapKeys both = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32))
                .with("pseudo-key", material(200, 32));
        String oldLink = codec(both, settings("k1", true, Duration.ofSeconds(30)), clock)
                .seal(follow(5), binding).orElseThrow().token();
        Refused refused = refused(codec(onlyNew, settings("k2", true, Duration.ofSeconds(30)), clock)
                .open(oldLink, binding, follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
        assertThat(refused.counted()).isTrue();
    }

    @Test
    void twoMemberRollingRotationVerifiesBothWays() {
        MapKeys a = new MapKeys().with("old", material(1, 32)).with("new", material(2, 32))
                .with("pseudo-key", material(200, 32));
        MapKeys b = new MapKeys().with("old", material(1, 32)).with("new", material(2, 32))
                .with("pseudo-key", material(200, 32));
        LinkCodec memberA = codec(a, settings("old", true, Duration.ofSeconds(30)), clock);
        LinkCodec memberB = codec(b, settings("new", true, Duration.ofSeconds(30)), clock);
        String fromA = memberA.seal(follow(5), binding).orElseThrow().token();
        String fromB = memberB.seal(follow(5), binding).orElseThrow().token();
        assertThat(memberB.open(fromA, binding, follows())).isInstanceOf(Opened.class);
        assertThat(memberA.open(fromB, binding, follows())).isInstanceOf(Opened.class);
        assertThat(memberA.open(fromA, binding, follows())).isInstanceOf(Opened.class);
        assertThat(memberB.open(fromB, binding, follows())).isInstanceOf(Opened.class);
        assertThat(((Opened) memberA.open(fromB, binding, follows())).keyId()).isEqualTo("new");
    }

    @Test
    void aMemberHoldingOnlyTheOldKeyRefusesANewKeyLinkAsUnknownKey() {
        MapKeys both = new MapKeys().with("old", material(1, 32)).with("new", material(2, 32))
                .with("pseudo-key", material(200, 32));
        MapKeys onlyOld = new MapKeys().with("old", material(1, 32)).with("pseudo-key", material(200, 32));
        String newLink = codec(both, settings("new", true, Duration.ofSeconds(30)), clock)
                .seal(follow(5), binding).orElseThrow().token();
        assertThat(refused(codec(onlyOld, settings("old", true, Duration.ofSeconds(30)), clock)
                .open(newLink, binding, follows())).reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
    }

    @Test
    void aTokenSealedUnderThePseudonymisationKeyIsUnknownKey() {
        MapKeys both = new MapKeys().with("k1", material(1, 32)).with("pseudo", material(9, 32));
        LinkCodec issuer = new LinkCodec(settings("pseudo", true, Duration.ofSeconds(30)),
                new LinkKeys(both, "pseudo", "pseudo-key"), clock);
        String token = issuer.seal(follow(5), binding).orElseThrow().token();
        java.util.Set<String> held = new java.util.HashSet<>(Set.of("k1"));
        LinkCodec verifier = new LinkCodec(settings("k1", true, Duration.ofSeconds(30)),
                new LinkKeys(both, "k1", "pseudo", () -> held), clock);
        assertThat(refused(verifier.open(token, binding, follows())).reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
        // The held set is read on every open: adding the pseudonymisation id later still refuses it.
        held.add("pseudo");
        assertThat(refused(verifier.open(token, binding, follows())).reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
    }

    @Test
    void aTokenUnderALaterAddedKeyWithThePseudonymisationBytesIsUnknownKeyAndCounted() {
        MapKeys keys = new MapKeys().with("k1", material(1, 32)).with("k2", material(200, 32))
                .with("other-pseudo", material(77, 32));
        String token = new LinkCodec(settings("k2", true, Duration.ofSeconds(30)),
                new LinkKeys(keys, "k2", "other-pseudo", () -> Set.of("k2")), clock)
                .seal(follow(5), binding).orElseThrow().token();
        java.util.Set<String> held = new java.util.HashSet<>(Set.of("k1"));
        LinkCodec verifier = new LinkCodec(settings("k1", true, Duration.ofSeconds(30)),
                new LinkKeys(keys, "k1", "pseudo-key", () -> held), clock);
        held.add("k2");
        Refused refused = refused(verifier.open(token, binding, follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
        assertThat(refused.counted()).isTrue();
    }

    @Test
    void aKeyTheProviderHoldsButHeldKeyIdsOmitsIsUnknownKey() {
        MapKeys both = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32));
        String token = new LinkCodec(settings("k2", true, Duration.ofSeconds(30)),
                new LinkKeys(both, "k2", "pseudo-key"), clock).seal(follow(5), binding).orElseThrow().token();
        LinkCodec verifier = new LinkCodec(settings("k1", true, Duration.ofSeconds(30)),
                new LinkKeys(both, "k1", "pseudo-key", () -> Set.of("k1")), clock);
        assertThat(refused(verifier.open(token, binding, follows())).reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
    }

    // ---- padding and size ----

    @Test
    void chainedKeysInOneBucketGiveEqualLengths() {
        assertThat(sealed(follow(1)).length()).isEqualTo(sealed(follow(64)).length());
        assertThat(sealed(follow(65)).length()).isGreaterThan(sealed(follow(64)).length());
        assertThat(sealed(follow(65)).length()).isEqualTo(sealed(follow(128)).length());
        assertThat(sealed(follow(256)).length()).isGreaterThan(sealed(follow(192)).length());
    }

    @Test
    void aKeyOver256BytesGetsNoLink() {
        assertThat(codec.seal(follow(257), binding)).isEmpty();
        assertThat(codec.sealDetailed(follow(257), binding))
                .isEqualTo(new SealResult.Omitted(SealResult.OmitReason.KEY_TOO_LONG));
        assertThat(codec.seal(follow(256), binding)).isPresent();
    }

    @Test
    void theRawBytesHoldNeitherTheChainedKeyNorAnySubjectId() {
        byte[] chained = "subject-7781-secret".getBytes(StandardCharsets.UTF_8);
        FollowPayload payload = follow(chained, "source-one");
        byte[] raw = raw(sealed(payload));
        assertThat(indexOf(raw, chained)).isNegative();
        assertThat(indexOf(raw, "subject-7781".getBytes(StandardCharsets.UTF_8))).isNegative();
        assertThat(indexOf(raw, "ns-a".getBytes(StandardCharsets.UTF_8))).isNegative();
    }

    @Test
    void openingThePayloadWithTheTestKeyFindsOnlyThePaddedChainedKey() throws Exception {
        byte[] chained = "subject-7781-secret".getBytes(StandardCharsets.UTF_8);
        byte[] raw = raw(sealed(follow(chained, "source-one")));
        byte[] plain = LinkVectors.decrypt(provider.secret("k1"), "case-1", "alice", true, raw);
        // link id 8, issue 8, depth 1, namespace 1+4, resolver 1+0, length 2, then 64 padded bytes.
        assertThat(plain).hasSize(8 + 8 + 1 + 5 + 1 + 2 + 64);
        int keyAt = 8 + 8 + 1 + 5 + 1 + 2;
        assertThat(Arrays.copyOfRange(plain, keyAt, keyAt + chained.length)).isEqualTo(chained);
        assertThat(Arrays.copyOfRange(plain, keyAt + chained.length, plain.length))
                .containsOnly((byte) 0);
        assertThat(ByteBuffer.wrap(plain).getShort(keyAt - 2)).isEqualTo((short) chained.length);
    }

    @Test
    void worstCaseLengthsFitTheDefault() {
        assertThat(LinkLayout.worstCaseFollowChars()).isEqualTo(616);
        assertThat(LinkLayout.worstCasePageChars()).isEqualTo(340);
        assertThat(LinkLayout.worstCaseFollowChars()).isLessThanOrEqualTo(1024);
        assertThat(LinkLayout.worstCasePageChars()).isLessThanOrEqualTo(1024);

        // And the real encoder agrees with the arithmetic.
        String id16 = "A".repeat(16);
        MapKeys wide = new MapKeys().with(id16, material(1, 32)).with("pseudo-key", material(200, 32));
        LinkCodec c = codec(wide, new LinkSettings(id16, Optional.of(Duration.ofMinutes(30)), 616, true,
                Duration.ZERO), clock);
        FollowPayload worstFollow = new FollowPayload(linkId(), T0, "n".repeat(32), Optional.of("r".repeat(32)),
                material(1, 256), "t".repeat(64), LinkRelation.TRANSLATED);
        // 461 bytes: 615 characters unpadded, inside the design's padded figure of 616.
        assertThat(c.seal(worstFollow, binding).orElseThrow().token()).hasSize(615);
        CatalogPagePayload worstPage = new CatalogPagePayload(linkId(), T0, Optional.of("e".repeat(64)),
                Optional.of("n".repeat(32)), LinkLabelKind.REAL, 65535, "l".repeat(64), material(1, 16));
        assertThat(c.seal(worstPage, binding).orElseThrow().token()).hasSize(340);
    }

    @Test
    void aHundredThousandSealsRepeatNoNonce() {
        Set<String> nonces = new HashSet<>();
        FollowPayload payload = follow(5);
        int nonceAt = 1 + 1 + 2 + 8 + 1 + "source-one".length() + 1;
        for (int i = 0; i < 100_000; i++) {
            byte[] raw = raw(sealed(payload));
            assertThat(nonces.add(token(Arrays.copyOfRange(raw, nonceAt, nonceAt + 12)))).isTrue();
        }
    }

    // ---- nothing sensitive in a message ----

    @Test
    void noFailurePathShowsAToken() {
        String token = sealed(follow(5));
        List<LinkOpenResult> results = List.of(
                codec.open(token + token, binding, follows()),
                codec.open("!!!" + token, binding, follows()),
                codec.open(token(patched(token, 0, 9)), binding, follows()),
                codec.open(token, binding("other", "alice"), follows()),
                codec.open(token, binding, pages()));
        for (LinkOpenResult result : results) {
            assertThat(result.toString()).doesNotContain(token).doesNotContain(SENTINEL);
        }
        assertThat(codec.toString()).doesNotContain("k1");
        assertThat(codec.seal(follow(5), binding).orElseThrow().toString()).doesNotContain(token);
        FollowPayload payload = follow("subject-9".getBytes(StandardCharsets.UTF_8), "source-one");
        assertThat(payload.toString()).doesNotContain("subject").doesNotContain("source-one");
        assertThat(page().toString()).doesNotContain("source-one");
        assertThat(binding.toString()).doesNotContain("alice").doesNotContain("case-1");
        assertThat(((Opened) codec.open(sealed(payload), binding, follows())).toString())
                .doesNotContain("subject");
    }

    @Test
    void anUnknownKeyNeverSurfacesTheProvidersMessage() {
        byte[] raw = raw(sealed(follow(5)));
        raw[2] = 'z';
        Refused refused = refused(codec.open(token(raw), binding, follows()));
        assertThat(refused.reason()).isEqualTo(LinkReason.UNKNOWN_KEY);
        assertThat(refused.toString()).doesNotContain(SENTINEL);
    }

    @Test
    void payloadConstructorsNameNoValue() {
        String secret = "subject-secret-value-" + "x".repeat(80);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> follow(secret.getBytes(StandardCharsets.UTF_8), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("subject-secret"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> follow(new byte[0], "source-one"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
