package io.github.aindriub.dataprism.reidentification;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.util.Optional;

/** The reverse lookup. In production, {@code ScopeIdentityIndex::subjectFor}. */
@FunctionalInterface
public interface SubjectLookup {

    Optional<String> subjectFor(String scopeId, PrivacyNamespace namespace, String synthetic);
}
