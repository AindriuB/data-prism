package io.github.aindriub.dataprism.mapper.fixture;

import java.io.ByteArrayOutputStream;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Negative fixture for {@code ArchitectureTest#onlyDesignatedClassesConstructJsonFactories}:
 * constructs and derives streaming factories and creates a generator, outside the allowlist.
 */
public final class JsonFactoryFixture {

    static void everyWay(JsonFactory existing, JsonMapper mapper) {
        new JsonFactory();
        JsonFactory built = JsonFactory.builder().build();
        built.createGenerator(new ByteArrayOutputStream());
        existing.rebuild();
        mapper.createGenerator(new ByteArrayOutputStream());
        mapper.writer().createGenerator(new ByteArrayOutputStream());
    }
}
