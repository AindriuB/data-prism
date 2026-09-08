package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

/**
 * Builds the map keys, scope first.
 *
 * <p>Scope leads every key so that ending a scope is a prefix match rather than a
 * walk of the whole cluster, and so that no key from one investigation can be
 * reached from another by construction rather than by care.
 *
 * <p>Keys are plain strings on purpose. A record key would need a serialiser
 * registered on every member, and a mismatched serialiser across a rolling
 * upgrade is a way to lose a scope's identities mid-investigation.
 */
final class ScopeKeys {

    /**
     * Joins key parts. NUL because it cannot appear in a scope id, a namespace
     * name or an identifier, so no crafted value can make one key look like
     * another. Declared rather than written inline: an escape for it is invisible
     * in source and easily mangled by a tool that reformats the file.
     */
    private static final char SEPARATOR = 0;

    private ScopeKeys() {
    }

    static String identity(String scopeId, String subjectId, PrivacyNamespace namespace) {
        return scopeId + SEPARATOR + namespace.name() + SEPARATOR + subjectId;
    }

    static String reidentification(String scopeId, PrivacyNamespace namespace, String synthetic) {
        return scopeId + SEPARATOR + namespace.name() + SEPARATOR + synthetic;
    }

    static String budget(String scopeId, String subjectId) {
        return scopeId + SEPARATOR + subjectId;
    }

    static String scopePrefix(String scopeId) {
        return scopeId + SEPARATOR;
    }
}
