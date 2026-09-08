package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.SecretKeyProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Turns request parameters into something safe to audit.
 *
 * <p>Keyed, not a plain digest. Search terms and identifiers come from a small,
 * guessable space — a bare SHA-256 of a person's name is reversed by anyone with
 * a name list and an afternoon, which would make the audit log a lookup table
 * for the data it exists to protect. See docs/design-review.md §E.
 */
public final class ParameterFingerprinter {

    private final SecretKeyProvider keys;

    public ParameterFingerprinter(SecretKeyProvider keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public String fingerprint(String value, PrivacyContext context) {
        var version = context.pseudonymisationVersion();
        try {
            Mac mac = Mac.getInstance(version.algorithm());
            mac.init(new SecretKeySpec(keys.secret(version.keyId()), version.algorithm()));
            byte[] digest = mac.doFinal(
                    (context.scopeId() + "|params|" + value).getBytes(StandardCharsets.UTF_8));
            // Twelve bytes is plenty to correlate two requests without widening
            // the search space enough to matter.
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("fingerprint failed for " + version.algorithm(), e);
        }
    }
}
