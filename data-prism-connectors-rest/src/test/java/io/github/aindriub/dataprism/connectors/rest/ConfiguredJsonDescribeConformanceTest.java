package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every path a scrubbed configured-source response contains is described, with the protection it was
 * scrubbed under (task 249).
 */
class ConfiguredJsonDescribeConformanceTest {

    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-249-describe-test-key-not-for-any-real-data-32bytes");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Vocabulary VOCABULARY = VocabularyRegistry.withBuiltIns().resolve("und");

    private static final String PARCELS = ConfiguredJsonDescriptionTest.HEAD + """
                nested-catalogues:
                  stop:
                    place:
                      nonSensitive: "Depot name, no free text"
                    contact:
                      classifications: [CONTACT]
                      namespace: EMAIL
                      action: REDACT
                    holder:
                      classifications: [PII]
                      namespace: PERSON_NAME
                      action: SYNTHESIZE
                fields:
                  parcel:
                    identifier: true
                    chainable: true
                    keyNamespace: parcel-ref
                  recipientName:
                    classifications: [PII]
                    namespace: PERSON_NAME
                    action: SYNTHESIZE
                  note:
                    classifications: [PII]
                    action: REMOVE
                  state:
                    nonSensitive: "Enumerated lifecycle state"
                  stops:
                    nested: stop
            """;

    private static final String PARCEL_BODY = """
            {"parcel":"P-1","recipientName":"Alice Example","note":"call first","state":"IN_TRANSIT",
             "stops":[{"place":"North","contact":"a@e.example","holder":"Bob Example"}]}""";

    private static final String SHARED = ConfiguredJsonDescriptionTest.HEAD + """
                nested-catalogues:
                  address:
                    line:
                      classifications: [PII]
                      namespace: ADDRESS
                      action: REDACT
                    city:
                      nonSensitive: "Municipality name, no free text"
                fields:
                  parcel:
                    identifier: true
                  billing:
                    nested: address
                  shipping:
                    nested: address
            """;

    private static final String SHARED_BODY = """
            {"parcel":"P-1","billing":{"line":"1 Main St","city":"North"},
             "shipping":[{"line":"2 Side St","city":"South"}]}""";

    private static final String CUSTOMER_BODY =
            "{\"id\":\"C1\",\"name\":\"Alice Example\",\"email\":\"a@e.example\",\"status\":\"ACTIVE\"}";

