package io.github.aindriub.dataprism.connectors.rest;

/**
 * Fixed template bytes for {@link ConfiguredJsonNestedCatalogueTokens}.
 *
 * <p>This class is never instantiated and never referred to at runtime as
 * itself; only its compiled {@code .class} bytes are used, as the fixed
 * template that {@link ConfiguredJsonNestedCatalogueTokens#mint(String)}
 * redefines as a hidden class once per configured nested catalogue. Each
 * redefinition mints a brand new, distinct {@code Class} object even though
 * the bytes are identical every time, which is exactly what lets one {@code
 * Class} token per nested catalogue exist without core's {@code
 * FieldMetadata}/{@code FieldMetadataResolver} SPI growing a second,
 * string-keyed way to resolve metadata: the key stays {@code Class<?>}, this
 * connector just mints one.
 */
final class ConfiguredJsonNestedCatalogueTemplate {

    private ConfiguredJsonNestedCatalogueTemplate() {
    }
}
