package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses and builds the calls of a scenario that sets a {@link Scenario.Mix}, and keeps, per worker, what the
 * earlier results offer to later calls: source labels, the next page link and the {@code _links} of the last
 * context. A call that needs something the worker does not yet hold is replaced by the call that gets it, so a
 * mix never sends an argument it made up, other than the deliberately invalid link.
 *
 * <p>Nothing a response carries is logged or recorded: labels and links stay in worker memory only.
 */
final class MixedCalls {

    /** The calls a mix can name, in the order their weights are laid out. */
    enum Op {
        ENTITY_CONTEXT("get_entity_context"),
        TARGETED_ENTITY_CONTEXT("get_entity_context"),
        LIST_SOURCES("list_sources"),
        DESCRIBE_SOURCE("describe_source"),
        FOLLOW("follow"),
        INVALID_LINK("follow"),
        FOLLOW_TRANSLATED("follow");

        private final String tool;

        Op(String tool) {
            this.tool = tool;
        }

        String tool() {
            return tool;
        }

        /** The operation's name in a result: lower camel case, one per call kind (not per MCP tool). */
        String key() {
            StringBuilder out = new StringBuilder();
            boolean upper = false;
            for (char c : name().toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    out.append(upper ? c : Character.toLowerCase(c));
                    upper = false;
                }
            }
            return out.toString();
        }
    }

    /** One worker's memory of what earlier results offered. */
    static final class State {
        private static final int MAX_HELD = 50;
        private List<String> labels = List.of();
        private List<String> links = List.of();
        private List<String> translatedLinks = List.of();
        private String pageNext;
    }

    /** The call to make: the operation that is actually sent (after any substitution) and its request. */
    record Planned(Op op, McpSchema.CallToolRequest request) {
        String tool() {
            return op.tool();
        }
    }

    private MixedCalls() { }

    /** The operation for call {@code index}: a fixed hash of the index over the weights, so a run is repeatable. */
    static Op choose(Scenario.Mix mix, long index) {
        int[] weights = {mix.entityContext(), mix.targetedEntityContext(), mix.listSources(), mix.describeSource(),
                mix.follow(), mix.invalidLink(), mix.followTranslated()};
        long pick = Math.floorMod(splitMix(index), (long) mix.total());
        for (int i = 0; i < weights.length; i++) {
            if (pick < weights[i]) {
                return Op.values()[i];
            }
            pick -= weights[i];
        }
        return Op.ENTITY_CONTEXT;
    }

    private static long splitMix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static Planned plan(Op wanted, State state, String entityType, String subject, long index) {
        Op op = wanted;
        // What the worker does not hold yet is fetched first.
        if ((op == Op.TARGETED_ENTITY_CONTEXT || op == Op.DESCRIBE_SOURCE) && state.labels.isEmpty()) {
            op = Op.LIST_SOURCES;
        }
        if (op == Op.FOLLOW && state.links.isEmpty()
                || op == Op.FOLLOW_TRANSLATED && state.translatedLinks.isEmpty()
                || op == Op.INVALID_LINK && state.links.isEmpty() && state.translatedLinks.isEmpty()) {
            op = Op.ENTITY_CONTEXT;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        String tool = op.tool();
        switch (op) {
            case ENTITY_CONTEXT -> {
                arguments.put("entityType", entityType);
                arguments.put("subjectId", subject);
            }
            case TARGETED_ENTITY_CONTEXT -> {
                arguments.put("entityType", entityType);
                arguments.put("subjectId", subject);
                List<String> chosen = new ArrayList<>();
                for (int k = 0; k < Math.min(3, state.labels.size()); k++) {
                    chosen.add(state.labels.get((int) ((index + k) % state.labels.size())));
                }
                arguments.put("targetSources", chosen);
            }
            case LIST_SOURCES -> {
                if (state.pageNext != null) {
                    arguments.put("page", state.pageNext);
                }
            }
            case DESCRIBE_SOURCE -> arguments.put("source", state.labels.get((int) (index % state.labels.size())));
            case FOLLOW -> {
                List<String> held = new ArrayList<>(state.links);
                // A link is followed once: a valid link used again is not what an agent does.
                arguments.put("link", held.remove(held.size() - 1));
                state.links = held;
            }
            case FOLLOW_TRANSLATED -> {
                List<String> held = new ArrayList<>(state.translatedLinks);
                arguments.put("link", held.remove(held.size() - 1));
                state.translatedLinks = held;
            }
            // Not consumed: the held link is still a valid one to follow later.
            case INVALID_LINK -> arguments.put("link", tamper(
                    state.links.isEmpty() ? state.translatedLinks.get(0) : state.links.get(0)));
        }
        return new Planned(op, new McpSchema.CallToolRequest(tool, arguments));
    }

    /**
     * A held link with its last byte changed. The version byte, the key id, the expiry and the structure are all
     * the server's own, so it reads the link's header, looks up the key and runs the AEAD, which fails on the
     * altered tag: the costly refusal path, not the cheap one a link of made-up bytes would take.
     */
    static String tamper(String link) {
        byte[] raw = java.util.Base64.getUrlDecoder().decode(link);
        raw[raw.length - 1] ^= 0x01;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Keeps what a successful result offers to later calls. A failed one clears what it would have replaced. */
    static void absorb(Planned planned, McpSchema.CallToolResult result, State state) {
        boolean ok = result != null && !Boolean.TRUE.equals(result.isError());
        switch (planned.op()) {
            case LIST_SOURCES -> {
                state.pageNext = null;
                JsonNode body = ok ? ResponseClassifier.body(result) : null;
                if (body == null) {
                    return;
                }
                List<String> labels = new ArrayList<>();
                body.path("sources").forEach(entry -> {
                    String label = entry.path("source").asString("");
                    if (!label.isEmpty() && labels.size() < State.MAX_HELD) {
                        labels.add(label);
                    }
                });
                if (!labels.isEmpty()) {
                    state.labels = List.copyOf(labels);
                }
                JsonNode page = body.path("page");
                if (!page.path("last").asBoolean(true)) {
                    String next = page.path("next").asString("");
                    state.pageNext = next.isEmpty() ? null : next;
                }
            }
            case ENTITY_CONTEXT, TARGETED_ENTITY_CONTEXT -> {
                JsonNode body = ok ? ResponseClassifier.body(result) : null;
                if (body == null) {
                    return;
                }
                List<String> links = new ArrayList<>();
                List<String> translated = new ArrayList<>();
                collectLinks(body.path("_links"), links, translated);
                body.path("sources").properties()
                        .forEach(source -> collectLinks(source.getValue().path("_links"), links, translated));
                if (!links.isEmpty()) {
                    state.links = List.copyOf(links);
                }
                if (!translated.isEmpty()) {
                    state.translatedLinks = List.copyOf(translated);
                }
            }
            default -> { }
        }
    }

    private static void collectLinks(JsonNode array, List<String> direct, List<String> translated) {
        array.forEach(node -> {
            String link = node.path("link").asString("");
            List<String> into = node.path("toNamespace").asString("").isEmpty() ? direct : translated;
            if (!link.isEmpty() && into.size() < State.MAX_HELD) {
                into.add(link);
            }
        });
    }
}
