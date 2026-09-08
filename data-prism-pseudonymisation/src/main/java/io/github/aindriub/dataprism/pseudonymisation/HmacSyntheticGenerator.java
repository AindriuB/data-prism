package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;

/**
 * Derives synthetic values by keyed HMAC.
 *
 * <p>The key is {@code scopeId | subjectId | namespace | version}, authenticated
 * under a secret. Three properties follow, and all three are load-bearing:
 *
 * <ul>
 *   <li>the same subject gets the same value everywhere in a scope, which is what
 *       makes correlation across sources readable;
 *   <li>different scopes diverge, so nothing correlates between investigations;
 *   <li>it depends on no state at all, so a cache is an optimisation and never a
 *       source of truth. Losing the cache cannot change an answer.
 * </ul>
 *
 * <p>The plaintext being replaced is deliberately not an input. Hashing the value
 * would make the pseudonym a reversible function of it, recoverable by anyone
 * with a dictionary of likely names.
 */
public final class HmacSyntheticGenerator implements SyntheticValueSource {

    /** Crockford base32: no I, L, O or U, so a discriminator cannot be misread. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final SecretKeyProvider keys;

    public HmacSyntheticGenerator(SecretKeyProvider keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(context, "context");

        PseudonymisationVersion version = context.pseudonymisationVersion();
        String material = context.scopeId() + "|" + subjectId + "|" + namespace.name() + "|" + version.version();
        byte[] digest = mac(version.algorithm(), keys.secret(version.keyId()), material);
        return render(namespace, digest);
    }

    private static byte[] mac(String algorithm, byte[] secret, String material) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // Deliberately does not include the material: it contains the subject id.
            throw new IllegalStateException("HMAC failed for algorithm " + algorithm, e);
        }
    }

    private static String render(PrivacyNamespace namespace, byte[] d) {
        String tag = discriminator(d);
        return switch (namespace) {
            case PERSON_NAME, PERSON_IDENTITY -> pick(SyntheticVocabulary.FIRST_NAMES, d, 4)
                    + " " + pick(SyntheticVocabulary.LAST_NAMES, d, 8) + " (" + tag + ")";
            case PERSON_FIRST_NAME -> pick(SyntheticVocabulary.FIRST_NAMES, d, 4) + " (" + tag + ")";
            case PERSON_LAST_NAME -> pick(SyntheticVocabulary.LAST_NAMES, d, 8) + " (" + tag + ")";
            case EMAIL -> "person." + tag.toLowerCase(java.util.Locale.ROOT) + "@example.invalid";
            case ADDRESS -> (unsigned(d, 12) % 200 + 1) + " " + pick(SyntheticVocabulary.STREETS, d, 16)
                    + ", " + pick(SyntheticVocabulary.TOWNS, d, 20);
            case ORGANISATION_NAME, ORGANISATION_IDENTITY -> pick(SyntheticVocabulary.TOWNS, d, 4) + " Holdings (" + tag + ")";
            // The scope-local token for a subject with no other representation.
            // Audit records this rather than the real identifier.
            case NONE -> "SUBJ-" + tag;
            default -> namespace.name() + "-" + tag;
        };
    }

    /**
     * Twenty bits of the digest, rendered as four characters. Enough that
     * collisions inside a scope stop being a practical concern; short enough that
     * the value still reads as a name. ASCII only: a pseudonym travels through
     * logs, audit records and terminals, and a non-ASCII separator turns into
     * mojibake in at least one of them.
     */
    private static String discriminator(byte[] d) {
        int bits = (int) (unsigned(d, 0) & 0xFFFFF);
        char[] out = new char[4];
        for (int i = 3; i >= 0; i--) {
            out[i] = ALPHABET[bits & 0x1F];
            bits >>>= 5;
        }
        return new String(out);
    }

    private static String pick(List<String> pool, byte[] d, int offset) {
        return pool.get((int) (unsigned(d, offset) % pool.size()));
    }

    /** Four digest bytes at {@code offset} as an unsigned value. */
    private static long unsigned(byte[] d, int offset) {
        return ((long) (d[offset] & 0xFF) << 24)
                | ((long) (d[offset + 1] & 0xFF) << 16)
                | ((long) (d[offset + 2] & 0xFF) << 8)
                | (d[offset + 3] & 0xFF);
    }
}
