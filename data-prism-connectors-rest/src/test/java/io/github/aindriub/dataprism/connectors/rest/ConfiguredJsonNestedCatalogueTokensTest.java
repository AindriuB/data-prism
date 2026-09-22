package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfiguredJsonNestedCatalogueTokens} is the one piece of machinery
 * that lets this connector give core's {@code Class}-keyed {@code
 * FieldMetadataResolver} a real descent target for a named nested catalogue,
 * without core growing a second, string-keyed way to resolve metadata. See
 * docs/plan/tasks/60-*.md's settled design.
 */
class ConfiguredJsonNestedCatalogueTokensTest {

    @Test
    @DisplayName("every mint is a distinct Class, even for the same catalogue name")
    void everyMintIsDistinct() {
        Class<?> a = ConfiguredJsonNestedCatalogueTokens.mint("address");
        Class<?> b = ConfiguredJsonNestedCatalogueTokens.mint("address");
        Class<?> c = ConfiguredJsonNestedCatalogueTokens.mint("employer");

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(b).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(String.class).isNotEqualTo(Object.class);
    }

    @Test
    @DisplayName("a minted token is a real, usable Class -- FieldMetadataResolver only ever asks for its identity")
    void mintedTokenIsARealClass() {
        Class<?> token = ConfiguredJsonNestedCatalogueTokens.mint("address");
        assertThat(token).isNotNull();
        assertThat(token.isHidden()).isTrue();
    }
}
