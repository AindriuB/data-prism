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
import java.util.Map;
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

    @Test
    @DisplayName("a duplicate entity type, a duplicate input key and a malformed input key refuse with INVALID_SOURCE_DESCRIPTION")
    void catalogRuleFourIsOwnedByTheParser() {
        String[] bad = {
                "entity-types: [PARCEL, PARCEL]\n",
                "input-keys: [parcel-ref, parcel-ref]\n",
                "input-keys: [\"Bad Key\"]\n",
                "entity-types: [\"bad type\"]\n",
        };
        for (String block : bad) {
            assertThatThrownBy(() -> parse(withDescription(block)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("INVALID_SOURCE_DESCRIPTION:")
                    .hasMessageContaining("parcel-service")
                    .hasMessageNotContaining("Bad Key")
                    .hasMessageNotContaining("bad type");
        }
    }

    @Test
    @DisplayName("a valid namespace-types reaches description().namespaceTypes()")
    void namespaceTypesReachTheDescription() {
        ConfiguredJsonSource source = parse(withDescription("""
                entity-types: [DEPOT, PARCEL]
                input-keys: [depot-ref, parcel-ref]
                namespace-types:
                  parcel-ref: PARCEL
                  depot-ref: DEPOT
                """));

        assertThat(new ConfiguredJsonDataSourceAdapter(source, RestClient.create()).description().namespaceTypes())
                .isEqualTo(Map.of(new KeyNamespace("parcel-ref"), "PARCEL", new KeyNamespace("depot-ref"), "DEPOT"));
    }

    @Test
    @DisplayName("a namespace-types that is not a mapping refuses with INVALID_CONFIG_SHAPE")
    void namespaceTypesMustBeAMapping() {
        for (String block : new String[] {"namespace-types: [parcel-ref]\n", "namespace-types: " + SENTINEL + "\n"}) {
            assertThatThrownBy(() -> parse(withDescription("entity-types: [PARCEL]\ninput-keys: [parcel-ref]\n" + block)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("INVALID_CONFIG_SHAPE")
                    .hasMessageNotContaining(SENTINEL);
        }
    }

    @Test
    @DisplayName("a key outside input-keys, a type outside entity-types and a key that is not a namespace refuse with INVALID_SOURCE_DESCRIPTION, never the value")
    void namespaceTypesRulesRefuse() {
        String head = "entity-types: [DEPOT, PARCEL]\ninput-keys: [parcel-ref]\nnamespace-types:\n  ";
        String[] bad = {
                head + "other-ref: PARCEL\n",
                head + "parcel-ref: " + SENTINEL + "\n",
                head + "\"Bad Key " + SENTINEL + "\": PARCEL\n",
        };
        for (String block : bad) {
            assertThatThrownBy(() -> parse(withDescription(block)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("INVALID_SOURCE_DESCRIPTION:")
                    .hasMessageContaining("parcel-service")
                    .hasMessageNotContaining(SENTINEL)
                    .hasMessageNotContaining("other-ref");
        }
    }
}
