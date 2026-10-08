package io.github.aindriub.dataprism.mapper.fixture;

import tools.jackson.databind.ObjectMapper;

/**
 * Negative fixture for {@code ArchitectureTest#onlyDesignatedClassesCreateMappers}: constructs a mapper directly.
 * It resides under {@code io.github.aindriub.dataprism..} outside the allowlist. Like the
 * other fixtures it is a test class, so it is never in the whole-graph import of the main
 * classes, and only the negative test sees it.
 */
public final class ConstructorMapperFixture {

    public static ObjectMapper constructed() {
        return new ObjectMapper();
    }
}
