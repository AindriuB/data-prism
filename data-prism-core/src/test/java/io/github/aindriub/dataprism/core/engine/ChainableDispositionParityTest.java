package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chainable is metadata only: setting it on an identifier changes no scrubbed tree, no disposition and no
 * emitted value (task 240). Runs the existing fixtures both ways.
 */
class ChainableDispositionParityTest {

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine engine(io.github.aindriub.dataprism.core.spi.FieldMetadataResolver r) {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE),
                        DataClassification.CONTACT, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT),
                        DataClassification.CONFIDENTIAL, PrivacyProfile.ClassificationRule.of(PrivacyAction.REMOVE)));
        return new JsonTreeScrubbingEngine(r, new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)),
                (subject, namespace, ctx) -> "synthetic-" + namespace);
    }

    private static Map<String, ModelDescriptor> flagged(Class<?> type, String... identifierFields) {
        Map<String, ModelDescriptor.FieldDescriptor> fields = new java.util.LinkedHashMap<>();
        for (String name : identifierFields) {
            fields.put(name, new ModelDescriptor.FieldDescriptor(List.of(), null, null, null, null, null,
                    true, "parcel-ref"));
        }
        return Map.of(type.getName(), new ModelDescriptor(type.getName(), null, null, null, fields));
    }

    private static void assertParity(Object source, Map<String, ModelDescriptor> descriptors) {
        var base = new DefaultFieldMetadataResolver();
        ScrubResult without = engine(base).scrub(source, context());
        ScrubResult with = engine(new DescriptorFieldMetadataResolver(base, descriptors)).scrub(source, context());

        assertThat(with.tree()).isEqualTo(without.tree());
        assertThat(with.dispositions()).isEqualTo(without.dispositions());
        assertThat(with.emitted()).isEqualTo(without.emitted());
    }

    @Test
    @DisplayName("Declared: identical with the flag on its identifier")
    void declared() {
        assertParity(new ScrubbingFixtures.Declared("s-1", "A Name", "a@example.invalid", "n", "OPEN"),
                flagged(ScrubbingFixtures.Declared.class, "subjectRef"));
    }

    @Test
    @DisplayName("TwoSubjects: identical with the flag on both identifiers")
    void twoSubjects() {
        assertParity(new ScrubbingFixtures.TwoSubjects("a-1", "g-1", "A Name", "G Name"),
                flagged(ScrubbingFixtures.TwoSubjects.class, "applicationRef", "guarantorRef"));
    }

    @Test
    @DisplayName("PlainClass: identical with the flag on its identifier")
    void plainClass() {
        assertParity(new ScrubbingFixtures.PlainClass("s-1", "A Name", "OPEN"),
                flagged(ScrubbingFixtures.PlainClass.class, "subjectRef"));
    }

    @Test
    @DisplayName("the flagged identifier is still removed, not emitted")
    void stillRemoved() {
        var base = new DefaultFieldMetadataResolver();
        var resolver = new DescriptorFieldMetadataResolver(base,
                flagged(ScrubbingFixtures.Declared.class, "subjectRef"));

        ScrubResult result = engine(resolver)
                .scrub(new ScrubbingFixtures.Declared("s-1", "A Name", "a@example.invalid", "n", "OPEN"), context());

        assertThat(result.dispositions()).containsEntry("/subjectRef", PrivacyAction.REMOVE);
    }
}
