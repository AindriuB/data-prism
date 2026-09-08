package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.Text;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.PoolKind;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>Subject identifiers are canonicalised before hashing. Two systems can store
 * the same identifier in different Unicode normalisation forms, and without this
 * one subject would silently become two.
 */
public final class HmacSyntheticGenerator implements SyntheticValueSource {

    /** Crockford base32: no I, L, O or U, so a discriminator cannot be misread. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final SecretKeyProvider keys;
    private final Vocabulary vocabulary;

    public HmacSyntheticGenerator(SecretKeyProvider keys, Vocabulary vocabulary) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
    }

    @Override
    public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(context, "context");

        PseudonymisationVersion version = context.pseudonymisationVersion();
        if (!vocabulary.id().equals(version.vocabularyId())) {
            // Not pedantry. Serving a scope from a pool it was not pinned to
            // silently reassigns pseudonyms mid-investigation, and every
            // downstream reference to "Alex Murphy" quietly starts meaning
            // someone else.
            throw new IllegalStateException("scope is pinned to vocabulary '"
                    + version.vocabularyId() + "' but this generator holds '"
                    + vocabulary.id() + "'");
        }

        String material = context.scopeId() + "|" + Text.canonical(subjectId)
                + "|" + namespace.name() + "|" + version.version();
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

    private String render(PrivacyNamespace namespace, byte[] d) {
        String tag = discriminator(d);
        return switch (namespace) {
            case PERSON_NAME, PERSON_IDENTITY -> fullName(d) + " (" + tag + ")";
            case PERSON_FIRST_NAME -> pick(PoolKind.FIRST_NAME, d, 4) + " (" + tag + ")";
            case PERSON_LAST_NAME -> pick(PoolKind.LAST_NAME, d, 8) + " (" + tag + ")";
            case EMAIL -> "person." + tag.toLowerCase(Locale.ROOT) + "@example.invalid";
            case ADDRESS -> address(d);
            case ORGANISATION_NAME, ORGANISATION_IDENTITY -> organisation(d) + " (" + tag + ")";
            // The scope-local token for a subject with no other representation.
            // Audit records this rather than the real identifier.
            case NONE -> "SUBJ-" + tag;
            default -> namespace.name() + "-" + tag;
        };
    }

    /**
     * Name order follows the script rather than a single assumption. Joining
     * family-name-first scripts with a space in given-family order produces
     * something that is recognisably not a name in that language, which defeats
     * the point of having a locale-specific set at all.
     */
    private String fullName(byte[] d) {
        String first = pick(PoolKind.FIRST_NAME, d, 4);
        String last = pick(PoolKind.LAST_NAME, d, 8);
        return familyNameFirst() ? last + first : first + " " + last;
    }

    private String address(byte[] d) {
        long number = unsigned(d, 12) % 200 + 1;
        String street = pick(PoolKind.STREET, d, 16);
        String town = pick(PoolKind.TOWN, d, 20);
        // Largest unit first in Han-script addresses, smallest first elsewhere.
        return familyNameFirst()
                ? town + street + number + "号"
                : number + " " + street + ", " + town;
    }

    private String organisation(byte[] d) {
        List<String> organisations = vocabulary.pool(PoolKind.ORGANISATION);
        if (!organisations.isEmpty()) {
            return pickFrom(organisations, d, 4);
        }
        // A set that does not define organisations falls back to a place name,
        // which reads acceptably and keeps the script consistent.
        return pick(PoolKind.TOWN, d, 4) + (familyNameFirst() ? "集团" : " Holdings");
    }

    private boolean familyNameFirst() {
        String script = vocabulary.script();
        return script.equals("Hani") || script.equals("Hans") || script.equals("Hant");
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

    private String pick(PoolKind kind, byte[] d, int offset) {
        List<String> pool = vocabulary.pool(kind);
        if (pool.isEmpty()) {
            throw new IllegalStateException("vocabulary " + vocabulary.id()
                    + " has no " + kind.key() + " pool");
        }
        return pickFrom(pool, d, offset);
    }

    private static String pickFrom(List<String> pool, byte[] d, int offset) {
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
