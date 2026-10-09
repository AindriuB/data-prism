package io.github.aindriub.dataprism.mapper.fixture;

import java.util.function.Supplier;

import io.modelcontextprotocol.json.McpJsonMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Negative fixture for {@code ArchitectureTest#noPublicApiExposesAnObjectMapper}: every way a
 * public or protected member can expose a mapper, plus one package-private method that must not
 * be reported.
 */
public class PublicMapperFixture {

    public ObjectMapper exposedField;

    public PublicMapperFixture(ObjectMapper constructorParameter) {
    }

    public JsonMapper exposedMapper() {
        return null;
    }

    public Supplier<ObjectMapper> exposedProvider() {
        return null;
    }

    protected void exposedParameter(ObjectMapper mapper) {
    }

    public MapperBuilder<?, ?> exposedBuilder() {
        return null;
    }

    public ObjectWriter exposedWriter() {
        return null;
    }

    public McpJsonMapper exposedMcpMapper() {
        return null;
    }

    JsonMapper hiddenMapper() {
        return null;
    }
}
