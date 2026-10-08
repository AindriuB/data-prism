package io.github.aindriub.dataprism.audit.fixture;

import io.github.aindriub.dataprism.mcp.CorrelationRequirement;

/**
 * Negative fixture: resides in a {@code ..dataprism.audit..} package and depends on
 * an {@code mcp} type. The package declaration deliberately does not match this
 * file's directory, so the class is never part of {@code CLASSES}.
 */
public final class AuditDependsOnMcpFixture {

    public static Class<?> outward() {
        return CorrelationRequirement.class;
    }
}
