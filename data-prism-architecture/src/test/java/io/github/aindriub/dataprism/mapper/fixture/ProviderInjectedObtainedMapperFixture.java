package io.github.aindriub.dataprism.mapper.fixture;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Negative fixture for {@code ArchitectureTest#springManagedClassesDoNotInjectAMapper}: a bean
 * method that takes the auto-configured mapper behind an {@code ObjectProvider}, so the mapper
 * appears only as a type argument. The class has a {@code @Bean} member but no class annotation.
 */
public final class ProviderInjectedObtainedMapperFixture {

    @Bean
    Object bean(ObjectProvider<ObjectMapper> mappers) {
        return mappers;
    }
}
