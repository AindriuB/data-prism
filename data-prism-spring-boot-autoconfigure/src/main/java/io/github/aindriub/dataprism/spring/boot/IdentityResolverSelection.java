package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;



/**
 * Selects the built-in {@link PassThroughIdentityResolver} for an operator
 * with no Java to write, opt-in only. {@code @ConditionalOnMissingBean} is the
 * deliberate choice for acceptance item 4: an application-supplied
 * {@link IdentityResolver} always wins over this one, never producing two.
 * Its own {@code @Bean} method is declared on this nested, imported class —
 * rather than directly on {@link DataPrismAutoConfiguration} — purely for
 * bean-definition ordering: imported ahead of it (see the class-level
 * {@code @Import} above), its conditional bean definition, when the property
 * selects it, is registered before {@code DataPrismAutoConfiguration}'s own
 * {@code @Bean} methods run, so {@code @ConditionalOnMissingBean} here and
 * {@code @ConditionalOnBean(IdentityResolver.class)} on beans declared below
 * it both see the correct, final state rather than racing bean processing
 * order.
 */
@Configuration(proxyBeanMethods = false)
class IdentityResolverSelection {
    @Bean
    @ConditionalOnMissingBean(IdentityResolver.class)
    @ConditionalOnProperty(prefix = "dataprism.identity", name = "resolver", havingValue = "pass-through")
    IdentityResolver dataPrismPassThroughIdentityResolver() {
        return new PassThroughIdentityResolver();
    }
}
