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
    REIDENTIFICATION("dataprism.reidentification"),
    // One constant per refusal code, because PrivacyMetrics takes no free-form tag.
    AUDIT_RETENTION_CHAIN_UNVERIFIED("dataprism.audit.retention.unverified"),
    AUDIT_RETENTION_ANCHOR_FAILED("dataprism.audit.retention.anchor_failed"),
    AUDIT_RETENTION_DELETE_FAILED("dataprism.audit.retention.delete_failed"),
    AUDIT_RETENTION_FAILED("dataprism.audit.retention.failed");

    private final String metricName;

    Metric(String metricName) {
        this.metricName = metricName;
    }

    public String metricName() {
        return metricName;
    }
}
