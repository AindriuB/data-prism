package io.github.aindriub.dataprism.mapper.fixture;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Negative fixture for {@code ArchitectureTest#springManagedClassesDoNotInjectAMapper}: carries
 * no Spring annotation at all (like a class registered through {@code spring.factories}) but
 * looks the container's mapper up by type.
 */
public final class ContextLookupObtainedMapperFixture
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getBean(JsonMapper.class);
    }
}
