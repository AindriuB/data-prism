package io.github.aindriub.dataprism.connectors.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;

/**
 * Mints one distinct {@code Class} token per named nested JSON catalogue.
 *
 * <p>Core's {@link io.github.aindriub.dataprism.core.FieldMetadata} and {@link
 * io.github.aindriub.dataprism.core.FieldMetadataResolver} key everything on
 * {@code Class<?>}, never on a name, and that is deliberately not something
 * this feature is allowed to change (see docs/plan/tasks/60-*.md). A
 * configuration-driven JSON source has no compiled Java type to hand core for
 * a nested catalogue the way an annotated model has one for a nested field --
 * so this mints one, at parse time, via {@link
 * MethodHandles.Lookup#defineHiddenClass}: the same fixed template bytes,
 * redefined as a brand new hidden class on every call, produce a fresh {@code
 * Class} object each time. Holding that object anywhere (a {@code
 * FieldMetadata.valueType()}, a resolver's lookup map) is what keeps it alive;
 * nothing about the class itself is ever used besides its identity.
 */
final class ConfiguredJsonNestedCatalogueTokens {

    private static final byte[] TEMPLATE = readTemplate();

    private ConfiguredJsonNestedCatalogueTokens() {
    }

    /**
     * @param catalogueName purely for the exception message if minting fails;
     *                      the returned token carries no memory of this name,
     *                      since nothing downstream is allowed to resolve on
     *                      a string
     */
    static Class<?> mint(String catalogueName) {
        try {
            return MethodHandles.lookup().defineHiddenClass(TEMPLATE, false).lookupClass();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "could not mint a Class token for nested catalogue '" + catalogueName + "'", e);
        }
    }

    private static byte[] readTemplate() {
        String resource = ConfiguredJsonNestedCatalogueTemplate.class.getName().replace('.', '/') + ".class";
        ClassLoader loader = ConfiguredJsonNestedCatalogueTemplate.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("nested-catalogue token template not found on the classpath: "
                        + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read nested-catalogue token template", e);
        }
    }
}
