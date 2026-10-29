package io.github.aindriub.dataprism.core.spi;

import io.github.aindriub.dataprism.core.spi.IdentityResolver.SourceRef;

import java.util.List;
import java.util.Objects;

/**
 * Exposes an existing {@link IdentityResolver} as a {@link KeyResolver} for one declared pair of sources, so an
 * application with a mapping does not write a second one. Opt-in per pair: nothing is generated automatically.
 *
 * <p>{@code resolve(key)} is {@code expand(resolve(new SourceRef(S1, key)), List.of(S2))}, returning the keys of
 * {@code S2}.
 */
public final class IdentityResolverKeyResolvers {

    private IdentityResolverKeyResolvers() {}

    public static KeyResolver of(IdentityResolver identity, String name, String firstSource, KeyNamespace from,
                                 String secondSource, KeyNamespace to) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(firstSource, "firstSource");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(secondSource, "secondSource");
        Objects.requireNonNull(to, "to");
        return new KeyResolver() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public KeyNamespace from() {
                return from;
            }

            @Override
            public KeyNamespace to() {
                return to;
            }

            @Override
            public Resolution resolve(String key, ResolutionContext context, int maxResults) {
                List<SourceRef> refs;
                try {
                    refs = identity.expand(identity.resolve(new SourceRef(firstSource, key)), List.of(secondSource));
                } catch (RuntimeException e) {
                    // Deliberately drops the cause: it may carry the key.
                    throw new KeyResolutionException(KeyResolutionException.Reason.UNAVAILABLE);
                }
                List<String> keys = refs.stream()
                        .filter(ref -> ref.sourceName().equals(secondSource))
                        .map(SourceRef::key)
                        .toList();
                if (keys.size() > maxResults) {
                    return new Resolution(keys.subList(0, Math.max(0, maxResults)), true);
                }
                return new Resolution(keys, false);
            }
        };
    }
}
