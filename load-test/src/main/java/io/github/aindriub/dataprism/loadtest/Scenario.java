package io.github.aindriub.dataprism.loadtest;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One load-test scenario, read from a JSON file in {@code scenarios/}.
 *
 * <p>The format is the contract for the later 500-source and soak scenarios too: {@code load} takes either a
 * fixed {@code calls} count, a {@code durationSeconds}, or both (whichever is reached first), and a
 * {@code ratePerSecond} of zero means "as fast as the workers can go".
 *
 * @param name          the scenario's name; it is the result file's prefix and matches the scenario file's name
 * @param sources       how many synthetic sources to generate and configure
 * @param sourceLatency the per-source response latency distribution, uniform over the stated band
 * @param load          how the driver calls {@code get_entity_context}
 * @param limits        the {@code dataprism.limits.*} the server is started with; omitted keys take the
 *                      framework default, except {@code max-sources}, which takes {@code sources}
 * @param seed          seeds the sources' latency sampling, so a run's source behaviour is reproducible
 */
public record Scenario(String name, int sources, Latency sourceLatency, Load load, Limits limits, long seed) {

    /** Uniform latency band, in milliseconds, each source responds within. */
    public record Latency(int minMs, int maxMs) { }

    /**
     * @param concurrency    simultaneous callers, each with its own MCP session
     * @param calls          measured calls to make; 0 means run for {@code durationSeconds}
     * @param warmupCalls    calls made first and not measured
     * @param ratePerSecond  target start rate across all callers; 0 means unpaced
     * @param durationSeconds measured run length; 0 means run for {@code calls}
     * @param callTimeoutSeconds how long the client waits for one call before counting a transport failure
     */
    public record Load(int concurrency, int calls, int warmupCalls, int ratePerSecond, int durationSeconds,
                       int callTimeoutSeconds) {
        public Load {
            callTimeoutSeconds = callTimeoutSeconds <= 0 ? 30 : callTimeoutSeconds;
        }
    }

    /**
     * The six {@code dataprism.limits.*} keys. Every field except {@code maxSources} is explicit once built;
     * {@code maxSources} stays null until the enclosing {@link Scenario} resolves it to the source count.
     */
    public record Limits(Integer maxSources, Integer maxRecords, String maxResponseSize, String perSourceTimeout,
                         Integer maxConcurrency, Integer scopeReadBudget) {

        public Limits {
            // The framework defaults, docs/configuration.md "dataprism.limits". Stated explicitly so the
            // result file always records every effective value rather than "whatever the server defaulted".
            maxRecords = maxRecords == null ? Integer.valueOf(500) : maxRecords;
            maxResponseSize = maxResponseSize == null ? "512KB" : maxResponseSize;
            perSourceTimeout = perSourceTimeout == null ? "3s" : perSourceTimeout;
            maxConcurrency = maxConcurrency == null ? Integer.valueOf(4) : maxConcurrency;
            scopeReadBudget = scopeReadBudget == null ? Integer.valueOf(100) : scopeReadBudget;
        }

        /** Framework defaults, with {@code max-sources} large enough to call every generated source. */
        public static Limits defaults(int sources) {
            return new Limits(sources, null, null, null, null, null);
        }

        /** The server properties these limits become, in {@code dataprism.limits.*} form. */
        public Map<String, String> asProperties() {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("dataprism.limits.max-sources", String.valueOf(maxSources));
            properties.put("dataprism.limits.max-records", String.valueOf(maxRecords));
            properties.put("dataprism.limits.max-response-size", maxResponseSize);
            properties.put("dataprism.limits.per-source-timeout", perSourceTimeout);
            properties.put("dataprism.limits.max-concurrency", String.valueOf(maxConcurrency));
            properties.put("dataprism.limits.scope-read-budget", String.valueOf(scopeReadBudget));
            return properties;
        }
    }

    public Scenario {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scenario needs a name");
        }
        if (sources <= 0) {
            throw new IllegalArgumentException("scenario needs at least one source");
        }
        if (sourceLatency == null) {
            sourceLatency = new Latency(5, 25);
        }
        if (load == null || load.concurrency() <= 0) {
            throw new IllegalArgumentException("scenario load needs a concurrency of at least 1");
        }
        if (load.calls() <= 0 && load.durationSeconds() <= 0) {
            throw new IllegalArgumentException("scenario load needs calls or durationSeconds");
        }
        if (limits == null) {
            limits = Limits.defaults(sources);
        } else if (limits.maxSources() == null) {
            // Unstated means "call every source": a scenario of N sources that can call only the
            // framework's default 8 would silently measure a skipped fan-out. A scenario that wants
            // that states max-sources itself.
            limits = new Limits(sources, limits.maxRecords(), limits.maxResponseSize(), limits.perSourceTimeout(),
                    limits.maxConcurrency(), limits.scopeReadBudget());
        }
    }

    public static Scenario load(Path file) throws IOException {
        JsonMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        try {
            return mapper.readValue(Files.readString(file), Scenario.class);
        } catch (tools.jackson.databind.exc.ValueInstantiationException invalid) {
            // The record's own validation, wrapped by the mapper: surface it as what it is.
            if (invalid.getCause() instanceof IllegalArgumentException cause) {
                throw cause;
            }
            throw invalid;
        }
    }
}
