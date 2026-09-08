package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

/**
 * Supplies the synthetic value for a subject in a namespace.
 *
 * <p>Declared here and implemented in the pseudonymisation module, so the
 * scrubbing engine depends on the contract rather than on the algorithm.
 *
 * <p>Implementations take no plaintext: the key is
 * {@code (scope, subject, namespace, algorithm version, key id)} and nothing
 * else. Deriving a pseudonym from the value it replaces would make it a
 * dictionary-reversible function of that value.
 */
public interface SyntheticValueSource {

    String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context);
}
