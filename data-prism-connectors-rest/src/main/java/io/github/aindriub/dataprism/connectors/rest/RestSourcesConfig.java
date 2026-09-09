package io.github.aindriub.dataprism.connectors.rest;

import java.util.Map;
import java.util.Objects;

/**
 * The result of loading {@code sources.yaml}: the sources themselves, plus the
 * outbound TLS settings when a {@code tls:} block was configured.
 *
 * @param sources the configured sources, keyed by name
 * @param tls     the parsed TLS settings, or {@code null} when no {@code tls:}
 *                block was present — in which case every source in {@link
 *                #sources()} permits {@code http}
 */
public record RestSourcesConfig(Map<String, RestSource> sources, TlsSettings tls) {

    public RestSourcesConfig {
        Objects.requireNonNull(sources, "sources");
        sources = Map.copyOf(sources);
    }

    public boolean tlsConfigured() {
        return tls != null;
    }
}
