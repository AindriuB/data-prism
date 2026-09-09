package io.github.aindriub.dataprism.core;

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
    REIDENTIFICATION("dataprism.reidentification");

    private final String metricName;

    Metric(String metricName) {
        this.metricName = metricName;
    }

    public String metricName() {
        return metricName;
    }
}
