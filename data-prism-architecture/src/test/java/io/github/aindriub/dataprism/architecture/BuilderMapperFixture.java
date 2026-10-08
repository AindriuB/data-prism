package io.github.aindriub.dataprism.mapper.fixture;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Negative fixture for {@code ArchitectureTest#onlyDesignatedClassesCreateMappers}: builds a mapper with a Jackson 3 builder.
 * It resides under {@code io.github.aindriub.dataprism..} outside the allowlist. Like the
 * other fixtures it is a test class, so it is never in the whole-graph import of the main
 * classes, and only the negative test sees it.
 */
public final class BuilderMapperFixture {

    public static ObjectMapper built() {
        return JsonMapper.builder().build();
    }
}
