package io.github.aindriub.dataprism.core.spi;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;

/**
 * Derives a stable substitute from the value itself.
 *
 * <p>Different in kind from {@link SyntheticValueSource}, and the difference
 * matters when choosing an action:
 *
 * <ul>
 *   <li>{@code SYNTHESIZE} is keyed on the <em>subject</em>. One person reads the
 *       same everywhere even where the sources spell their name differently,
 *       which is what makes cross-source correlation work.
 *   <li>{@code HASH} and {@code TOKENIZE} are keyed on the <em>value</em>. Equal
 *       values produce equal output, so joins on that value survive — and
 *       unequal values stay visibly unequal.
 * </ul>
 *
 * <p>That second property is also the risk. A value-derived substitute discloses
 * equality, and where the set of possible values is small it discloses the value:
 * anyone holding the key can enumerate a country code, a status, or a national
 * identifier with a known format and match the output. Keying under the scope
 * secret keeps that out of reach of the model and of anyone reading the response,
 * but it is not protection against someone with the key.
 *
 * <p>So these are for identifiers that need to stay joinable and are drawn from a
 * large space. For anything low-entropy, {@code REDACT} or {@code GENERALIZE}
 * say more honestly what is safe.
 */
public interface ValueTokenSource {

    /** A hex digest of the value, keyed under the scope. Opaque and stable. */
    String hash(String value, PrivacyNamespace namespace, PrivacyContext context);

    /**
     * A short scope-local token, keyed under the scope. Same purpose as
     * {@link #hash}, in a form short enough to read and quote back.
     */
    String token(String value, PrivacyNamespace namespace, PrivacyContext context);

    /**
     * Whether this source can hash and tokenize. The scrubbing engine reads this to describe a policy, so it must
     * agree with what {@link #hash} and {@link #token} do. A source that hashes and tokenizes must override this
     * to return {@code true}; a decorator must delegate it to the source it wraps. The default is {@code false},
     * so an implementation that does neither is described as unable to tokenize rather than over-claiming.
     */
    default boolean available() {
        return false;
    }

    /** Refuses both, for a deployment that has not chosen an implementation. */
    static ValueTokenSource unavailable() {
        return new ValueTokenSource() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String hash(String value, PrivacyNamespace namespace, PrivacyContext context) {
                throw new PrivacyRefusedException("NO_TOKEN_SOURCE", namespace.name(),
                        "HASH requires a ValueTokenSource and none is configured");
            }

            @Override
            public String token(String value, PrivacyNamespace namespace, PrivacyContext context) {
                throw new PrivacyRefusedException("NO_TOKEN_SOURCE", namespace.name(),
                        "TOKENIZE requires a ValueTokenSource and none is configured");
            }
        };
    }
}
