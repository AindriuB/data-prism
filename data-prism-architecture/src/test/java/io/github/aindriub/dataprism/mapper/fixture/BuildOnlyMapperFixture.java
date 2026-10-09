package io.github.aindriub.dataprism.mapper.fixture;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.MapperBuilder;

/**
 * Negative fixture for {@code ArchitectureTest#onlyDesignatedClassesCreateMappers}: it only calls
 * {@code build()} on a {@link MapperBuilder} that is passed in. It never constructs a mapper, calls
 * a static {@code builder()} or {@code rebuild()}, so only the {@code build()} clause can catch it.
 * It resides under {@code io.github.aindriub.dataprism..} outside the allowlist.
 */
public final class BuildOnlyMapperFixture {

    public static ObjectMapper finish(MapperBuilder<? extends ObjectMapper, ?> builder) {
        return builder.build();
    }
}
