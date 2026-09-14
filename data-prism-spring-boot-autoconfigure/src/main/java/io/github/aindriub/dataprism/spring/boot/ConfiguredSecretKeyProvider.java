package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.SecretKeyProvider;
import java.util.Objects;

/** Pins a reviewed reference resolver to the one reference selected in deployment configuration. */
final class ConfiguredSecretKeyProvider implements SecretKeyProvider {
    private final String configuredKeyId;
    private final String reference;
    private final HmacKeyReferenceResolver resolver;
    ConfiguredSecretKeyProvider(String configuredKeyId, String reference, HmacKeyReferenceResolver resolver) {
        this.configuredKeyId=Objects.requireNonNull(configuredKeyId,"configuredKeyId"); this.reference=Objects.requireNonNull(reference,"reference"); this.resolver=Objects.requireNonNull(resolver,"resolver");
    }
    @Override public byte[] secret(String keyId) {
        if(!configuredKeyId.equals(keyId)) throw new IllegalStateException("unconfigured HMAC key id: "+keyId);
        return resolver.resolve(keyId, reference);
    }
}
