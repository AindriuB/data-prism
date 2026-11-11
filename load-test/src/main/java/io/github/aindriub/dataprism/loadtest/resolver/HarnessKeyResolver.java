package io.github.aindriub.dataprism.loadtest.resolver;

import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;

import java.util.List;

/**
 * The load-test harness's key resolver (task 263, D2): translates a {@code parcel-id} key into the one
 * {@code parcel-ref} key the synthetic sources of the second namespace answer, so the harness's own server issues
 * and follows translated links. It lives only in the harness's key-resolver jar, which the harness puts on its
 * server's {@code loader.path}; no packaged server or image carries it.
 */
public final class HarnessKeyResolver implements KeyResolver {

    public static final String FROM = "parcel-id";
    public static final String TO = "parcel-ref";
    static final String PREFIX = "ref-";

    @Override
    public String name() {
        return "harness-parcel-ref";
    }

    @Override
    public KeyNamespace from() {
        return new KeyNamespace(FROM);
    }

    @Override
    public KeyNamespace to() {
        return new KeyNamespace(TO);
    }

    @Override
    public Resolution resolve(String key, ResolutionContext context, int maxResults) {
        return new Resolution(List.of(PREFIX + key), false);
    }
}
