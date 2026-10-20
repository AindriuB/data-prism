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
 * @param slowSources   how many of the {@code sources} (the last ones) answer within {@code slowLatency} instead
 *                      of {@code sourceLatency}; 0 means none. Lets a scenario time out some sources and not all
 *                      (a call in which every source fails is refused, and reports no per-source statuses)
 * @param slowLatency   the band the slow sources answer within; required when {@code slowSources} is above 0
 */
public record Scenario(String name, int sources, Latency sourceLatency, Load load, Limits limits, long seed,
                       int slowSources, Latency slowLatency) {

    /** Uniform latency band, in milliseconds, each source responds within. */
    public record Latency(int minMs, int maxMs) { }

    /**
     * @param concurrency    simultaneous callers, each with its own MCP session
     * @param calls          measured calls to make; 0 means run for {@code durationSeconds}
     * @param warmupCalls    calls made first and not measured
     * @param ratePerSecond  target start rate across all callers; 0 means unpaced
     * @param durationSeconds measured run length; 0 means run for {@code calls}
     * @param callTimeoutSeconds how long the client waits for one call before counting a transport failure
     * @param subjectPool    0 asks for a different subject on every call; above 0 the calls cycle through that many
     *                       subjects, so the per-subject read budget is reached
     */
    public record Load(int concurrency, int calls, int warmupCalls, int ratePerSecond, int durationSeconds,
                       int callTimeoutSeconds, int subjectPool) {
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

    /** The longest run: past it the credential rotation has not been shown to hold. */
    public static final int MAX_DURATION_SECONDS = 24 * 60 * 60;

    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*");

    public Scenario {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "scenario name must be lower-case letters, digits and hyphens (it names the result file): " + name);
        }
        if (sources <= 0) {
            throw new IllegalArgumentException("scenario needs at least one source");
        }
        if (sourceLatency == null) {
            sourceLatency = new Latency(5, 25);
        }
        if (slowSources < 0 || slowSources > sources) {
            throw new IllegalArgumentException("scenario slowSources must be between 0 and sources");
        }
        if (slowSources > 0 && slowLatency == null) {
            throw new IllegalArgumentException("scenario slowSources needs a slowLatency band");
        }
        if (load == null || load.concurrency() <= 0) {
            throw new IllegalArgumentException("scenario load needs a concurrency of at least 1");
        }
        for (var field : Map.of("calls", load.calls(), "warmupCalls", load.warmupCalls(),
                "ratePerSecond", load.ratePerSecond(), "durationSeconds", load.durationSeconds(),
                "subjectPool", load.subjectPool()).entrySet()) {
            if (field.getValue() < 0) {
                throw new IllegalArgumentException("scenario load." + field.getKey() + " must not be negative");
            }
        }
        if (load.durationSeconds() > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("scenario load.durationSeconds must not exceed 24 hours ("
                    + MAX_DURATION_SECONDS + " s)");
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
            Scenario scenario = mapper.readValue(Files.readString(file), Scenario.class);
            String expected = file.getFileName().toString().replaceFirst("\\.json$", "");
            if (!scenario.name().equals(expected)) {
                throw new IllegalArgumentException("scenario name \"" + scenario.name()
                        + "\" must match its file name " + file.getFileName());
            }
            return scenario;
        } catch (tools.jackson.databind.exc.ValueInstantiationException invalid) {
            // The record's own validation, wrapped by the mapper: surface it as what it is.
            if (invalid.getCause() instanceof IllegalArgumentException cause) {
                throw cause;
            }
            throw invalid;
        }
    }
}
