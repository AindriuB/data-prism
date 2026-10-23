package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.link.LinkTestSupport.MapKeys;
import org.junit.jupiter.api.Test;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static io.github.aindriub.dataprism.core.link.LinkTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkKeysTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String SCOPE = "case-1";

    // ---- construction ----

    @Test
    void anUnresolvableKeyIdRefuses() {
        MapKeys empty = new MapKeys();
        assertThatThrownBy(() -> new LinkKeys(empty, "k1", "pseudo-key"))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_UNRESOLVED")
                        .doesNotContain(SENTINEL).doesNotContain("k1"))
                .hasNoCause();
    }

    @Test
    void aNullFromTheProviderIsUnresolved() {
        assertThatThrownBy(() -> new LinkKeys(id -> null, "k1", "pseudo-key"))
                .hasMessageStartingWith("LINK_KEY_UNRESOLVED");
    }

    @Test
    void materialUnder32BytesRefuses() {
        MapKeys weak = new MapKeys().with("k1", material(1, 31));
        assertThatThrownBy(() -> new LinkKeys(weak, "k1", "pseudo-key"))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_WEAK"));
        new LinkKeys(new MapKeys().with("k1", material(1, 32)), "k1", "pseudo-key");
    }

    @Test
    void theSameIdAsThePseudonymisationKeyRefuses() {
        MapKeys keys = new MapKeys().with("shared", material(1, 32));
        assertThatThrownBy(() -> new LinkKeys(keys, "shared", "shared"))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_SHARED").doesNotContain("shared-"));
    }

    @Test
    void aHeldSetContainingThePseudonymisationKeyRefuses() {
        MapKeys keys = new MapKeys().with("k1", material(1, 32)).with("pseudo", material(2, 32));
        assertThatThrownBy(() -> new LinkKeys(keys, "k1", "pseudo", () -> Set.of("k1", "pseudo")))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_SHARED"));
    }

    @Test
    void aHugeLifetimeSaturatesInsteadOfOverflowing() {
        LinkSettings huge = new LinkSettings("k1", Optional.of(Duration.ofSeconds(Long.MAX_VALUE)), 1024, true,
                Duration.ZERO);
        MapKeys provider = new MapKeys().with("k1", material(1, 32));
        LinkTestSupport.TestClock clock = new LinkTestSupport.TestClock(T0);
        LinkCodec codec = new LinkCodec(huge, new LinkKeys(provider, "k1", "pseudo-key"), clock);
        LinkBinding binding = binding("case-1", "alice");
        SealedLink link = codec.seal(follow(5), binding).orElseThrow();
        // The scope and token ends bind, as for any lifetime.
        assertThat(link.expiry()).isEqualTo(T0.plus(Duration.ofDays(1)));
    }

    @Test
    void aHeldSetThatReturnsNullOrThrowsMeansTheCurrentIdOnly() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32));
        assertThat(new LinkKeys(provider, "k1", "pseudo-key", () -> null).heldKeyIds()).containsExactly("k1");
        LinkKeys throwing = new LinkKeys(provider, "k1", "pseudo-key", () -> {
            throw new IllegalStateException(SENTINEL);
        });
        assertThat(throwing.heldKeyIds()).containsExactly("k1");
        assertThat(throwing.master("k2")).isNull();
        assertThat(throwing.master("k1")).isNotNull();
    }

    // ---- review: held ids are resolved and length-checked ----

    @Test
    void aHeldIdThatDoesNotResolveRefusesAtConstruction() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32));
        assertThatThrownBy(() -> new LinkKeys(provider, "k1", "pseudo-key", () -> Set.of("k1", "k2")))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_UNRESOLVED")
                        .doesNotContain(SENTINEL).doesNotContain("k2"))
                .hasNoCause();
    }

    @Test
    void aWeakHeldIdRefusesAtConstruction() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 31));
        assertThatThrownBy(() -> new LinkKeys(provider, "k1", "pseudo-key", () -> Set.of("k1", "k2")))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_WEAK").doesNotContain("k2"));
    }

    @Test
    void heldKeyIdsDropsAnIdThatStopsResolvingOrBecomesWeak() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32))
                .with("k3", material(3, 32));
        LinkKeys keys = new LinkKeys(provider, "k1", "pseudo-key", () -> Set.of("k1", "k2", "k3"));
        assertThat(keys.heldKeyIds()).containsExactly("k1", "k2", "k3");
        provider.keys.remove("k2");
        provider.keys.put("k3", material(3, 31));
        assertThat(keys.heldKeyIds()).containsExactly("k1");
        assertThat(keys.master("k2")).isNull();
        assertThat(keys.master("k3")).isNull();
    }

    // ---- review: the pseudonymisation key ----

    @Test
    void aNullPseudonymisationKeyIdRefuses() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32));
        assertThatThrownBy(() -> new LinkKeys(provider, "k1", null))
                .isInstanceOf(LinkConfigurationException.class).hasMessageStartingWith("LINK_KEY_SHARED");
        assertThatThrownBy(() -> new LinkKeys(provider, "k1", null, () -> Set.of("k1")))
                .hasMessageStartingWith("LINK_KEY_SHARED");
    }

    @Test
    void anUnresolvablePseudonymisationKeyRefuses() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32));
        assertThatThrownBy(() -> new LinkKeys(provider, "k1", "pseudo-ref-9"))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_UNRESOLVED")
                        .doesNotContain("pseudo-ref-9").doesNotContain(SENTINEL))
                .hasNoCause();
        assertThatThrownBy(() -> new LinkKeys(id -> "k1".equals(id) ? material(1, 32) : null, "k1", "pseudo-key"))
                .hasMessageStartingWith("LINK_KEY_UNRESOLVED");
    }

    @Test
    void aHeldKeyWithTheMaterialOfThePseudonymisationKeyRefuses() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("k2", material(9, 32))
                .with("pseudo", material(9, 32));
        assertThatThrownBy(() -> new LinkKeys(provider, "k1", "pseudo", () -> Set.of("k1", "k2")))
                .isInstanceOf(LinkConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_SHARED"));
        MapKeys current = new MapKeys().with("k1", material(9, 32)).with("pseudo", material(9, 32));
        assertThatThrownBy(() -> new LinkKeys(current, "k1", "pseudo"))
                .hasMessageStartingWith("LINK_KEY_SHARED");
    }

    @Test
    void aKeyAddedLaterWithThePseudonymisationBytesIsNotHeld() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("k2", material(200, 32));
        Set<String> held = new HashSet<>(Set.of("k1"));
        LinkKeys keys = new LinkKeys(provider, "k1", "pseudo-key", () -> held);
        held.add("k2");
        assertThat(keys.heldKeyIds()).containsExactly("k1");
        assertThat(keys.master("k2")).isNull();
        assertThat(keys.lookup("k2").status()).isEqualTo(LinkKeys.Status.NOT_HELD);
        assertThat(keys.toString()).doesNotContain("k2");
    }

    // ---- review: a null entry in the held set is absent ----

    @Test
    void aNullEntryInTheHeldSetIsTreatedAsAbsent() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32));
        Set<String> withNull = new HashSet<>(Arrays.asList("k1", "k2", null));
        LinkKeys keys = new LinkKeys(provider, "k1", "pseudo-key", () -> withNull);
        assertThat(keys.heldKeyIds()).containsExactly("k1", "k2");
        assertThat(keys.master(null)).isNull();
    }

    @Test
    void theConstructorDoesNotMutateTheProvidersArray() {
        byte[] held = material(1, 32);
        byte[] before = held.clone();
        new LinkKeys(id -> "k1".equals(id) ? held : material(200, 32), "k1", "pseudo-key");
        assertThat(held).isEqualTo(before);
    }

    @Test
    void settingsLimitsAreValidated() {
        assertThatThrownBy(() -> new LinkSettings("k1", Optional.empty(), 615, true, Duration.ZERO))
                .hasMessageStartingWith("INVALID_LINK_LIMIT");
        assertThatThrownBy(() -> new LinkSettings("k1", Optional.empty(), 4097, true, Duration.ZERO))
                .hasMessageStartingWith("INVALID_LINK_LIMIT");
        new LinkSettings("k1", Optional.empty(), 616, true, Duration.ZERO);
        new LinkSettings("k1", Optional.empty(), 4096, true, Duration.ofSeconds(30));
        assertThatThrownBy(() -> new LinkSettings("k1", Optional.empty(), 1024, true, Duration.ofSeconds(31)))
                .hasMessageStartingWith("INVALID_LINK_LIMIT");
        assertThatThrownBy(() -> new LinkSettings("k1", Optional.empty(), 1024, true, Duration.ofSeconds(-1)))
                .hasMessageStartingWith("INVALID_LINK_LIMIT");
        assertThatThrownBy(() -> new LinkSettings("k1", Optional.of(Duration.ZERO), 1024, true, Duration.ZERO))
                .hasMessageStartingWith("INVALID_LINK_LIFETIME");
        for (Duration tooShort : List.of(Duration.ofMillis(999), Duration.ofNanos(1), Duration.ofSeconds(-5))) {
            assertThatThrownBy(() -> new LinkSettings("k1", Optional.of(tooShort), 1024, true, Duration.ZERO))
                    .isInstanceOf(LinkConfigurationException.class)
                    .hasMessageStartingWith("INVALID_LINK_LIFETIME");
        }
        new LinkSettings("k1", Optional.of(Duration.ofSeconds(1)), 1024, true, Duration.ZERO);
        for (String bad : List.of("", "a".repeat(17), "has space", "dot.id", "slash/id", "é")) {
            assertThatThrownBy(() -> new LinkSettings(bad, Optional.empty(), 1024, true, Duration.ZERO))
                    .hasMessageStartingWith("INVALID_LINK_LIMIT");
        }
        new LinkSettings("A_b-9".repeat(3) + "Z", Optional.empty(), 1024, true, Duration.ZERO);
    }

    @Test
    void theCodecRefusesSettingsThatNameAnotherKey() {
        MapKeys keys = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32));
        assertThatThrownBy(() -> new LinkCodec(settings("k2", true, Duration.ZERO),
                new LinkKeys(keys, "k1", "pseudo-key"), new TestClockHolder().clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyConstructionRefusalCarriesItsCode() {
        MapKeys ok = new MapKeys().with("k1", material(1, 32));
        assertCode("LINK_KEY_UNRESOLVED", () -> new LinkKeys(new MapKeys(), "k1", "pseudo-key"));
        assertCode("LINK_KEY_UNRESOLVED", () -> new LinkKeys(ok, "k1", "absent"));
        assertCode("LINK_KEY_UNRESOLVED", () -> new LinkKeys(ok, "k1", "pseudo-key", () -> Set.of("k1", "k2")));
        assertCode("LINK_KEY_WEAK", () -> new LinkKeys(new MapKeys().with("k1", material(1, 31)), "k1",
                "pseudo-key"));
        assertCode("LINK_KEY_SHARED", () -> new LinkKeys(ok, "k1", null));
        assertCode("LINK_KEY_SHARED", () -> new LinkKeys(ok, "k1", "k1"));
        assertCode("LINK_KEY_SHARED", () -> new LinkKeys(ok, "k1", "pseudo-key", () -> Set.of("k1", "pseudo-key")));
        assertCode("LINK_KEY_SHARED", () -> new LinkKeys(new MapKeys().with("k1", material(9, 32))
                .with("pseudo-key", material(9, 32)), "k1", "pseudo-key"));
        assertCode("INVALID_LINK_LIMIT", () -> new LinkKeys(ok, "bad id", "pseudo-key"));
        assertCode("INVALID_LINK_LIMIT", () -> new LinkSettings("", Optional.empty(), 1024, true, Duration.ZERO));
        assertCode("INVALID_LINK_LIMIT", () -> new LinkSettings("k1", Optional.empty(), 615, true, Duration.ZERO));
        assertCode("INVALID_LINK_LIMIT", () -> new LinkSettings("k1", Optional.empty(), 1024, true,
                Duration.ofSeconds(31)));
        assertCode("INVALID_LINK_LIFETIME", () -> new LinkSettings("k1", Optional.of(Duration.ZERO), 1024, true,
                Duration.ZERO));
        assertCode("INVALID_LINK_LIFETIME", () -> new LinkSettings("k1", Optional.of(Duration.ofMillis(999)), 1024,
                true, Duration.ZERO));
        MapKeys two = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32));
        assertCode("INVALID_LINK_LIMIT", () -> new LinkCodec(settings("k2", true, Duration.ZERO),
                new LinkKeys(two, "k1", "pseudo-key"), new TestClockHolder().clock));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable construction) {
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(LinkConfigurationException.class,
                construction);
        assertThat(((LinkConfigurationException) thrown).code()).isEqualTo(code);
        assertThat(thrown.getMessage()).startsWith(code + ": ").doesNotContain(SENTINEL);
        assertThat(thrown).hasNoCause();
    }

    private static final class TestClockHolder {
        final LinkTestSupport.TestClock clock = new LinkTestSupport.TestClock(T0);
    }

    // ---- derivation ----

    @Test
    void derivedKeysDifferPerLabelAndPerScope() throws Exception {
        byte[] master = material(1, 32);
        byte[] seal = LinkKeys.derive(master, LinkKeys.SEAL_LABEL, SCOPE);
        byte[] fingerprint = LinkKeys.derive(master, LinkKeys.FINGERPRINT_LABEL, SCOPE);
        byte[] caller = LinkKeys.derive(master, LinkKeys.CALLER_LABEL, SCOPE);
        byte[] otherScope = LinkKeys.derive(master, LinkKeys.SEAL_LABEL, "case-2");
        assertThat(List.of(HEX.formatHex(seal), HEX.formatHex(fingerprint), HEX.formatHex(caller),
                HEX.formatHex(otherScope))).doesNotHaveDuplicates();
        assertThat(seal).hasSize(32);
        // And the main code agrees with an independent reading of the derivation.
        assertThat(seal).isEqualTo(LinkVectors.derive(master, "dataprism/link/v1/seal", SCOPE));
        assertThat(fingerprint).isEqualTo(LinkVectors.derive(master, "dataprism/link/v1/fingerprint", SCOPE));
        assertThat(caller).isEqualTo(LinkVectors.derive(master, "dataprism/link/v1/caller", SCOPE));
    }

    @Test
    void fingerprintsAndTagsAreKeyedPerScope() {
        LinkKeys keys = new LinkKeys(new MapKeys().with("k1", material(1, 32)), "k1", "pseudo-key");
        byte[] value = "subject-1".getBytes(StandardCharsets.UTF_8);
        assertThat(keys.forScope(SCOPE).fingerprint(value)).hasSize(12)
                .isEqualTo(keys.forScope(SCOPE).fingerprint(value))
                .isNotEqualTo(keys.forScope("case-2").fingerprint(value));
        assertThat(keys.forScope(SCOPE).catalogTag(List.of("a", "b"))).hasSize(16)
                .isNotEqualTo(keys.forScope(SCOPE).catalogTag(List.of("b", "a")))
                .isNotEqualTo(keys.forScope(SCOPE).catalogTag(List.of("ab")))
                .isNotEqualTo(keys.forScope("case-2").catalogTag(List.of("a", "b")));
        assertThat(keys.forScope(SCOPE).toString()).doesNotContain(HEX.formatHex(keys.forScope(SCOPE)
                .fingerprint(value)));
    }

    @Test
    void forScopeNamesNoReferenceWhenAHeldKeyIsUnavailable() {
        MapKeys provider = new MapKeys().with("k1", material(1, 32)).with("k2", material(2, 32));
        LinkKeys keys = new LinkKeys(provider, "k1", "pseudo-key", () -> Set.of("k1", "k2"));
        provider.keys.remove("k2");
        // k2 is held, so the provider is asked and throws a message that names a sentinel reference.
        assertThatThrownBy(() -> provider.secret("k2")).hasMessageContaining(SENTINEL);
        assertThatThrownBy(() -> keys.forScope(SCOPE, "k2"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("LINK_KEY_UNRESOLVED")
                        .doesNotContain(SENTINEL))
                .hasNoCause();
    }

    @Test
    void theAttemptCounterKeyHoldsNeitherScopeNorPrincipal() {
        LinkKeys keys = new LinkKeys(new MapKeys().with("k1", material(1, 32)), "k1", "pseudo-key");
        String counter = keys.attemptCounterKey("case-1", "alice");
        assertThat(counter).matches("[0-9a-f]{32}").doesNotContain("case").doesNotContain("alice");
        assertThat(counter).isEqualTo(keys.attemptCounterKey("case-1", "alice"))
                .isNotEqualTo(keys.attemptCounterKey("case-1", "bob"))
                .isNotEqualTo(keys.attemptCounterKey("case-2", "alice"));
        // The boundary between scope and principal is length-prefixed, not delimited.
        assertThat(keys.attemptCounterKey("ab", "c")).isNotEqualTo(keys.attemptCounterKey("a", "bc"));
    }

    @Test
    void heldKeyIdsReportsTheProvidersSet() {
        MapKeys provider = new MapKeys().with("old", material(1, 32)).with("new", material(2, 32));
        assertThat(new LinkKeys(provider, "new", "pseudo-key", () -> Set.of("old", "new")).heldKeyIds())
                .containsExactly("new", "old");
        assertThat(new LinkKeys(provider, "new", "pseudo-key").heldKeyIds()).containsExactly("new");
    }

    // ---- review 4: purpose labels on every MAC input ----

    @Test
    void theThreeMacPurposesNeverProduceTheSameOutputForTheSameInput() throws Exception {
        LinkKeys keys = new LinkKeys(new MapKeys().with("k1", material(1, 32)), "k1", "pseudo-key");
        String scope = "case-1";
        String principal = "alice";
        // The input of the old, unlabelled counter key, offered to the fingerprint and to the catalog tag.
        byte[] counterInput = LabelledHkdf.lengthPrefixed(scope.getBytes(StandardCharsets.UTF_8),
                principal.getBytes(StandardCharsets.UTF_8));
        String counter = keys.attemptCounterKey(scope, principal);
        String tag = HEX.formatHex(keys.forScope(scope).catalogTag(List.of(scope, principal)));
        String fingerprint = HEX.formatHex(keys.forScope(scope).fingerprint(counterInput));
        assertThat(counter).isNotEqualTo(tag);
        assertThat(counter).doesNotStartWith(fingerprint).doesNotStartWith(tag);
        assertThat(tag).isNotEqualTo(fingerprint);
        // Pre-fix, all three were HMAC(fingerprintKey, the same bytes).
        byte[] unlabelled = LinkVectors.hmac(LinkVectors.derive(material(1, 32), "dataprism/link/v1/fingerprint",
                scope), counterInput);
        assertThat(counter).isNotEqualTo(HEX.formatHex(unlabelled, 0, 16));
        assertThat(tag).isNotEqualTo(HEX.formatHex(unlabelled, 0, 16));
        assertThat(fingerprint).isNotEqualTo(HEX.formatHex(unlabelled, 0, 12));
    }

    @Test
    void theMacLabelsAreTheDocumentedOnes() {
        assertThat(LinkKeys.FINGERPRINT_LABEL).isEqualTo("dataprism/link/v1/fingerprint");
        assertThat(LinkKeys.CATALOG_TAG_LABEL).isEqualTo("dataprism/link/v1/catalog-tag");
        assertThat(LinkKeys.ATTEMPT_LABEL).isEqualTo("dataprism/link/v1/attempt");
    }

    @Test
    void theMacsMatchAnIndependentDerivation() throws Exception {
        byte[] master = material(1, 32);
        LinkKeys keys = new LinkKeys(new MapKeys().with("k1", master), "k1", "pseudo-key");
        byte[] value = "subject-1".getBytes(StandardCharsets.UTF_8);
        assertThat(keys.forScope(SCOPE).fingerprint(value))
                .isEqualTo(LinkVectors.fingerprint(master, SCOPE, value));
        assertThat(keys.forScope(SCOPE).catalogTag(List.of("alpha", "beta")))
                .isEqualTo(LinkVectors.catalogTag(master, SCOPE, List.of("alpha", "beta")));
        assertThat(keys.forScope(SCOPE).catalogTag(List.of()))
                .isEqualTo(LinkVectors.catalogTag(master, SCOPE, List.of()));
        assertThat(keys.attemptCounterKey(SCOPE, "alice"))
                .isEqualTo(LinkVectors.counterKey(master, SCOPE, "alice"));
    }

    // ---- known answers ----

    private static Properties expected() throws Exception {
        Properties props = new Properties();
        try (InputStream in = LinkKeysTest.class.getResourceAsStream("/link/vectors.properties")) {
            assertThat(in).as("src/test/resources/link/vectors.properties").isNotNull();
            props.load(in);
        }
        return props;
    }

    /** Regenerates every vector from the fixed inputs. */
    static Properties regenerate() throws Exception {
        TestClockHolder holder = new TestClockHolder();
        byte[] master = material(1, 32);
        MapKeys provider = new MapKeys().with("vec-key", master).with("pseudo-key", material(200, 32));
        LinkSettings settings = new LinkSettings("vec-key", Optional.of(Duration.ofMinutes(30)), 1024, true,
                Duration.ofSeconds(5));
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (0xA0 + i);
        }
        LinkKeys keys = new LinkKeys(provider, "vec-key", "pseudo-key");
        LinkCodec codec = new LinkCodec(settings, keys, holder.clock, () -> nonce.clone());
        LinkBinding binding = binding(SCOPE, "alice");
        Properties out = new Properties();
        out.setProperty("master.length", "32");
        out.setProperty("seal.key", HEX.formatHex(LinkKeys.derive(master, LinkKeys.SEAL_LABEL, SCOPE)));
        out.setProperty("fingerprint.key", HEX.formatHex(LinkKeys.derive(master, LinkKeys.FINGERPRINT_LABEL, SCOPE)));
        out.setProperty("caller.key", HEX.formatHex(LinkKeys.derive(master, LinkKeys.CALLER_LABEL, SCOPE)));
        out.setProperty("fingerprint", HEX.formatHex(
                keys.forScope(SCOPE).fingerprint("subject-1".getBytes(StandardCharsets.UTF_8))));
        out.setProperty("catalog.tag", HEX.formatHex(keys.forScope(SCOPE).catalogTag(List.of("alpha", "beta"))));
        out.setProperty("counter.key", keys.attemptCounterKey(SCOPE, "alice"));
        out.setProperty("follow.token", codec.seal(follow("subject-1".getBytes(StandardCharsets.UTF_8), "source-one"),
                binding).orElseThrow().token());
        out.setProperty("page.token", codec.seal(page(), binding).orElseThrow().token());
        return out;
    }

    @Test
    void theVectorFilePinsTheDerivedKeysAndTwoSealedLinks() throws Exception {
        Properties regenerated = regenerate();
        String writeTo = System.getProperty("link.vectors.write");
        if (writeTo != null) {
            Files.writeString(Path.of(writeTo), render(regenerated));
        }
        Properties pinned = expected();
        assertThat(regenerated.stringPropertyNames()).isEqualTo(pinned.stringPropertyNames());
        for (String name : regenerated.stringPropertyNames()) {
            assertThat(regenerated.getProperty(name)).as(name).isEqualTo(pinned.getProperty(name));
        }
    }

    @Test
    void thePinnedLinksOpenUnderTheFixedKey() throws Exception {
        Properties pinned = expected();
        byte[] master = material(1, 32);
        byte[] plain = LinkVectors.decrypt(master, SCOPE, "alice", true,
                Base64.getUrlDecoder().decode(pinned.getProperty("follow.token")));
        assertThat(plain).hasSize(8 + 8 + 1 + 5 + 1 + 2 + 64);
        byte[] pagePlain = LinkVectors.decrypt(master, SCOPE, "alice", true,
                Base64.getUrlDecoder().decode(pinned.getProperty("page.token")));
        // The pinned MAC vectors equal an independent derivation, not merely the main code's own output.
        assertThat(pinned.getProperty("fingerprint")).isEqualTo(HEX.formatHex(
                LinkVectors.fingerprint(master, SCOPE, "subject-1".getBytes(StandardCharsets.UTF_8))));
        assertThat(pinned.getProperty("catalog.tag")).isEqualTo(HEX.formatHex(
                LinkVectors.catalogTag(master, SCOPE, List.of("alpha", "beta"))));
        assertThat(pinned.getProperty("counter.key")).isEqualTo(LinkVectors.counterKey(master, SCOPE, "alice"));
        assertThat(pagePlain.length).isEqualTo(8 + 8 + 1 + 6 + 5 + 1 + 2 + 11 + 16);
        assertThat(Arrays.copyOfRange(pagePlain, pagePlain.length - 16, pagePlain.length))
                .isEqualTo(material(90, 16));
    }

    private static String render(Properties props) {
        StringBuilder sb = new StringBuilder(
                "# Known-answer vectors for the sealed-link codec. Invented key material.\n");
        sb.append("# Inputs: master = material(1,32), scope case-1, principal alice, nonce a0..ab, "
                + "clock 2030-01-01T00:00:00Z.\n");
        for (String name : new TreeSet<>(props.stringPropertyNames())) {
            sb.append(name).append('=').append(props.getProperty(name)).append('\n');
        }
        return sb.toString();
    }
}
