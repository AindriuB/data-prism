package io.github.aindriub.dataprism.oversight.fixture;

import io.github.aindriub.dataprism.mcp.CorrelationRequirement;

/**
 * Negative fixture: resides in a {@code ..dataprism.oversight..} package and depends on
 * an {@code mcp} type. The package declaration deliberately does not match this
 * file's directory, so the class is never part of {@code CLASSES}.
 */
public final class OversightDependsOnMcpFixture {

    public static Class<?> outward() {
        return CorrelationRequirement.class;
    }
}
