package io.github.aindriub.dataprism.quickstart.extension.identity;

import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.quickstart.extension.QuickstartExtensionAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the tutorial's wiring claim: an application-supplied {@link
 * IdentityResolver} bean, such as {@link ExampleIdentityResolverConfiguration}'s,
 * replaces {@code QuickstartExtensionAutoConfiguration}'s own
 * {@code @ConditionalOnMissingBean} {@link PassThroughIdentityResolver} — the
 * quickstart's registration is never changed, only shadowed, in a context
 * that also declares one.
 */
class IdentityResolverOverrideTest {

    // QuickstartExtensionAutoConfiguration also declares a DataSourceAdapter bean
    // whose @Value properties have no default; these two values are only here so
    // that bean can be created too — this test asserts nothing about it.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(
                    "dataprism.sources.customer.base-url=https://customer.example.invalid",
                    "dataprism.sources.customer.timeout=5s")
            .withConfiguration(AutoConfigurations.of(QuickstartExtensionAutoConfiguration.class));

    @Test
    void quickstartFallsBackToPassThroughWithNoOtherBean() {
        runner.run(context -> assertThat(context).getBean(IdentityResolver.class)
                .isInstanceOf(PassThroughIdentityResolver.class));
    }

    @Test
    void anApplicationSuppliedResolverWinsOverTheQuickstartDefault() {
        runner.withUserConfiguration(ExampleIdentityResolverConfiguration.class)
                .run(context -> assertThat(context).getBean(IdentityResolver.class)
                        .isInstanceOf(MappedIdentityResolver.class));
    }
}
