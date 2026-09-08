package io.github.aindriub.dataprism.core;

/**
 * How long a set of synthetic identities stays consistent, and where it stops.
 *
 * <p>The scope is half the pseudonymisation key, so it is also the isolation
 * boundary: the same subject in two scopes is two different synthetic people, on
 * purpose. See docs/pack.md §58.
 */
public enum PrivacyScopeType {

    REQUEST,
    SESSION,
    INVESTIGATION,
    CASE
}
