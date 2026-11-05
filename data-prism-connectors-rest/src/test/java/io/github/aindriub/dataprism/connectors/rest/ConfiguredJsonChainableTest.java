package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.engine.ChainableField;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonDescriptionTest.HEAD;
import static io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonDescriptionTest.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code chainable} and {@code keyNamespace} in a {@code fields:} entry (task 249). */
class ConfiguredJsonChainableTest {

    private static final String ID = "identifier: true\n";
    private static final String INERT = "nonSensitive: \"Enumerated lifecycle state\"\n";
    private static final String CHAIN = "chainable: true\nkeyNamespace: parcel-ref\n";

    private static String fields(String parcel, String state) {
        return HEAD + "    fields:\n      parcel:\n" + parcel.indent(8) + "      state:\n" + state.indent(8);
    }

    @Test
    @DisplayName("chainable with a key namespace on the identifier is exposed in the ChainableField shape")
    void accepted() {
        assertThat(parse(fields(ID + CHAIN, INERT)).chainableFields())
                .containsExactly(new ChainableField("parcel", new KeyNamespace("parcel-ref"), false));
    }

    @Test
    @DisplayName("an identifier without the flag is not chainable")
    void notChainableByDefault() {
        assertThat(parse(fields(ID, INERT)).chainableFields()).isEmpty();
    }

    @Test
    @DisplayName("every other shape refuses with INVALID_CHAINABLE_FIELD naming the source and the field")
    void refused() {
        String[][] shapes = {
                {ID, INERT + CHAIN, "state"},
                {ID, "classifications: [PII]\n" + CHAIN, "state"},
                {ID + "chainable: true\n", INERT, "parcel"},
                {ID + "keyNamespace: parcel-ref\n", INERT, "parcel"},
                {ID + "chainable: false\nkeyNamespace: parcel-ref\n", INERT, "parcel"},
                {ID + "chainable: true\nkeyNamespace: Not A Namespace\n", INERT, "parcel"},
        };
        for (String[] shape : shapes) {
            assertThatThrownBy(() -> parse(fields(shape[0], shape[1])))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("INVALID_CHAINABLE_FIELD:")
                    .hasMessageContaining("parcel-service")
                    .hasMessageContaining("field " + shape[2]);
        }
    }

    @Test
    @DisplayName("inside a nested catalogue it refuses too")
    void nestedCatalogueRefused() {
        String yaml = HEAD + """
                nested-catalogues:
                  stop:
                    place:
                      nonSensitive: "depot"
                      chainable: true
                      keyNamespace: depot-ref
                fields:
                  parcel:
                    identifier: true
                  stops:
                    nested: stop
                """.indent(4);
        assertThatThrownBy(() -> parse(yaml))
                .hasMessageStartingWith("INVALID_CHAINABLE_FIELD:")
                .hasMessageContaining("place");
    }
}
