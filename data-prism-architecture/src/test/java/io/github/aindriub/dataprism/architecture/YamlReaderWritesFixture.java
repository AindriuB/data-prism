package io.github.aindriub.dataprism.mapper.fixture;

import java.io.ByteArrayOutputStream;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Negative fixture for {@code ArchitectureTest#designatedYamlReadersDoNotWrite}: a parse-only
 * class that starts writing, by mapper and by generator.
 */
public final class YamlReaderWritesFixture {

    static void startsWriting(ObjectMapper mapper, YAMLFactory factory) {
        mapper.writeValueAsString("x");
        mapper.writer();
        factory.createGenerator(new ByteArrayOutputStream());
        mapper.writer().createGenerator(new ByteArrayOutputStream());
    }
}
