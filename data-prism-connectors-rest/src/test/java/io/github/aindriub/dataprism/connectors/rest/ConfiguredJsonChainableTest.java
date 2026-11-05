package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.engine.ChainableField;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

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

    private static ScrubResult scrub(String parcel, String state) throws IOException {
        ConfiguredJsonSource source = parse(fields(parcel, state));
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        StaticSecretKeyProvider keys =
                StaticSecretKeyProvider.of("task-249-chainable-parity-key-not-for-any-real-data-32bytes");
        ProfilePrivacyPolicyResolver policy;
        try (var in = ConfiguredJsonChainableTest.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            policy = new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(in));
        }
        var engine = new ConfiguredJsonScrubbingEngine((o, c) -> {
            throw new AssertionError("java-first delegate must not be reached");
        }, Map.of("parcel-service", source), policy, new HmacSyntheticGenerator(keys, vocabulary),
                new HmacValueTokenSource(keys));
        PrivacyContext context = new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT",
                "investigation", Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));
        ObjectNode body = (ObjectNode) JsonMapper.builder().build()
                .readTree("{\"parcel\":\"P-1\",\"state\":\"ACTIVE\"}");
        return engine.scrub(new ConfiguredJsonPayload("parcel-service", body), context);
    }

    @Test
    @DisplayName("scrubbed output and dispositions are equal with the chainable flag on and off")
    void chainableChangesNothingInTheScrub() throws IOException {
        ScrubResult on = scrub(ID + CHAIN, INERT);
        ScrubResult off = scrub(ID, INERT);

        assertThat(on.tree()).isEqualTo(off.tree());
        assertThat(on.dispositions()).isEqualTo(off.dispositions());
        assertThat(on.dispositions()).isNotEmpty();
    }
}
