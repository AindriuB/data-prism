package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.spi.CostHint;
import io.github.aindriub.dataprism.core.spi.FreshnessHint;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code description:} block of a configured JSON source (task 249). */
class ConfiguredJsonDescriptionTest {

    private static final String SENTINEL = "SENTINEL-DESCRIPTION-TEXT-7731";

    static final String HEAD = """
            json-sources:
              parcel-service:
                base-url: https://parcel.example.internal
                path: /parcels/{subject}
                timeout: PT2S
                model-version: parcel-v1
                subject-json-path: parcel
            """;

    static final String FIELDS = """
                fields:
                  parcel:
                    identifier: true
                  state:
                    nonSensitive: "Enumerated lifecycle state; no free text"
            """;

    static ConfiguredJsonSource parse(String yaml) {
        return ConfiguredJsonSources.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .sources().get("parcel-service");
    }

    private static String withDescription(String block) {
        return HEAD + "    description:\n" + block.indent(6) + FIELDS;
    }

    @Test
    @DisplayName("a full description block reaches the adapter's description()")
    void fullBlock() {
        ConfiguredJsonSource source = parse(withDescription("""
                entity-types: [PARCEL]
                alias-description: "%s"
                real-description: "The parcel tracking service."
                input-keys: [parcel-ref]
                cost: LOW
                freshness: NEAR_LIVE
                """.formatted(SENTINEL)));

        SourceDescription description =
                new ConfiguredJsonDataSourceAdapter(source, RestClient.create()).description();

        assertThat(description).isEqualTo(new SourceDescription(Set.of("PARCEL"), SENTINEL,
                "The parcel tracking service.", List.of(new KeyNamespace("parcel-ref")), CostHint.LOW,
                FreshnessHint.NEAR_LIVE));
    }

    @Test
    @DisplayName("without a block the description is undeclared")
    void noBlock() {
        ConfiguredJsonSource source = parse(HEAD + FIELDS);
        assertThat(new ConfiguredJsonDataSourceAdapter(source, RestClient.create()).description())
                .isEqualTo(SourceDescription.undeclared());
    }

    @Test
    @DisplayName("any key but the six refuses with the existing unknown-key refusal")
    void unknownKey() {
        assertThatThrownBy(() -> parse(withDescription("""
                entity-types: [PARCEL]
                summary: "x"
                """)))
                .hasMessageContaining("UNKNOWN_CONFIG_KEY")
                .hasMessageContaining("'summary'");
    }

    @Test
    @DisplayName("description text never appears in a parser message")
    void textNeverInMessages() {
        String text = "alias-description: \"" + SENTINEL + "\"\n";
        String[] bad = {
                text + "real-description: 12\n",
                text + "cost: " + SENTINEL + "\n",
                text + "freshness: \"" + SENTINEL + "\"\n",
                text + "input-keys: [\"" + SENTINEL + "\"]\n",
                text + "entity-types: [\"" + SENTINEL + " !\"]\n",
                text + "surprise: 1\n",
                "real-description: \"" + SENTINEL + "\"\ninput-keys: not-a-list\n",
        };
        for (String block : bad) {
            assertThatThrownBy(() -> parse(withDescription(block)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(SENTINEL));
        }
    }

    @Test
    @DisplayName("the base-url host is available for the catalog's name check")
    void baseUrlHost() {
        assertThat(parse(HEAD + FIELDS).baseUrlHost()).isEqualTo("parcel.example.internal");
    }
}
