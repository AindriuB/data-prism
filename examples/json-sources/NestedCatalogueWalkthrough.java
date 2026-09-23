// Complete, runnable companion to docs/protect-your-own-api.md, "A nested
// response". Package-private itself, exactly the way
// ConfiguredJsonNestedCatalogueScrubbingTest in this module is, because
// ConfiguredJsonPayload is package-private: this file lives beside the
// examples it loads, not inside data-prism-connectors-rest's own source
// tree, so it is compiled and run against that module's already-built
// classes rather than added to it. Loads
// examples/json-sources/customer-api-nested.yaml with the real
// ConfiguredJsonSources, wires the real ConfiguredJsonScrubbingEngine
// exactly the way that test does, and prints the resolved nested catalogue,
// the scrubbed fixture-shaped response, and the NESTED_LEAF_NOT_SCALAR
// refusal for a stale wire shape.
//
// Run from the repository root, after `mvn -q package -pl data-prism-core,
// data-prism-connectors-rest -am -DskipTests` has built both modules' classes:
//
//   javac -cp "data-prism-core/target/classes:data-prism-connectors-rest/target/classes:$(cd data-prism-connectors-rest && mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" \
//       -d /tmp/nested-catalogue-walkthrough examples/json-sources/NestedCatalogueWalkthrough.java
//   java -cp "/tmp/nested-catalogue-walkthrough:data-prism-core/target/classes:data-prism-connectors-rest/target/classes:$(cd data-prism-connectors-rest && mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" \
//       io.github.aindriub.dataprism.connectors.rest.NestedCatalogueWalkthrough
package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;

import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

public final class NestedCatalogueWalkthrough {

    public static void main(String[] args) throws Exception {
        ConfiguredJsonSourcesConfig config;
        try (var in = new FileInputStream("examples/json-sources/customer-api-nested.yaml")) {
            config = ConfiguredJsonSources.fromYaml(in);
        }
        ConfiguredJsonSource source = config.sources().get("customer-api-with-address");

        PrivacyPolicyResolver policy;
        try (InputStream profiles = NestedCatalogueWalkthrough.class
                .getResourceAsStream("/privacy-profiles-default.yaml")) {
            policy = new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(profiles));
        }
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        StaticSecretKeyProvider keys =
                StaticSecretKeyProvider.of("nested-catalogue-walkthrough-key-not-for-any-real-data-32b");
        SyntheticValueSource synthetics = new HmacSyntheticGenerator(keys, vocabulary);
        ValueTokenSource tokens = new HmacValueTokenSource(keys);
        ScrubbingEngine unreachableJavaFirst = (payload, ctx) -> {
            throw new AssertionError("java-first delegate should never be reached in this walkthrough");
        };
        ConfiguredJsonScrubbingEngine engine = new ConfiguredJsonScrubbingEngine(unreachableJavaFirst,
                Map.of(source.transport().name(), source), policy, synthetics, tokens);

        // Map.of()/Map.copyOf() randomise iteration order per JVM run (JEP
        // 269's immutable collections deliberately do this); sort every
        // printed key set so this program's output is the same on every run,
        // not just this one.
        System.out.println("sources: " + new java.util.TreeSet<>(config.sources().keySet()));
        System.out.println("root fields: " + new java.util.TreeSet<>(source.fields().keySet()));
        System.out.println("nested catalogues: " + new java.util.TreeSet<>(source.nestedCatalogues().keySet()));
        for (String catalogueName : new java.util.TreeSet<>(source.nestedCatalogues().keySet())) {
            Map<String, FieldMetadata> catalogueFields = source.nestedCatalogues().get(catalogueName);
            for (String fieldName : new java.util.TreeSet<>(catalogueFields.keySet())) {
                FieldMetadata metadata = catalogueFields.get(fieldName);
                System.out.println("  " + catalogueName + "." + fieldName
                        + " -> classifications=" + metadata.classifications()
                        + " namespace=" + metadata.namespace()
                        + " action=" + metadata.suggestedAction()
                        + " nonSensitiveReason=" + metadata.nonSensitiveReason());
            }
        }

        PrivacyContext context = new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT",
                "investigation", Instant.now(),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));

        ObjectMapper json = new ObjectMapper();
        ObjectNode body = (ObjectNode) json.readTree("""
                {"customerId":"1001","customerName":"Fixture Person One","email":"fixture.person.one@example.invalid",\
"status":"ACTIVE","address":{"line1":"123 Main St","postcode":"90210"}}""");
        ScrubResult result = engine.scrub(
                new ConfiguredJsonPayload("customer-api-with-address", body), context);
        System.out.println(result.tree());

        ObjectNode staleShapeBody = (ObjectNode) json.readTree("""
                {"customerId":"1001","customerName":"Fixture Person One","email":"fixture.person.one@example.invalid",\
"status":"ACTIVE","address":{"line1":"123 Main St","postcode":{"unexpected":"structure"}}}""");
        try {
            engine.scrub(new ConfiguredJsonPayload("customer-api-with-address", staleShapeBody), context);
            System.out.println("expected a refusal, got none");
        } catch (PrivacyRefusedException e) {
            System.out.println("REFUSED: " + e.getMessage());
        }
    }
}
