package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.Text;
import io.github.aindriub.dataprism.core.ValueTokenSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Keyed substitutes derived from the value.
 *
 * <p>Scoped like everything else: the same value in two investigations produces
 * two different tokens, so nothing correlates across cases. Values are
 * canonicalised first, so a name stored in two Unicode forms does not become two
 * tokens.
 *
 * <p>Keying is what makes this worth anything at all. An unkeyed digest of a
 * value drawn from a guessable space is not protection — anyone can hash the
 * candidates and match. Under the scope secret that attack needs the key, which
 * puts it out of reach of the model and of anyone reading the response. It does
 * not put it out of reach of someone holding the key, and no amount of hashing
 * would: see {@link ValueTokenSource} for when to reach for something else.
 */
public final class HmacValueTokenSource implements ValueTokenSource {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final SecretKeyProvider keys;

    public HmacValueTokenSource(SecretKeyProvider keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public String hash(String value, PrivacyNamespace namespace, PrivacyContext context) {
        return HexFormat.of().formatHex(mac(value, namespace, context), 0, 16);
    }

    @Override
    public String token(String value, PrivacyNamespace namespace, PrivacyContext context) {
        byte[] digest = mac(value, namespace, context);
        StringBuilder out = new StringBuilder(namespace.name()).append('-');
        // 40 bits, wide enough that two distinct values colliding inside a scope
        // is not a practical concern; short enough to quote back.
        for (int i = 0; i < 8; i++) {
            out.append(ALPHABET[digest[i] & 0x1F]);
        }
        return out.toString();
    }

    private byte[] mac(String value, PrivacyNamespace namespace, PrivacyContext context) {
        PseudonymisationVersion version = context.pseudonymisationVersion();
        String material = context.scopeId() + "|value|" + namespace.name()
                + "|" + Text.canonical(value) + "|" + version.version();
        try {
            Mac mac = Mac.getInstance(version.algorithm());
            mac.init(new SecretKeySpec(keys.secret(version.keyId()), version.algorithm()));
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // Deliberately does not include the material: it contains the value.
            throw new IllegalStateException("HMAC failed for algorithm " + version.algorithm(), e);
        }
    }
}
