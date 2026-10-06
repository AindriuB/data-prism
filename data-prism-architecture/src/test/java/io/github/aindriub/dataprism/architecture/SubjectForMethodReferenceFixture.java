package io.github.aindriub.dataprism.architecture;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.hazelcast.ScopeIdentityIndex;

import java.util.Optional;

/** Negative fixture: reaches the reverse lookup through a method reference, outside the permitted package. */
final class SubjectForMethodReferenceFixture {

    interface Lookup {
        Optional<String> find(String scopeId, PrivacyNamespace namespace, String synthetic);
    }

    static Lookup viaReference(ScopeIdentityIndex index) {
        return index::subjectFor;
    }
}
