package io.github.aindriub.dataprism.core;

import java.util.List;
import java.util.Objects;

/**
 * The resolver for deployments where every source already keys on the same id.
 *
 * <p>The honest default. It does nothing, which is exactly right when the sources
 * genuinely share a key and exactly wrong when they do not — and an organisation
 * discovering that is better served by writing a resolver than by the platform
 * guessing at a match.
 */
public final class PassThroughIdentityResolver implements IdentityResolver {

    @Override
    public CanonicalId resolve(SourceRef ref) {
        return new CanonicalId(Objects.requireNonNull(ref, "ref").key());
    }

    @Override
    public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
        Objects.requireNonNull(id, "id");
        return sourceNames.stream().map(name -> new SourceRef(name, id.value())).toList();
    }
}
