package io.github.aindriub.dataprism.reidentification;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.util.Objects;

public record ReidentificationRequest(
        String scopeId,
        PrivacyNamespace namespace,
        String syntheticValue,
        String purpose,
        String caseId) {

    public ReidentificationRequest {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(syntheticValue, "syntheticValue");
    }
}
