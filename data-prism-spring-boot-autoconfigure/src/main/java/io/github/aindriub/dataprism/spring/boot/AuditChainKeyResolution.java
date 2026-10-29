package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditChainKey;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Resolves the configured audit chain keys (the current key and the retired, verification-only ones) through the
 * reviewed {@link HmacKeyReferenceResolver}, and refuses a key that is the pseudonymisation key (D-1.0-4). Shape was
 * checked on the properties before this runs. A message names a property and never a reference, a key id or key
 * material, and keeps no cause: a resolver's exception can carry any of them.
 *
 * <p>The keyed chain resists a writer without the key. It does not resist anyone holding it, the operator included.
 */
final class AuditChainKeyResolution {
    private static final String CURRENT = "dataprism.audit.hmac-key";
    private static final String RETIRED = "dataprism.audit.retired-hmac-keys";

    private AuditChainKeyResolution() {
    }

    /** One resolved key: the property it came from, its id and a private copy of its material. */
    record Entry(String property, String keyId, byte[] material) {
        void wipe() {
            Arrays.fill(material, (byte) 0);
        }
    }

    /** The key the recorder signs with and the verification-only keys, in the form core takes them. */
    record Keys(AuditChainKey current, List<AuditChainKey> retired) {
        /** Current first, then the retired ones: what retention verifies with. */
        List<AuditChainKey> forVerification() {
            List<AuditChainKey> all = new ArrayList<>();
            all.add(current);
            all.addAll(retired);
            return List.copyOf(all);
        }
    }

    static boolean keyed(DataPrismProperties properties) {
        AuditProperties.HmacKey key = properties.getAudit().getHmacKey();
        return notBlank(key.getKeyId()) || notBlank(key.getEnvironmentVariable())
                || notBlank(key.getProviderReference());
    }

    /** @return the keys, or {@code null} when the chain is unkeyed */
    static Keys resolve(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        if (!keyed(properties)) {
            return null;
        }
        if (resolver == null) {
            throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER",
                    "provide an HmacKeyReferenceResolver bean for the configured reference");
        }
        List<Entry> entries = entries(properties, resolver);
        try {
            refuseReuse(properties, resolver, entries);
            AuditChainKey current = null;
            List<AuditChainKey> retired = new ArrayList<>();
            for (Entry entry : entries) {
                AuditChainKey key = new AuditChainKey(entry.keyId(), entry.material());
                if (current == null) {
                    current = key;
                } else {
                    retired.add(key);
                }
            }
            return new Keys(current, List.copyOf(retired));
        } finally {
            entries.forEach(Entry::wipe);
        }
    }

    /**
     * The resolved material of the current key and then the retired ones, empty when unkeyed. The caller wipes
     * each entry. Used by the cluster key check, which must compare against them.
     */
    static List<Entry> materials(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        return keyed(properties) ? entries(properties, resolver) : List.of();
    }

    private static List<Entry> entries(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        AuditProperties audit = properties.getAudit();
        AuditProperties.HmacKey key = audit.getHmacKey();
        List<Entry> out = new ArrayList<>();
        try {
            boolean env = notBlank(key.getEnvironmentVariable());
            out.add(fetch(resolver, CURRENT, key.getKeyId(), env, env ? key.getEnvironmentVariable()
                    : key.getProviderReference()));
            for (Map.Entry<String, AuditProperties.RetiredKey> retired : audit.getRetiredHmacKeys().entrySet()) {
                AuditProperties.RetiredKey old = retired.getValue();
                boolean oldEnv = notBlank(old.getEnvironmentVariable());
                out.add(fetch(resolver, RETIRED, retired.getKey(), oldEnv, oldEnv ? old.getEnvironmentVariable()
                        : old.getProviderReference()));
            }
            return out;
        } catch (RuntimeException refused) {
            out.forEach(Entry::wipe);
            throw refused;
        }
    }

    private static Entry fetch(HmacKeyReferenceResolver resolver, String property, String keyId, boolean env,
                               String reference) {
        byte[] received;
        try {
            received = HmacKeyReferences.resolve(resolver, !env, keyId, reference);
        } catch (RuntimeException unresolved) {
            throw new DataPrismConfigurationException("AUDIT_HMAC_KEY_UNRESOLVED",
                    property + " could not be resolved");
        }
        if (received == null) {
            throw new DataPrismConfigurationException("AUDIT_HMAC_KEY_UNRESOLVED",
                    property + " could not be resolved");
        }
        // The resolver does not promise a fresh array, so this copy is the only one ever cleared.
        byte[] material = received.clone();
        if (material.length < AuditChainKey.MIN_KEY_BYTES) {
            Arrays.fill(material, (byte) 0);
            throw new DataPrismConfigurationException("AUDIT_HMAC_KEY_TOO_SHORT",
                    property + " resolves to material shorter than " + AuditChainKey.MIN_KEY_BYTES + " bytes");
        }
        return new Entry(property, keyId, material);
    }

    /** The same reference string, or the same bytes, as the pseudonymisation key. */
    private static void refuseReuse(DataPrismProperties properties, HmacKeyReferenceResolver resolver,
                                    List<Entry> entries) {
        PrivacyProperties.HmacKey privacy = properties.getPrivacy().getHmacKey();
        String privacyReference = HmacKeyReferences.reference(privacy);
        AuditProperties audit = properties.getAudit();
        AuditProperties.HmacKey key = audit.getHmacKey();
        String currentReference = notBlank(key.getEnvironmentVariable()) ? key.getEnvironmentVariable()
                : key.getProviderReference();
        if (currentReference.equals(privacyReference)) {
            throw reuse(CURRENT);
        }
        for (AuditProperties.RetiredKey old : audit.getRetiredHmacKeys().values()) {
            String reference = notBlank(old.getEnvironmentVariable()) ? old.getEnvironmentVariable()
                    : old.getProviderReference();
            if (reference.equals(privacyReference)) {
                throw reuse(RETIRED);
            }
        }
        byte[] received;
        try {
            received = HmacKeyReferences.resolve(resolver, privacy, privacy.getKeyId());
        } catch (RuntimeException unresolved) {
            received = null;
        }
        if (received == null) {
            // Without its material the audit keys cannot be shown to differ from it, so none is accepted.
            throw new DataPrismConfigurationException("HMAC_KEY_UNRESOLVED",
                    "dataprism.privacy.hmac-key could not be resolved to check the audit keys against it");
        }
        byte[] pseudonymisation = received.clone();
        try {
            for (Entry entry : entries) {
                if (MessageDigest.isEqual(entry.material(), pseudonymisation)) {
                    throw reuse(entry.property());
                }
            }
        } finally {
            Arrays.fill(pseudonymisation, (byte) 0);
        }
    }

    private static DataPrismConfigurationException reuse(String property) {
        return new DataPrismConfigurationException("AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY",
                property + " must differ from dataprism.privacy.hmac-key");
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
