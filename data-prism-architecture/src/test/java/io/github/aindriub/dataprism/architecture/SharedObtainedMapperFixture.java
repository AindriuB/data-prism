package io.github.aindriub.dataprism.mapper.fixture;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Negative fixture for {@code ArchitectureTest#onlyDesignatedClassesCreateMappers}: obtains
 * Jackson's process-wide shared mapper instead of building one. Like the other fixtures it is a
 * test class under {@code io.github.aindriub.dataprism..}, outside the allowlist.
 */
public final class SharedObtainedMapperFixture {

    static ObjectMapper obtained() {
        return JsonMapper.shared();
    }
}
