package io.github.aindriub.dataprism.quickstart.extension.identity;

import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.quickstart.extension.QuickstartExtensionAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code -Dloader.path} extension registration guidance in {@code
 * docs/developer-guide/custom-identity-resolver.md}: an extension's own
 * {@code @AutoConfiguration} must declare {@code before =
 * QuickstartExtensionAutoConfiguration.class} to win over the quickstart's
 * {@code @ConditionalOnMissingBean} default deterministically.
 *
 * <p>{@link SingleIdentityResolverConsumer} stands in for {@code
 * DataPrismAutoConfiguration.dataPrismContextOrchestrator}: the same shape —
 * a {@code @Bean} method with exactly one {@link IdentityResolver} parameter
 * — is what actually fails when two candidates exist, not the preflight
 * (which only counts bean names and does not require uniqueness). This
 * module cannot boot the real {@code DataPrismAutoConfiguration} in a unit
 * test without also wiring its unrelated JWT, audit-sink and Hazelcast
 * topology preconditions, none of which this ordering question is about;
 * the stand-in reproduces the exact ambiguity Spring itself raises
 * ({@code NoUniqueBeanDefinitionException}) for the same shape of injection
 * point, honestly, without claiming to boot production wiring this module
 * does not own.
 *
 * <p>Both {@code @AutoConfiguration} classes below are fed to {@code
 * AutoConfigurations.of} with {@link QuickstartExtensionAutoConfiguration}
 * listed <em>first</em> — the ordering an extension author does not control
 * and must not rely on — so anything this test proves comes from the {@code
 * @AutoConfiguration} annotations themselves, not from argument order.
 */
class IdentityResolverOrderingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // QuickstartExtensionAutoConfiguration also declares a DataSourceAdapter
            // bean whose @Value properties have no default; these two values are
            // only here so that bean can be created too.
            .withPropertyValues(
                    "dataprism.sources.customer.base-url=https://customer.example.invalid",
                    "dataprism.sources.customer.timeout=5s")
            .withUserConfiguration(SingleIdentityResolverConsumer.class);

    @Test
    void anOrderedAutoConfigurationWinsOverTheQuickstartDefault() {
        runner.withConfiguration(AutoConfigurations.of(
                        QuickstartExtensionAutoConfiguration.class,
                        ExampleOrderedIdentityResolverAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(SingleIdentityResolverConsumer.Holder.class).identities())
                        .isInstanceOf(MappedIdentityResolver.class));
    }

    /**
     * The failure {@code before} exists to avoid: an extension's own {@code
     * @AutoConfiguration} with no ordering relative to the quickstart's
     * default is not merely "may lose the override" — with the quickstart's
     * default processed first (as it is here, listed first with neither
     * class declaring an order), both beans register, and the context fails
     * to start once anything asks for exactly one {@link IdentityResolver}.
     */
    @Test
    void anUnorderedAutoConfigurationCanProduceTwoBeansAndFailToStart() {
        runner.withConfiguration(AutoConfigurations.of(
                        QuickstartExtensionAutoConfiguration.class,
                        UnorderedIdentityResolverAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @AutoConfiguration
    static class UnorderedIdentityResolverAutoConfiguration {

        @Bean
        IdentityResolver unorderedCustomIdentityResolver() {
            return new MappedIdentityResolver(ExampleIdentityMapping.keysByCanonicalId());
        }
    }

    /**
     * Stands in for {@code DataPrismAutoConfiguration.dataPrismContextOrchestrator}
     * — see the class Javadoc above for why the real class is not used directly.
     */
    @Configuration(proxyBeanMethods = false)
    static class SingleIdentityResolverConsumer {

        /** A distinct type, deliberately: wrapping {@code identities} rather than
         * returning it directly keeps this bean's own runtime type from also
         * matching {@code getBeanNamesForType(IdentityResolver.class)}, which
         * would otherwise miscount the very ambiguity this class exists to
         * surface. */
        record Holder(IdentityResolver identities) {
        }

        @Bean
        Holder identityResolverConsumer(IdentityResolver identities) {
            return new Holder(identities);
        }
    }
}
