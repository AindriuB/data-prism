package io.github.aindriub.dataprism.loadtest;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a scenario's synthetic sources and the {@code json-sources:} catalogue that configures the
 * server to call them.
 *
 * <p>The invented domain is parcels moving between depots. Every value the sources serve is obviously
 * synthetic: the email domain is {@code example.invalid}, names are numbered, and nothing identifier-shaped
 * (no bank, card or national-id format) is generated at all.
 */
final class SourceGenerator {

    static final String ENTITY_TYPE = "PARCEL";

    /** The key namespace of a parcel's id, for the sources that link to each other. */
    static final String KEY_NAMESPACE = "parcel-id";

    private SourceGenerator() { }

    static List<SourceDefinition> generate(Scenario scenario) {
        if (scenario.sources() <= 0) {
            throw new IllegalArgumentException("a scenario needs at least one source");
        }
        Scenario.Latency band = scenario.sourceLatency();
        if (band.minMs() < 0 || band.maxMs() < band.minMs()) {
            throw new IllegalArgumentException("source latency band must satisfy 0 <= minMs <= maxMs");
        }
        Scenario.Latency slow = scenario.slowLatency();
        if (scenario.slowSources() > 0 && (slow.minMs() < 0 || slow.maxMs() < slow.minMs())) {
            throw new IllegalArgumentException("slow latency band must satisfy 0 <= minMs <= maxMs");
        }
        List<SourceDefinition> sources = new ArrayList<>(scenario.sources());
        for (int i = 1; i <= scenario.sources(); i++) {
            String name = String.format("depot-feed-%04d", i);
            Scenario.Latency own = i > scenario.sources() - scenario.slowSources() ? slow : band;
            sources.add(new SourceDefinition(name, "/" + name + "/parcels/{subject}", own.minMs(), own.maxMs()));
        }
        return List.copyOf(sources);
    }

    /** The YAML {@code dataprism.json-sources.config-location} names; every field is classified. */
    static String catalogueYaml(List<SourceDefinition> sources, String baseUrl) {
        return catalogueYaml(sources, baseUrl, false);
    }

    /**
     * As above; with {@code linkable} every source accepts and offers the {@code parcel-id} key namespace through
     * its {@code id} field, so a context result carries {@code _links} (while links are enabled) to the others.
     */
    static String catalogueYaml(List<SourceDefinition> sources, String baseUrl, boolean linkable) {
        StringBuilder yaml = new StringBuilder("json-sources:\n");
        for (SourceDefinition source : sources) {
            yaml.append("  ").append(source.name()).append(":\n")
                    .append("    base-url: ").append(baseUrl).append('\n')
                    .append("    path: ").append(source.path()).append('\n')
                    .append("    timeout: PT10S\n")
                    .append("    model-version: parcel-v1\n")
                    .append("    subject-json-path: id\n")
                    .append(linkable ? "    description:\n      entity-types: [" + ENTITY_TYPE + "]\n"
                            + "      input-keys: [" + KEY_NAMESPACE + "]\n" : "")
                    .append("    fields:\n")
                    .append("      id:\n")
                    .append("        identifier: true\n")
                    .append(linkable ? "        chainable: true\n        keyNamespace: " + KEY_NAMESPACE + "\n" : "")
                    .append("      status:\n")
                    .append("        nonSensitive: \"enumerated parcel lifecycle state\"\n")
                    .append("      depot:\n")
                    .append("        nonSensitive: \"synthetic depot code\"\n")
                    .append("      recipientName:\n")
                    .append("        classifications: [PII]\n")
                    .append("        namespace: PERSON_NAME\n")
                    .append("        action: SYNTHESIZE\n")
                    .append("      contactEmail:\n")
                    .append("        classifications: [PII]\n")
                    .append("        namespace: EMAIL\n")
                    .append("        action: SYNTHESIZE\n");
        }
        return yaml.toString();
    }
}
