package io.github.aindriub.dataprism.connectors.rest;

import java.util.Map;
import java.util.Objects;

/**
 * The result of loading a configured-JSON-source file: the sources themselves,
 * plus the outbound TLS settings when a {@code tls:} block was configured.
 *
 * <p>Deliberately the same shape as {@link RestSourcesConfig} and deliberately
 * not the same type: the two configuration surfaces are loaded from different
 * root keys and never merged, so that this mode can never be reached by a typo
 * in a Java-first {@code sources:} block. See docs/configuration.md,
 * "Java-first now; generic JSON later".
 */
public record ConfiguredJsonSourcesConfig(Map<String, ConfiguredJsonSource> sources, TlsSettings tls) {

    public ConfiguredJsonSourcesConfig {
        Objects.requireNonNull(sources, "sources");
        sources = Map.copyOf(sources);
    }

    public boolean tlsConfigured() {
        return tls != null;
    }
}
