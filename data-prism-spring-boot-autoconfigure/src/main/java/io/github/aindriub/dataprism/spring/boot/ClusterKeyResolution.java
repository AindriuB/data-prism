package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the configured cluster and name keys through the reviewed {@link HmacKeyReferenceResolver}, the same way
 * the privacy HMAC key is resolved, and builds the {@link ClusterKeys} a member holds. Shape and presence were
 * checked on the properties before this runs; this checks what only the resolved material can show.
 *
 * <p>Refusals are checked in a fixed order: unresolved, weak or shared with the privacy HMAC key (cluster keys
 * first, then the name key), a name key equal to a cluster key, then key ids that are not pairwise distinct. A
 * message names a property and never a key id's material, a reference, or anything the resolver said. Resolved
 * material is cleared as soon as {@link ClusterKeys} has copied it.
 */
final class ClusterKeyResolution {
    private static final String CLUSTER_KEY = "dataprism.hazelcast.security.cluster-key";
    private static final String NAME_KEY = "dataprism.hazelcast.security.name-key";

    private ClusterKeyResolution() {
    }

    /** One configured key: the property it came from, the code family for its refusals, its id and its source. */
    private record Ref(String property, String area, String keyId, String reference) {
    }

    private static final class Resolved {
        final Ref ref;
        final byte[] material;

        Resolved(Ref ref, byte[] material) {
            this.ref = ref;
            this.material = material;
        }
    }

    /**
     * @return the keys, or empty when none are configured (only a solo member may have none)
     * @throws DataPrismConfigurationException with the code the register defines for the first failure
     */
    static Optional<ClusterKeys> resolve(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        HazelcastProperties.Security security = properties.getHazelcast().getSecurity();
        if (!properties.getHazelcast().securitySet()) {
            return Optional.empty();
        }
        HazelcastProperties.ClusterKey cluster = security.getClusterKey();
        HazelcastProperties.NameKey name = security.getNameKey();
        List<Resolved> clusterKeys = new ArrayList<>();
        Resolved nameKey = null;
        byte[] hmac = null;
        usableId("CLUSTER_KEY", CLUSTER_KEY, cluster.getKeyId());
        usableId("CLUSTER_KEY", CLUSTER_KEY + ".next", cluster.getNext().getKeyId());
        usableId("CLUSTER_KEY", CLUSTER_KEY + ".previous", cluster.getPrevious().getKeyId());
        usableId("NAME_KEY", NAME_KEY, name.getKeyId());
        try {
            clusterKeys.add(resolve(resolver, new Ref(CLUSTER_KEY, "CLUSTER_KEY", cluster.getKeyId(),
                    source(cluster.getEnvironmentVariable(), cluster.getProviderReference()))));
            rotation(resolver, clusterKeys, CLUSTER_KEY + ".next", cluster.getNext());
            rotation(resolver, clusterKeys, CLUSTER_KEY + ".previous", cluster.getPrevious());
            nameKey = resolve(resolver, new Ref(NAME_KEY, "NAME_KEY", name.getKeyId(),
                    source(name.getEnvironmentVariable(), name.getProviderReference())));

            hmac = privacyKey(properties, resolver);
            for (Resolved key : clusterKeys) {
                shared(key, hmac, properties.getPrivacy().getHmacKey().getKeyId());
            }
            shared(nameKey, hmac, properties.getPrivacy().getHmacKey().getKeyId());
            for (Resolved key : clusterKeys) {
                if (MessageDigest.isEqual(nameKey.material, key.material)) {
                    throw new DataPrismConfigurationException("NAME_KEY_SHARED",
                            NAME_KEY + " must differ from " + key.ref.property());
                }
            }
            distinctIds(clusterKeys, nameKey);

            Resolved current = clusterKeys.get(0);
            Resolved next = find(clusterKeys, CLUSTER_KEY + ".next");
            Resolved previous = find(clusterKeys, CLUSTER_KEY + ".previous");
            return Optional.of(new ClusterKeys(current.ref.keyId(), current.material,
                    next == null ? null : next.ref.keyId(), next == null ? null : next.material,
                    previous == null ? null : previous.ref.keyId(), previous == null ? null : previous.material,
                    nameKey.ref.keyId(), nameKey.material));
        } finally {
            for (Resolved key : clusterKeys) {
                Arrays.fill(key.material, (byte) 0);
            }
            if (nameKey != null) {
                Arrays.fill(nameKey.material, (byte) 0);
            }
            if (hmac != null) {
                Arrays.fill(hmac, (byte) 0);
            }
        }
    }

