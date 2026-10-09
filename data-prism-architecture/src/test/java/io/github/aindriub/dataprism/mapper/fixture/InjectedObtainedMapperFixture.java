package io.github.aindriub.dataprism.mapper.fixture;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Negative fixture for {@code ArchitectureTest#springManagedClassesDoNotInjectAMapper}: a Spring
 * component that takes the container's auto-configured mapper through its constructor.
 */
@Component
public final class InjectedObtainedMapperFixture {

    private final JsonMapper injected;

    InjectedObtainedMapperFixture(JsonMapper injected) {
        this.injected = injected;
    }
}
