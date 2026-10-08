package io.github.aindriub.dataprism.audit.fixture;

import io.github.aindriub.dataprism.mcp.CorrelationRequirement;

/**
 * Negative fixture: resides in a {@code ..dataprism.audit..} package and depends on
 * an {@code mcp} type. It is excluded from {@code CLASSES} because that import
 * reads only the other modules' main {@code target/classes} with {@code
 * DO_NOT_INCLUDE_TESTS}, so this test class is never in the graph.
 */
public final class AuditDependsOnMcpFixture {

    public static Class<?> outward() {
        return CorrelationRequirement.class;
    }
}
