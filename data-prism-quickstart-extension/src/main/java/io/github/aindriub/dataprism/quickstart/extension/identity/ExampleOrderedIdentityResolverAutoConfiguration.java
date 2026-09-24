package io.github.aindriub.dataprism.quickstart.extension.identity;

import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.quickstart.extension.QuickstartExtensionAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * How a reviewed {@code -Dloader.path} extension jar registers its own
 * {@link IdentityResolver} and wins over the quickstart's {@code
 * @ConditionalOnMissingBean} default deterministically, unlike {@link
 * ExampleIdentityResolverConfiguration}'s plain {@code @Configuration}, which
 * a {@code -Dloader.path} jar never component-scans.
 *
 * <p>Being listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * is not enough on its own: Spring Boot gives no ordering promise between two
 * unrelated {@code @AutoConfiguration} classes just because both are on that
 * list. If this bean is registered <em>after</em> {@code
 * QuickstartExtensionAutoConfiguration}'s own {@code @ConditionalOnMissingBean}
 * bean, the quickstart's default has already been registered by then, and this
 * class's unconditional {@code @Bean} adds a second {@link IdentityResolver}
 * — {@code DataPrismAutoConfiguration.dataPrismContextOrchestrator}'s single
 * {@code IdentityResolver} parameter then fails to resolve, and startup fails
 * with {@code NoUniqueBeanDefinitionException}, not with a working override.
 *
 * <p>{@code before = QuickstartExtensionAutoConfiguration.class} is what
 * removes that risk: it tells Spring Boot to process this class first, so by
 * the time the quickstart's own conditional bean method is considered, this
 * one is already registered and {@code @ConditionalOnMissingBean} correctly
 * steps aside. {@code
 * IdentityResolverOrderingTest.anOrderedAutoConfigurationWinsOverTheQuickstartDefault}
 * proves this exact ordering resolves to one bean, this one; its sibling test
 * proves the failure this annotation avoids.
 */
// --8<-- [start:ordered-registration]
@AutoConfiguration(before = QuickstartExtensionAutoConfiguration.class)
class ExampleOrderedIdentityResolverAutoConfiguration {

    @Bean
    IdentityResolver orderedCustomIdentityResolver() {
        return new MappedIdentityResolver(ExampleIdentityMapping.keysByCanonicalId());
    }
}
// --8<-- [end:ordered-registration]
