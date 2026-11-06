package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.link.LinkConfigurationException;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link LinkKeys} for {@code dataprism.links.key-id} and {@code dataprism.links.keys.<key-id>.*},
 * resolving every reference through the reviewed {@link HmacKeyReferenceResolver}, the pseudonymisation key through
 * the same provider as the link keys, as {@link LinkKeys} requires.
 *
 * <p>{@link LinkKeys} itself refuses an unresolvable key ({@code LINK_KEY_UNRESOLVED}), material under 32 bytes
 * ({@code LINK_KEY_WEAK}) and the pseudonymisation key's id or bytes ({@code LINK_KEY_SHARED}, the bytes compared in
 * constant time), for the current key and every key held for verification. This class adds the checks on the
 * properties' shape and turns each refusal into a {@link DataPrismConfigurationException} that names a property and
 * never a reference, a key id's material or anything the resolver said. A resolver's exception is dropped: it can
 * carry the reference or key material.
 */
final class LinkKeyResolution {
    private static final String KEY_ID = "dataprism.links.key-id";
    private static final String KEYS = "dataprism.links.keys";

    private LinkKeyResolution() {
    }

    /**
     * @throws DataPrismConfigurationException {@code LINK_KEY_UNRESOLVED}, {@code LINK_KEY_WEAK},
     *                                         {@code LINK_KEY_SHARED} or {@code INVALID_LINK_LIMIT}
     */
    static LinkKeys resolve(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        LinksProperties links = properties.getLinks();
        if (!links.keyConfigured()) {
            throw new DataPrismConfigurationException("LINK_KEY_UNRESOLVED", KEY_ID + " must not be blank");
        }
        if (resolver == null) {
            throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER",
                    "provide an HmacKeyReferenceResolver bean for the configured link key reference");
        }
        Map<String, LinksProperties.Key> keys = links.getKeys();
        if (keys.get(links.getKeyId()) == null) {
            throw new DataPrismConfigurationException("LINK_KEY_UNRESOLVED",
                    KEY_ID + " must name an entry of " + KEYS);
        }
        for (LinksProperties.Key key : keys.values()) {
            if (key == null || notBlank(key.getEnvironmentVariable()) == notBlank(key.getProviderReference())) {
                throw new DataPrismConfigurationException("LINK_KEY_UNRESOLVED",
                        KEYS + ".<key-id> must set exactly one of environment-variable and provider-reference");
            }
        }
        PrivacyProperties.HmacKey privacy = properties.getPrivacy().getHmacKey();
        SecretKeyProvider provider = new Provider(links, privacy, resolver);
        Set<String> held = new LinkedHashSet<>(keys.keySet());
        try {
            return new LinkKeys(provider, links.getKeyId(), privacy.getKeyId(), () -> held);
        } catch (LinkConfigurationException refused) {
            throw new DataPrismConfigurationException(refused.code(), KEYS + ": " + withoutCode(refused));
        }
    }

    private static String withoutCode(LinkConfigurationException refused) {
        String prefix = refused.code() + ": ";
        String message = refused.getMessage();
        return message.startsWith(prefix) ? message.substring(prefix.length()) : "the link key is not valid";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** The link keys and the pseudonymisation key by id; any other id is refused, never answered. */
    private record Provider(LinksProperties links, PrivacyProperties.HmacKey privacy,
                            HmacKeyReferenceResolver resolver) implements SecretKeyProvider {
        @Override
        public byte[] secret(String keyId) {
            if (keyId.equals(privacy.getKeyId())) {
                return HmacKeyReferences.resolve(resolver, privacy, keyId);
            }
            LinksProperties.Key key = links.getKeys().get(keyId);
            if (key == null) {
                throw new IllegalStateException("unconfigured link key id");
            }
            boolean environment = notBlank(key.getEnvironmentVariable());
            return HmacKeyReferences.resolve(resolver, !environment, keyId,
                    environment ? key.getEnvironmentVariable() : key.getProviderReference());
        }

        @Override
        public String toString() {
            return "LinkKeyResolution.Provider[redacted]";
        }
    }
}
