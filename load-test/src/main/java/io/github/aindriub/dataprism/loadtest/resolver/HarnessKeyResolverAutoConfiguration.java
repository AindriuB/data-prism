package io.github.aindriub.dataprism.loadtest.resolver;

import io.github.aindriub.dataprism.core.spi.KeyResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers {@link HarnessKeyResolver} as the {@link KeyResolver} bean the link wiring collects. */
@AutoConfiguration
public class HarnessKeyResolverAutoConfiguration {

    @Bean
    KeyResolver harnessKeyResolver() {
        return new HarnessKeyResolver();
    }
}
