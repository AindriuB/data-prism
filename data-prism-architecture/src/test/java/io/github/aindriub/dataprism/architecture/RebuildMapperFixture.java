package io.github.aindriub.dataprism.mapper.fixture;

import tools.jackson.databind.ObjectMapper;

/**
 * Negative fixture for {@code ArchitectureTest#onlyDesignatedClassesCreateMappers}: derives a new mapper from an existing one with {@code rebuild().build()}.
 * It resides under {@code io.github.aindriub.dataprism..} outside the allowlist. Like the
 * other fixtures it is a test class, so it is never in the whole-graph import of the main
 * classes, and only the negative test sees it.
 */
public final class RebuildMapperFixture {

    public static ObjectMapper rebuilt(ObjectMapper existing) {
        return existing.rebuild().build();
    }
}
