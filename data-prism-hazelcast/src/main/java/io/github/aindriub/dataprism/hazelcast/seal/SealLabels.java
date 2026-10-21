package io.github.aindriub.dataprism.hazelcast.seal;

/** The key-derivation labels of docs/plan/design/cluster-security.md 4.7. {@code <map>} is the full map name. */
final class SealLabels {

    static final String VERSION = "dps1";
    static final String AEAD_PREFIX = "dataprism/cluster/v1/aead/";
    static final String KCV = "dataprism/cluster/v1/kcv";
    static final String NAME_PREFIX = "dataprism/cluster/v1/name/";
    static final String SCOPE = "dataprism/cluster/v1/scope";

    private SealLabels() {}
}
