package io.github.aindriub.dataprism.quickstart.extension.identity;

import io.github.aindriub.dataprism.core.IdentityResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How an ordinary Spring Boot application — one that simply depends on
 * {@code data-prism-spring-boot-starter} and component-scans its own code,
 * unlike a {@code -Dloader.path} reviewed extension jar such as this very
 * module — registers its own {@link IdentityResolver}: a plain {@code
 * @Configuration} class in a package the application already scans, with an
 * ordinary {@code @Bean}, no different from any other bean in that
 * application. It needs no {@code @ConditionalOnMissingBean} of its own —
 * {@code QuickstartExtensionAutoConfiguration.quickstartIdentityResolver()}
 * already carries that annotation, so an application-supplied bean always
 * wins over it, never producing two.
 *
 * <p>This class is deliberately <strong>not</strong> what a {@code
 * -Dloader.path} extension (like this module's own quickstart jar) would
 * use to register a resolver of its own — nothing on such a jar is
 * component-scanned, so a plain {@code @Configuration} placed here would
 * never run. That case instead needs its own {@code @AutoConfiguration}
 * class, registered the same way {@code
 * QuickstartExtensionAutoConfiguration} itself is: one line in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * — see {@code docs/developer-guide/write-an-adapter.md}'s "Wire it up with
 * auto-configuration" step for that mechanism. That page documents no
 * ordering between auto-configuration classes; on its own, being on that
 * list does not say whether this bean or the quickstart's own {@code
 * @ConditionalOnMissingBean} default registers first, and either one
 * arriving first changes the outcome. {@link
 * ExampleOrderedIdentityResolverAutoConfiguration} is the version written for
 * that case, with the explicit ordering this class does not need. This class
 * is not on that list, and is never component-scanned by the quickstart
 * itself; it is exercised only by this module's own {@code
 * IdentityResolverOverrideTest}, which is what proves the ordinary-application
 * claim above rather than merely asserting it in prose.
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
