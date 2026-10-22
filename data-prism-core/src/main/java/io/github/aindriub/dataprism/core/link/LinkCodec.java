package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Seals and opens links (sealed-links.md sections 3 and 4): AES-256-GCM under a per-scope key derived from the
 * link key, in a fixed binary layout, base64url without padding.
 *
 * <pre>
 * version(1) | key id length(1), key id | expiry(8, epoch seconds) | target length(1), target
 *            | relation(1) | nonce(12) | ciphertext || tag(16)
 * </pre>
 *
 * <p>The associated data is the length-prefixed concatenation of version, key id, expiry, target, relation,
 * scope id (from the session) and caller tag, so a link replayed in another scope, by another caller or against
 * another target fails authentication.
 *
 * <p><b>Verification order</b> (no oracle): the length first, before any decoding; then the structure, which
 * depends only on the public layout; then AEAD verification always, against the key the token names or a fixed
 * dummy key when that id is unknown; and only then expiry, issue time and relation, evaluated as booleans. A
 * token that fails authentication is {@code AUTH_FAILED} whatever its header says.
 *
 * <p>This is the only place in the project, with the cluster seal, that creates an {@code AES/GCM} cipher. No
 * token, payload, chained key or key byte appears in an exception message, {@code toString} or log line; this
 * class does not log.
 */
