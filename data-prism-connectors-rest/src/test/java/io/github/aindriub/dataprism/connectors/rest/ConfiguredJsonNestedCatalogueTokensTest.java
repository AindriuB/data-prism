package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConfiguredJsonNestedCatalogueTokens} is the one piece of machinery
 * that lets this connector give core's {@code Class}-keyed {@code
 * FieldMetadataResolver} a real descent target for a named nested catalogue,
 * without core growing a second, string-keyed way to resolve metadata. See
 * docs/plan/tasks/60-*.md's settled design.
 *
 * <p>Tokens come from a bounded, fixed pool of pre-declared marker types
 * rather than a runtime-generated class -- see the attempt-1 write-up in that
 * task file for why a generated class was rejected.
 */
class ConfiguredJsonNestedCatalogueTokensTest {

    @Test
    @DisplayName("distinct ordinals within one source get distinct tokens")
    void distinctOrdinalsGetDistinctTokens() {
        Class<?> a = ConfiguredJsonNestedCatalogueTokens.mint("customer-api", 0);
        Class<?> b = ConfiguredJsonNestedCatalogueTokens.mint("customer-api", 1);

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(String.class).isNotEqualTo(Object.class);
    }

    @Test
    @DisplayName("the same ordinal always resolves to the same token")
    void sameOrdinalIsDeterministic() {
        Class<?> first = ConfiguredJsonNestedCatalogueTokens.mint("customer-api", 3);
        Class<?> second = ConfiguredJsonNestedCatalogueTokens.mint("customer-api", 3);
        Class<?> otherSource = ConfiguredJsonNestedCatalogueTokens.mint("another-source", 3);

        assertThat(first).isEqualTo(second).isEqualTo(otherSource);
    }

    @Test
    @DisplayName("a minted token is a real, usable, stably-named Class")
    void mintedTokenIsARealClassWithAStableName() {
        Class<?> firstRun = ConfiguredJsonNestedCatalogueTokens.mint("customer-api", 0);
        Class<?> secondRun = ConfiguredJsonNestedCatalogueTokens.mint("customer-api", 0);

        assertThat(firstRun).isNotNull();
        assertThat(firstRun.isHidden()).isFalse();
        // Same name across two independent calls simulating two runs -- unlike a
        // runtime-generated hidden class, whose Class#getName() differs every time.
        assertThat(firstRun.getName())
                .isEqualTo(secondRun.getName())
                .startsWith("io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonNestedCatalogueSlot");
    }

    @Test
    @DisplayName("startup refusal: a source declaring more nested catalogues than the pool holds is refused, naming the source")
    void poolExhaustionRefusesNamingSource() {
        int poolSize = ConfiguredJsonNestedCatalogueTokens.POOL.size();
        assertThatThrownBy(() -> ConfiguredJsonNestedCatalogueTokens.mint("overflowing-source", poolSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflowing-source")
                .hasMessageContaining(String.valueOf(poolSize));
    }
}