    /** A key id that is set must be usable: not blank, no dot. Checked per property before anything is resolved. */
    private static void usableId(String area, String property, String keyId) {
        if (keyId != null && (keyId.isBlank() || keyId.indexOf('.') >= 0)) {
            throw new DataPrismConfigurationException(area + "_UNRESOLVED",
                    property + ".key-id must not be blank or contain a dot");
        }
    }

    private static void rotation(HmacKeyReferenceResolver resolver, List<Resolved> out, String property,
                                 HazelcastProperties.RotationKey key) {
        if (key.getKeyId() == null || key.getKeyId().isBlank()) {
            return;
        }
        out.add(resolve(resolver, new Ref(property, "CLUSTER_KEY", key.getKeyId(),
                source(key.getEnvironmentVariable(), key.getProviderReference()))));
    }

    private static Resolved find(List<Resolved> keys, String property) {
        for (Resolved key : keys) {
            if (key.ref.property().equals(property)) {
                return key;
            }
        }
        return null;
    }

    private static String source(String environmentVariable, String providerReference) {
        return environmentVariable == null || environmentVariable.isBlank() ? providerReference
                : environmentVariable;
    }

    private static Resolved resolve(HmacKeyReferenceResolver resolver, Ref ref) {
        byte[] material;
        try {
            material = resolver.resolve(ref.keyId(), ref.reference());
        } catch (RuntimeException unresolved) {
            throw new DataPrismConfigurationException(ref.area() + "_UNRESOLVED",
                    ref.property() + " could not be resolved");
        }
        if (material == null) {
            throw new DataPrismConfigurationException(ref.area() + "_UNRESOLVED",
                    ref.property() + " could not be resolved");
        }
        if (material.length < ClusterKeys.MIN_KEY_BYTES) {
            Arrays.fill(material, (byte) 0);
            throw new DataPrismConfigurationException(ref.area() + "_WEAK",
                    ref.property() + " resolves to material shorter than " + ClusterKeys.MIN_KEY_BYTES + " bytes");
        }
        return new Resolved(ref, material);
    }

    /** The privacy HMAC key, already validated before the cluster is built. */
    private static byte[] privacyKey(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        PrivacyProperties.HmacKey hmac = properties.getPrivacy().getHmacKey();
        String reference = hmac.getEnvironmentVariable();
        if (reference == null || reference.isBlank()) {
            reference = hmac.getProviderReference();
        }
        try {
            return resolver.resolve(hmac.getKeyId(), reference);
        } catch (RuntimeException unresolved) {
            // The HMAC key's own refusal comes from its own check, which runs before the cluster is built.
            return null;
        }
    }

    private static void shared(Resolved key, byte[] hmac, String hmacKeyId) {
        boolean sameId = key.ref.keyId().equals(hmacKeyId);
        boolean sameMaterial = hmac != null && MessageDigest.isEqual(key.material, hmac);
        if (sameId || sameMaterial) {
            throw new DataPrismConfigurationException(key.ref.area() + "_SHARED",
                    key.ref.property() + " must differ from dataprism.privacy.hmac-key");
        }
    }

    private static void distinctIds(List<Resolved> clusterKeys, Resolved nameKey) {
        List<Resolved> all = new ArrayList<>(clusterKeys);
        all.add(nameKey);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).ref.keyId().equals(all.get(j).ref.keyId())) {
                    throw new DataPrismConfigurationException("DUPLICATE_CLUSTER_KEY_ID",
                            all.get(j).ref.property() + ".key-id must differ from " + all.get(i).ref.property()
                                    + ".key-id");
                }
            }
        }
    }
}