public final class LinkCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int PAYLOAD_DEPTH = 0;
    private static final long EXPIRY_CLAMP = 1L << 40;

    private final LinkSettings settings;
    private final LinkKeys keys;
    private final Clock clock;
    private final Supplier<byte[]> nonces;

    public LinkCodec(LinkSettings settings, LinkKeys keys, Clock clock) {
        this(settings, keys, clock, randomNonces());
    }

    /** As the public constructor, with a nonce source; a known-answer test fixes it. */
    LinkCodec(LinkSettings settings, LinkKeys keys, Clock clock, Supplier<byte[]> nonces) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        if (!settings.keyId().equals(keys.currentKeyId())) {
            throw new IllegalArgumentException("the settings and the keys name different key ids");
        }
    }

    private static Supplier<byte[]> randomNonces() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] nonce = new byte[LinkLayout.NONCE_BYTES];
            random.nextBytes(nonce);
            return nonce;
        };
    }

    // ---- seal ----

    /** The link, or empty when the chained key is over 256 bytes or the encoding exceeds {@code maxLength}. */
    public Optional<SealedLink> seal(LinkPayload payload, LinkBinding binding) {
        return sealDetailed(payload, binding) instanceof SealResult.Issued issued
                ? Optional.of(issued.link())
                : Optional.empty();
    }

    /** As {@link #seal}, naming the reason when no link is issued. */
    public SealResult sealDetailed(LinkPayload payload, LinkBinding binding) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(binding, "binding");
        if (payload instanceof FollowPayload follow && follow.chainedKey().length > LinkLayout.MAX_CHAINED_KEY) {
            return new SealResult.Omitted(SealResult.OmitReason.KEY_TOO_LONG);
        }
        Instant now = clock.instant();
        Instant expiry = expiry(now, binding);
        if (!expiry.isAfter(now)) {
            return new SealResult.Omitted(SealResult.OmitReason.ALREADY_EXPIRED);
        }
        long expirySeconds = expiry.getEpochSecond();
        String target = payload instanceof FollowPayload follow ? follow.target() : "";
        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
        byte[] keyIdBytes = settings.keyId().getBytes(StandardCharsets.US_ASCII);
        LinkRelation relation = payload.relation();

        byte[] master = keys.master(settings.keyId());
        if (master == null) {
            return new SealResult.Omitted(SealResult.OmitReason.KEY_UNAVAILABLE);
        }
        byte[] sealKey = LinkKeys.derive(master, LinkKeys.SEAL_LABEL, binding.scopeId());
        byte[] callerTag = callerTag(master, binding);
        byte[] aad = aad(keyIdBytes, expirySeconds, targetBytes, relation.code(), binding.scopeId(), callerTag);
        byte[] nonce = nonces.get();
        if (nonce.length != LinkLayout.NONCE_BYTES) {
            throw new IllegalStateException("the nonce source returned the wrong length");
        }

        byte[] ciphertext = encrypt(sealKey, nonce, aad, encodePayload(payload));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(LinkLayout.VERSION);
        out.write(keyIdBytes.length);
        out.writeBytes(keyIdBytes);
        out.writeBytes(ByteBuffer.allocate(LinkLayout.EXPIRY_BYTES).putLong(expirySeconds).array());
        out.write(targetBytes.length);
        out.writeBytes(targetBytes);
        out.write(relation.code());
        out.writeBytes(nonce);
        out.writeBytes(ciphertext);
        String token = ENCODER.encodeToString(out.toByteArray());
        if (token.length() > settings.maxLength()) {
            return new SealResult.Omitted(SealResult.OmitReason.TOO_LONG);
        }
        return new SealResult.Issued(new SealedLink(token, Instant.ofEpochSecond(expirySeconds)));
    }

    /** {@code min(now + lifetime, scope expiry, token expiry)}, to whole seconds, rounding down. */
    private Instant expiry(Instant now, LinkBinding binding) {
        Instant expiry = binding.scopeExpiry().isBefore(binding.tokenExpiry())
                ? binding.scopeExpiry() : binding.tokenExpiry();
        if (settings.lifetime().isPresent()) {
            Instant byLifetime = LinkLayout.plus(now, settings.lifetime().get());
            if (byLifetime.isBefore(expiry)) {
                expiry = byLifetime;
            }
        }
        return Instant.ofEpochSecond(expiry.getEpochSecond());
    }

    // ---- open ----

    /**
     * Opens a token. Returns {@link LinkOpenResult.Opened} for a valid payload whose relation is in
     * {@code accepted}, or {@link LinkOpenResult.Refused} with the audit reason and whether it is counted.
     */
    public LinkOpenResult open(String token, LinkBinding binding, Set<LinkRelation> accepted) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(accepted, "accepted");
        if (token == null) {
            return refuse(LinkReason.MALFORMED);
        }
        // 1. Length, before any decoding.
        if (token.length() > settings.maxLength()) {
            return refuse(LinkReason.TOO_LONG);
        }
        // 2. Structure: depends only on the public layout.
        byte[] raw = decode(token);
        if (raw == null || raw.length < 1) {
            return refuse(LinkReason.MALFORMED);
        }
        if ((raw[0] & 0xFF) != LinkLayout.VERSION) {
            return refuse(LinkReason.VERSION);
        }
        Header header = Header.parse(raw);
        if (header == null) {
            return refuse(LinkReason.MALFORMED);
        }

        // 3. Always run the AEAD verification: against the named key, or a dummy when it is unknown.
        LinkKeys.Lookup lookup = keys.lookup(header.keyId);
        boolean knownKey = lookup.status() == LinkKeys.Status.HELD;
        byte[] master = knownKey ? lookup.material() : LinkKeys.dummyMaster();
        byte[] sealKey = LinkKeys.derive(master, LinkKeys.SEAL_LABEL, binding.scopeId());
        byte[] callerTag = callerTag(master, binding);
        byte[] aad = aad(header.keyIdBytes, header.expirySeconds, header.targetBytes, header.relationCode,
                binding.scopeId(), callerTag);
        byte[] plain = decrypt(sealKey, header.nonce, aad, raw, header.ciphertextOffset);
        boolean authenticated = plain != null && knownKey;

        // 4. Only now the booleans, all evaluated, then combined in a fixed order.
        Instant now = clock.instant();
        // The header expiry is unauthenticated until the tag verifies; clamp it so a hostile value cannot throw.
        Instant expiry = Instant.ofEpochSecond(Math.max(-EXPIRY_CLAMP, Math.min(EXPIRY_CLAMP, header.expirySeconds)));
        // Skew applies to the link's own expiry only; the scope and the token end exactly, with no skew.
        boolean expired = !now.isBefore(LinkLayout.plus(expiry, settings.clockSkew()))
                || !now.isBefore(binding.scopeExpiry())
                || !now.isBefore(binding.tokenExpiry());
        LinkRelation relation = LinkRelation.fromCode(header.relationCode);
        boolean relationAccepted = relation != null && accepted.contains(relation)
                && relation != LinkRelation.COLLECTION_PAGE && relation != LinkRelation.GROUP;
        LinkPayload payload = authenticated ? decodePayload(plain, relation, header) : null;
        boolean parsed = !authenticated || payload != null;
        boolean future = payload != null
                && payload.issueTime().isAfter(LinkLayout.plus(now, settings.clockSkew()));

        if (lookup.status() == LinkKeys.Status.UNAVAILABLE) {
            return refuse(LinkReason.KEY_UNAVAILABLE);
        }
        if (!knownKey) {
            return refuse(LinkReason.UNKNOWN_KEY);
        }
        if (!authenticated) {
            return refuse(LinkReason.AUTH_FAILED);
        }
        if (!parsed) {
            // Only a genuine link authenticates, so a bad payload is the issuer's fault: never counted.
            return new LinkOpenResult.Refused(LinkReason.MALFORMED, false);
        }
        if (expired) {
            return refuse(LinkReason.EXPIRED);
        }
        if (future) {
            return refuse(LinkReason.ISSUED_IN_FUTURE);
        }
        if (!relationAccepted) {
            return refuse(LinkReason.WRONG_TOOL);
        }
        return new LinkOpenResult.Opened(payload, expiry, header.keyId);
    }

    private static LinkOpenResult.Refused refuse(LinkReason reason) {
        return new LinkOpenResult.Refused(reason, reason.counted());
    }

    /** Strict base64url without padding: anything that does not re-encode to the same text is malformed. */
    private static byte[] decode(String token) {
        try {
            byte[] raw = DECODER.decode(token);
            return ENCODER.encodeToString(raw).equals(token) ? raw : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The cleartext header of a structurally valid token. */
    private static final class Header {
        String keyId;
        byte[] keyIdBytes;
        long expirySeconds;
        byte[] targetBytes;
        int relationCode;
        byte[] nonce;
        int ciphertextOffset;

        static Header parse(byte[] raw) {
            int at = 1;
            if (raw.length < at + 1) {
                return null;
            }
            int keyIdLength = raw[at++] & 0xFF;
            if (keyIdLength < 1 || keyIdLength > LinkLayout.MAX_KEY_ID
                    || raw.length < at + keyIdLength + LinkLayout.EXPIRY_BYTES + 1) {
                return null;
            }
            Header h = new Header();
            h.keyIdBytes = Arrays.copyOfRange(raw, at, at + keyIdLength);
            h.keyId = new String(h.keyIdBytes, StandardCharsets.US_ASCII);
            if (!LinkSettings.validKeyId(h.keyId)) {
                return null;
            }
            at += keyIdLength;
            h.expirySeconds = ByteBuffer.wrap(raw, at, LinkLayout.EXPIRY_BYTES).getLong();
            at += LinkLayout.EXPIRY_BYTES;
            int targetLength = raw[at++] & 0xFF;
            if (targetLength > LinkLayout.MAX_TARGET
                    || raw.length < at + targetLength + 1 + LinkLayout.NONCE_BYTES + LinkLayout.TAG_BYTES) {
                return null;
            }
            h.targetBytes = Arrays.copyOfRange(raw, at, at + targetLength);
            at += targetLength;
            h.relationCode = raw[at++] & 0xFF;
            if (LinkRelation.fromCode(h.relationCode) == null) {
                return null;
            }
            h.nonce = Arrays.copyOfRange(raw, at, at + LinkLayout.NONCE_BYTES);
            at += LinkLayout.NONCE_BYTES;
            h.ciphertextOffset = at;
            return h;
        }
    }

    // ---- associated data and caller tag ----

    private static byte[] aad(byte[] keyId, long expirySeconds, byte[] target, int relation, String scopeId,
                              byte[] callerTag) {
        return LabelledHkdf.lengthPrefixed(
                new byte[] {(byte) LinkLayout.VERSION},
                keyId,
                ByteBuffer.allocate(LinkLayout.EXPIRY_BYTES).putLong(expirySeconds).array(),
                target,
                new byte[] {(byte) relation},
                scopeId.getBytes(StandardCharsets.UTF_8),
                callerTag);
    }

    /** {@code HMAC(callerKey, principalId)} truncated to 16 bytes, or 16 zero bytes when not bound. */
    private byte[] callerTag(byte[] master, LinkBinding binding) {
        byte[] callerKey = LinkKeys.derive(master, LinkKeys.CALLER_LABEL, binding.scopeId());
        byte[] mac = LabelledHkdf.hmac(callerKey, binding.principalId().getBytes(StandardCharsets.UTF_8));
        return settings.bindCaller()
                ? Arrays.copyOf(mac, LinkLayout.CALLER_TAG_BYTES)
                : new byte[LinkLayout.CALLER_TAG_BYTES];
    }

    // ---- AEAD ----

    private static byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(LinkLayout.TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plain);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("the link could not be sealed");
        }
    }

    /** The plaintext, or {@code null} when authentication fails. */
    private static byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] raw, int offset) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(LinkLayout.TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(raw, offset, raw.length - offset);
        } catch (GeneralSecurityException failure) {
            return null;
        }
    }

    // ---- payload ----

    private static byte[] encodePayload(LinkPayload payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(payload.linkId());
        out.writeBytes(ByteBuffer.allocate(LinkLayout.ISSUE_BYTES).putLong(payload.issueTime().getEpochSecond())
                .array());
        out.write(PAYLOAD_DEPTH);
        switch (payload) {
            case FollowPayload follow -> {
                writeText(out, follow.keyNamespace());
                writeText(out, follow.resolverName().orElse(""));
                byte[] key = follow.chainedKey();
                out.write(key.length >>> 8);
                out.write(key.length);
                out.writeBytes(key);
                out.writeBytes(new byte[LinkLayout.paddedLength(key.length) - key.length]);
            }
            case CatalogPagePayload page -> {
                writeText(out, page.entityTypeFilter().orElse(""));
                writeText(out, page.keyNamespaceFilter().orElse(""));
                out.write(page.labelKind().code());
                out.write(page.pageSize() >>> 8);
                out.write(page.pageSize());
                writeText(out, page.lastLabel());
                out.writeBytes(page.catalogTag());
            }
        }
        return out.toByteArray();
    }

    private static void writeText(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length);
        out.writeBytes(bytes);
    }

    /** The payload of an authenticated token, or {@code null} when its bytes do not fit the relation's layout. */
    private static LinkPayload decodePayload(byte[] plain, LinkRelation relation, Header header) {
        if (relation == null) {
            return null;
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(plain);
            byte[] linkId = new byte[LinkLayout.LINK_ID_BYTES];
            in.get(linkId);
            Instant issued = Instant.ofEpochSecond(in.getLong());
            if (in.get() != PAYLOAD_DEPTH) {
                return null;
            }
            LinkPayload payload;
            switch (relation) {
                case DIRECT, TRANSLATED -> {
                    String namespace = readText(in, LinkLayout.MAX_NAMESPACE);
                    String resolver = readText(in, LinkLayout.MAX_RESOLVER);
                    int length = in.getShort() & 0xFFFF;
                    int padded = LinkLayout.paddedLength(length);
                    if (length < 1 || length > LinkLayout.MAX_CHAINED_KEY || in.remaining() != padded) {
                        return null;
                    }
                    byte[] key = new byte[length];
                    in.get(key);
                    byte[] padding = new byte[padded - length];
                    in.get(padding);
                    for (byte b : padding) {
                        if (b != 0) {
                            return null;
                        }
                    }
                    payload = new FollowPayload(linkId, issued, namespace,
                            resolver.isEmpty() ? Optional.empty() : Optional.of(resolver), key,
                            new String(header.targetBytes, StandardCharsets.UTF_8), relation);
                }
                case CATALOG_PAGE -> {
                    String entityType = readText(in, LinkLayout.MAX_ENTITY_TYPE);
                    String namespace = readText(in, LinkLayout.MAX_NAMESPACE);
                    LinkLabelKind kind = LinkLabelKind.fromCode(in.get());
                    int pageSize = in.getShort() & 0xFFFF;
                    String last = readText(in, LinkLayout.MAX_LABEL);
                    byte[] tag = new byte[LinkLayout.CATALOG_TAG_BYTES];
                    in.get(tag);
                    if (kind == null || in.hasRemaining() || header.targetBytes.length != 0) {
                        return null;
                    }
                    payload = new CatalogPagePayload(linkId, issued,
                            entityType.isEmpty() ? Optional.empty() : Optional.of(entityType),
                            namespace.isEmpty() ? Optional.empty() : Optional.of(namespace),
                            kind, pageSize, last, tag);
                }
                default -> {
                    return null;
                }
            }
            return payload;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String readText(ByteBuffer in, int max) {
        int length = in.get() & 0xFF;
        if (length > max) {
            throw new IllegalArgumentException("too long");
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "LinkCodec[redacted]";
    }
}
