package io.github.aindriub.dataprism.core.metrics;

/**
 * Every metric this platform emits, named once so a new one is a visible diff.
 *
 * <p>Covers docs/pack.md §89 and the three additions from docs/design-review.md
 * §E. Nothing here carries a value or a free-form tag — see {@link PrivacyMetrics}
 * for why.
 */
public enum Metric {

    MCP_REQUESTS("dataprism.mcp.requests"),
    MCP_DENIED("dataprism.mcp.denied"),
    PRIVACY_TRANSFORMATIONS("dataprism.privacy.transformations"),
    PRIVACY_VALIDATION_FAILURES("dataprism.privacy.validation.failures"),
    IDENTITY_CACHE_HIT("dataprism.identity.cache.hit"),
    IDENTITY_CACHE_MISS("dataprism.identity.cache.miss"),
    SOURCE_LATENCY("dataprism.source.latency"),
    SOURCE_ERRORS("dataprism.source.errors"),
    IDENTITY_COLLISION("dataprism.identity.collision"),
    PRIVACY_FAILCLOSED("dataprism.privacy.failclosed"),
    PRIVACY_SOURCE_WITHHELD("dataprism.privacy.source.withheld"),
    REIDENTIFICATION("dataprism.reidentification"),
    APPROVAL_RELEASE_SKIPPED("dataprism.approval.release.skipped"),
    APPROVAL_DECISION_AUDIT_UNAVAILABLE("dataprism.approval.decision.audit_unavailable"),
    APPROVAL_DECISION_UNCONFIRMED("dataprism.approval.decision.unconfirmed"),
    // One constant per refusal code, because PrivacyMetrics takes no free-form tag.
    AUDIT_RETENTION_CHAIN_UNVERIFIED("dataprism.audit.retention.unverified"),
    AUDIT_RETENTION_ANCHOR_FAILED("dataprism.audit.retention.anchor_failed"),
    AUDIT_RETENTION_DELETE_FAILED("dataprism.audit.retention.delete_failed"),
    AUDIT_RETENTION_FAILED("dataprism.audit.retention.failed"),
    // Cluster security (docs/plan/design/cluster-security.md 4.4).
    CLUSTER_SEAL_TAMPER("dataprism.cluster.seal.tamper"),
    CLUSTER_SEAL_UNSUPPORTED_FORMAT("dataprism.cluster.seal.unsupported_format"),
    CLUSTER_SEAL_SALT_REJECTED("dataprism.cluster.seal.salt_rejected"),
    CLUSTER_MEMBERS_UNVERIFIED("dataprism.cluster.members.unverified"),
    CLUSTER_PAUSE_FRESH("dataprism.cluster.pause.fresh"),
    CLUSTER_PAUSE_AGE_SECONDS("dataprism.cluster.pause.age_seconds"),
    CLUSTER_PAUSE_CONFLICT("dataprism.cluster.pause.conflict"),
    CLUSTER_ROTATION_ACCEPTING_NEXT("dataprism.cluster.rotation.accepting_next"),
    CLUSTER_ROTATION_BLOCKED("dataprism.cluster.rotation.blocked"),
    CLUSTER_ROTATION_BEHIND("dataprism.cluster.rotation.behind"),
    CLUSTER_SEAL_ENTRIES_UNDER_PREVIOUS("dataprism.cluster.seal.entries_under_previous"),
    CLUSTER_LOCK_TIMEOUT("dataprism.cluster.lock.timeout"),
    CLUSTER_FLOOR_REFUSAL("dataprism.cluster.floor.refusal"),
    CLUSTER_LEGACY_ENTRIES("dataprism.cluster.legacy.entries");

    private final String metricName;

    Metric(String metricName) {
        this.metricName = metricName;
    }

    public String metricName() {
        return metricName;
    }
}
