package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SubjectIdentifier;

/**
 * Fixture types for the engine tests.
 *
 * <p>Named for their shape rather than any business meaning: core has no domain,
 * and a fixture called {@code Customer} here would be the first crack in that.
 *
 * <p>{@link Declared} and {@link Undeclared} are identical but for one
 * annotation. That pairing is the point — it proves the fail-closed test is
 * measuring the annotation rather than passing for some unrelated reason.
 */
final class ScrubbingFixtures {

    private ScrubbingFixtures() {
    }

    @LlmExposedModel
    record Declared(
            @InternalIdentifier String subjectRef,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String fullName,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT)
            String contact,
            @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                    suggestedAction = PrivacyAction.REMOVE)
            String note,
            @NonSensitive(reason = "Enumerated state, no free text")
            String state) {
    }

    /** The same record with the classification on {@code contact} removed. */
    @LlmExposedModel
    record Undeclared(
            @InternalIdentifier String subjectRef,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String fullName,
            String contact,
            @NonSensitive(reason = "Enumerated state, no free text")
            String state) {
    }

    /** Two subjects in one record: the case a single identifier gets wrong. */
    @LlmExposedModel
    record TwoSubjects(
            @InternalIdentifier String applicationRef,
            @SubjectIdentifier(role = "guarantor")
            String guarantorRef,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String applicantName,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE,
                    subject = "guarantorRef")
            String guarantorName) {
    }

    /** Not approved for exposure. */
    record NotExposed(@InternalIdentifier String subjectRef, String anything) {
    }

    /** A plain class rather than a record, annotated on its fields. */
    @LlmExposedModel
    static final class PlainClass {

        @InternalIdentifier
        private final String subjectRef;

        @SensitiveData(classifications = DataClassification.PII,
                namespace = PrivacyNamespace.PERSON_NAME,
                suggestedAction = PrivacyAction.SYNTHESIZE)
        private final String fullName;

        @NonSensitive(reason = "Enumerated state, no free text")
        private final String state;

        PlainClass(String subjectRef, String fullName, String state) {
            this.subjectRef = subjectRef;
            this.fullName = fullName;
            this.state = state;
        }

        public String getSubjectRef() {
            return subjectRef;
        }

        public String getFullName() {
            return fullName;
        }

        public String getState() {
            return state;
        }
    }
}
