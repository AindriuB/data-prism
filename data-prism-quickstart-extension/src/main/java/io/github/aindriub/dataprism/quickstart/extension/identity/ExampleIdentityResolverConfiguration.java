package io.github.aindriub.dataprism.quickstart.extension.identity;

import io.github.aindriub.dataprism.core.IdentityResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How an application registers its own {@link IdentityResolver}: an ordinary
 * {@code @Bean}, in an application's own configuration, no different from any
 * other bean. It needs no {@code @ConditionalOnMissingBean} of its own —
 * {@code QuickstartExtensionAutoConfiguration.quickstartIdentityResolver()}
 * already carries that annotation, so an application-supplied bean always
 * wins over it, never producing two.
 *
 * <p>This class is not on the {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * list, and is never component-scanned by the quickstart itself; it is
 * exercised only by this module's own {@code
 * IdentityResolverOverrideTest}, which is what proves the claim above rather
 * than merely asserting it in prose.
 */
// --8<-- [start:registration]
@Configuration
class ExampleIdentityResolverConfiguration {

    @Bean
    IdentityResolver customIdentityResolver() {
        return new MappedIdentityResolver(ExampleIdentityMapping.keysByCanonicalId());
    }
}
// --8<-- [end:registration]