    private static PrivacyPolicyResolver policy() {
        try (InputStream input = ConfiguredJsonDescribeConformanceTest.class
                .getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrivacyContext context(String profile) {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, profile, "investigation",
                Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(VOCABULARY.id()));
    }

    private static ConfiguredJsonScrubbingEngine engine(Map<String, ConfiguredJsonSource> sources) {
        return new ConfiguredJsonScrubbingEngine(
                (source, context) -> {
                    throw new AssertionError("not reached");
                },
                sources, policy(), new HmacSyntheticGenerator(KEYS, VOCABULARY), new HmacValueTokenSource(KEYS));
    }

    private static ConfiguredJsonSource customerApi() throws IOException {
        try (InputStream in = ConfiguredJsonDescribeConformanceTest.class
                .getResourceAsStream("/task20-json-sources.yaml")) {
            return ConfiguredJsonSources.fromYaml(in).sources().get("customer-api");
        }
    }

    private static Protection protection(PrivacyAction action) {
        return switch (action) {
            case PASS_THROUGH -> Protection.VERBATIM;
            case SYNTHESIZE -> Protection.SYNTHETIC;
            case TOKENIZE, HASH -> Protection.TOKEN;
            case REDACT -> Protection.MARKER;
            case GENERALIZE -> Protection.BUCKET;
            case REMOVE -> Protection.ABSENT;
        };
    }

    /** {@code /stops/*}{@code /place} as {@code stops[].place}. */
    private static String path(String pointer) {
        StringBuilder out = new StringBuilder();
        for (String segment : pointer.substring(1).split("/")) {
            if (segment.equals("*")) {
                out.append("[]");
            } else {
                out.append(out.isEmpty() ? "" : ".").append(segment.replace("~1", "/").replace("~0", "~"));
            }
        }
        return out.toString();
    }

    private static void leaves(JsonNode node, String path, java.util.List<String> into) {
        if (node.isObject()) {
            node.properties().forEach(e -> leaves(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(),
                    into));
        } else if (node.isArray()) {
            node.forEach(element -> leaves(element, path + "[]", into));
        } else {
            into.add(path);
        }
    }

    private static void assertConforms(ConfiguredJsonScrubbingEngine engine, ConfiguredJsonSource source,
                                       String body, String profile) throws IOException {
        PrivacyContext context = context(profile);
        ScrubResult scrubbed = engine.scrub(
                new ConfiguredJsonPayload(source.transport().name(), (ObjectNode) JSON.readTree(body)), context);
        ModelDescription description =
                engine.describe(source.transport().name(), context, true).orElseThrow();
        Map<String, FieldProtection> byPath = new HashMap<>();
        description.fields().forEach(f -> byPath.put(f.path(), f));

        Map<String, PrivacyAction> actions = new HashMap<>();
        scrubbed.dispositions().forEach((pointer, action) -> actions.put(path(pointer), action));

        java.util.List<String> emitted = new java.util.ArrayList<>();
        leaves(scrubbed.tree(), "", emitted);
        assertThat(emitted).isNotEmpty();
        for (String leaf : emitted) {
            assertThat(byPath).as("path %s under %s", leaf, profile).containsKey(leaf);
            assertThat(byPath.get(leaf).protection()).as("protection of %s under %s", leaf, profile)
                    .isEqualTo(protection(actions.get(leaf)));
        }
        // A field the scrubber removes is not described, unless it is chainable.
        actions.forEach((path, action) -> {
            if (action == PrivacyAction.REMOVE && !path.equals("parcel")) {
                assertThat(byPath).as("removed %s", path).doesNotContainKey(path);
            }
        });
    }

    @Test
    @DisplayName("a catalogue with nested entries is described as the scrubber protects it, under both profiles")
    void nestedSourceConforms() throws IOException {
        ConfiguredJsonSource source = ConfiguredJsonDescriptionTest.parse(PARCELS);
        ConfiguredJsonScrubbingEngine engine = engine(Map.of("parcel-service", source));
        for (String profile : new String[] {"DEFAULT", "STRICT"}) {
            assertConforms(engine, source, PARCEL_BODY, profile);
        }
    }

    @Test
    @DisplayName("the existing fixture is described as the scrubber protects it, under both profiles")
    void existingFixtureConforms() throws IOException {
        ConfiguredJsonSource source = customerApi();
        ConfiguredJsonScrubbingEngine engine = engine(Map.of("customer-api", source));
        for (String profile : new String[] {"DEFAULT", "STRICT"}) {
            assertConforms(engine, source, CUSTOMER_BODY, profile);
        }
    }

    @Test
    @DisplayName("the chainable identifier is listed ABSENT with its key namespace, only while links are enabled")
    void chainableIdentifier() {
        ConfiguredJsonSource source = ConfiguredJsonDescriptionTest.parse(PARCELS);
        ConfiguredJsonScrubbingEngine engine = engine(Map.of("parcel-service", source));

        ModelDescription linked = engine.describe("parcel-service", context("DEFAULT"), true).orElseThrow();
        assertThat(linked.fields()).filteredOn(f -> f.keyNamespace() != null)
                .containsExactly(new FieldProtection("parcel", Protection.ABSENT, null, "parcel-ref"));

        ModelDescription unlinked = engine.describe("parcel-service", context("DEFAULT"), false).orElseThrow();
        assertThat(unlinked.fields()).noneMatch(f -> f.keyNamespace() != null);
        assertThat(unlinked.fields()).extracting(FieldProtection::path).doesNotContain("parcel");
    }

    @Test
    @DisplayName("a nested catalogue is described as an object and as a list of objects")
    void nestedPaths() {
        ConfiguredJsonSource source = ConfiguredJsonDescriptionTest.parse(PARCELS);
        ModelDescription description = engine(Map.of("parcel-service", source))
                .describe("parcel-service", context("DEFAULT"), false).orElseThrow();
        assertThat(description.fields()).extracting(FieldProtection::path)
                .contains("stops.place", "stops[].place", "stops.contact", "stops[].holder", "state", "state[]")
                .doesNotContain("stops", "stops[]", "note", "note[]");
    }

    @Test
    @DisplayName("an unknown source, or the shared payload class, cannot be described")
    void unknown() {
        ConfiguredJsonSource source = ConfiguredJsonDescriptionTest.parse(PARCELS);
        ConfiguredJsonScrubbingEngine engine = engine(Map.of("parcel-service", source));
        assertThat(engine.describe("elsewhere", context("DEFAULT"), true)).isEqualTo(Optional.empty());
        assertThat(engine.describe(ConfiguredJsonPayload.class, context("DEFAULT"), true)).isEmpty();
    }

    @Test
    @DisplayName("two fields sharing one nested catalogue are both described, and conform to the scrubber")
    void sharedNestedCatalogue() throws IOException {
        ConfiguredJsonSource source = ConfiguredJsonDescriptionTest.parse(SHARED);
        ConfiguredJsonScrubbingEngine engine = engine(Map.of("parcel-service", source));
        ModelDescription description = engine.describe("parcel-service", context("DEFAULT"), false).orElseThrow();
        assertThat(description.fields()).extracting(FieldProtection::path)
                .contains("billing.line", "billing[].line", "billing.city",
                        "shipping.line", "shipping[].line", "shipping.city");
        for (String profile : new String[] {"DEFAULT", "STRICT"}) {
            assertConforms(engine, source, SHARED_BODY, profile);
        }
    }
}
