package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.spi.ChainableField;
import io.github.aindriub.dataprism.core.engine.ChainableFields;
import io.github.aindriub.dataprism.core.spi.ChainableView;
import io.github.aindriub.dataprism.core.engine.SourceTree;
import io.github.aindriub.dataprism.core.link.FollowPayload;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.link.SealResult;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.orchestration.catalog.SourceLabels;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Builds the sealed links of a response (sealed-links.md section 5, rules 1 to 6).
 *
 * <p>Runs in orchestration, on the raw record, from declarations only: it calls no resolver and no adapter and
 * does no I/O. The chained key is read from the raw record, held for the length of one call in a package-private
 * candidate, and leaves only as ciphertext inside a token. Nothing here is logged, and no message, exception
 * or {@code toString} carries a key, a token or a field value. A failure of any kind fails closed to "no link".
 */
public final class LinkBuilder {

    private static final String DIRECT = "direct";
    private static final String TRANSLATED = "translated";

    /** One answered source: its real name, its label, and the raw record it returned. */
    public record Origin(String name, String label, Object record) {
        public Origin {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(record, "record");
        }

        @Override
        public String toString() {
            return "Origin[redacted]";
        }
    }

    /** The links one source carries, and how many it lost to the cap or to a limit. */
    public record Linked(List<IssuedLink> links, int omitted) {
        public Linked {
            links = List.copyOf(links);
        }
    }

    /**
     * What a call built: per source label, only for sources with a link or an omission, and every token, so the
     * caller can exempt exactly those from the key-shape scan.
     */
    public record Result(Map<String, Linked> bySource, Set<String> tokens, int issued, int omitted) {
        public static final Result NONE = new Result(Map.of(), Set.of(), 0, 0);

        public Result {
            bySource = Map.copyOf(bySource);
            tokens = Set.copyOf(tokens);
        }

        @Override
        public String toString() {
            return "Result[issued=" + issued + ", omitted=" + omitted + "]";
        }
    }

    /** One link that could be issued, before the cap. Holds the chained key: never leaves this class. */
    private record Candidate(String originLabel, LinkTargets.Target target, String field, KeyNamespace namespace,
                             byte[] key) {
        @Override
        public String toString() {
            return "Candidate[redacted]";
        }
    }

    private final LinkCodec codec;
    private final int maxPerResponse;
    private final KeyResolvers resolvers;
    private final Map<String, ModelDescriptor> descriptors;
    private final Clock clock;
    private final BiFunction<String, Object, Optional<ChainableView>> views;
    private final SecureRandom random = new SecureRandom();
    private final Map<Class<?>, Optional<List<ChainableField>>> fieldsByType = new ConcurrentHashMap<>();

    public LinkBuilder(LinkCodec codec, int maxPerResponse, KeyResolvers resolvers,
                       Map<String, ModelDescriptor> descriptors, Clock clock) {
        this(codec, maxPerResponse, resolvers, descriptors, clock, (name, record) -> Optional.empty());
    }

    /**
     * @param views what the scrubbing engine says about a record, by source name and record: asked first, and an
     *              empty answer leaves the decision to the record's class. Never cached, because two sources can
     *              share one record class and declare different fields.
     */
    public LinkBuilder(LinkCodec codec, int maxPerResponse, KeyResolvers resolvers,
                       Map<String, ModelDescriptor> descriptors, Clock clock,
                       BiFunction<String, Object, Optional<ChainableView>> views) {
        this.views = Objects.requireNonNull(views, "views");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (maxPerResponse < 1 || maxPerResponse > 500) {
            throw new IllegalArgumentException("INVALID_LINK_LIMIT: max-per-response must be 1 to 500");
        }
        this.maxPerResponse = maxPerResponse;
        this.resolvers = Objects.requireNonNull(resolvers, "resolvers");
        this.descriptors = Map.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param origins    the answered sources, in any order
     * @param labels     the permitted sources and the labels of this call
     * @param binding    the session's scope, caller, scope expiry and token expiry, never a request value
     * @param nameIsSafe whether a declared field name may appear in output; a name that is itself sensitive
     *                   gets no link
     */
    public Result build(List<Origin> origins, SourceLabels labels, LinkBinding binding,
                        Predicate<String> nameIsSafe) {
        Objects.requireNonNull(origins, "origins");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(nameIsSafe, "nameIsSafe");
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Integer> omittedByOrigin = new HashMap<>();
        for (Origin origin : origins) {
            collect(origin, labels, nameIsSafe, candidates, omittedByOrigin);
        }
        candidates.sort(Comparator.<Candidate, String>comparing(c -> rel(c.target().relation()))
                .thenComparing(c -> c.target().label())
                .thenComparing(Candidate::field)
                .thenComparing(Candidate::originLabel));

        Map<String, ArrayDeque<Candidate>> queues = new TreeMap<>();
        for (Candidate candidate : candidates) {
            queues.computeIfAbsent(candidate.target().label(), label -> new ArrayDeque<>()).add(candidate);
        }
        Map<String, List<IssuedLink>> issuedByOrigin = new LinkedHashMap<>();
        Set<String> tokens = new LinkedHashSet<>();
        Instant now = clock.instant();
        int issued = 0;
        boolean progress = true;
        while (issued < maxPerResponse && progress) {
            progress = false;
            for (ArrayDeque<Candidate> queue : queues.values()) {
                if (issued >= maxPerResponse) {
                    break;
                }
                Candidate next = queue.poll();
                if (next == null) {
                    continue;
                }
                progress = true;
                Optional<IssuedLink> link = seal(next, binding, now);
                if (link.isPresent()) {
                    issuedByOrigin.computeIfAbsent(next.originLabel(), k -> new ArrayList<>()).add(link.get());
                    tokens.add(link.get().link());
                    issued++;
                } else {
                    omittedByOrigin.merge(next.originLabel(), 1, Integer::sum);
                }
            }
        }
        for (ArrayDeque<Candidate> queue : queues.values()) {
            for (Candidate left : queue) {
                omittedByOrigin.merge(left.originLabel(), 1, Integer::sum);
            }
        }

        Map<String, Linked> bySource = new LinkedHashMap<>();
        Set<String> labelsWithAnything = new LinkedHashSet<>(issuedByOrigin.keySet());
        labelsWithAnything.addAll(omittedByOrigin.keySet());
        int omitted = 0;
        for (String label : labelsWithAnything) {
            List<IssuedLink> links = new ArrayList<>(issuedByOrigin.getOrDefault(label, List.of()));
            links.sort(Comparator.comparing(IssuedLink::rel).thenComparing(IssuedLink::target)
                    .thenComparing(IssuedLink::field));
            int lost = omittedByOrigin.getOrDefault(label, 0);
            omitted += lost;
            bySource.put(label, new Linked(links, lost));
        }
        return new Result(bySource, tokens, issued, omitted);
    }

    private void collect(Origin origin, SourceLabels labels, Predicate<String> nameIsSafe,
                         List<Candidate> out, Map<String, Integer> omittedByOrigin) {
        List<ChainableField> fields;
        JsonNode tree;
        try {
            Optional<ChainableView> view = views.apply(origin.name(), origin.record());
            if (view.isPresent()) {
                fields = view.get().fields();
                tree = view.get().tree();
            } else {
                fields = chainableFields(origin.record().getClass());
                tree = fields.isEmpty() ? null : SourceTree.of(origin.record());
            }
        } catch (RuntimeException unreadable) {
            return; // fail closed: no link from a record the engine cannot read
        }
        if (fields.isEmpty() || tree == null) {
            return;
        }
        // One link per distinct key toward a target, at its first index in path order (OD-14).
        Set<String> seen = new LinkedHashSet<>();
        for (ChainableField field : fields) {
            if (!declaredNamesAreSafe(field.path(), nameIsSafe)) {
                continue;
            }
            List<LinkTargets.Target> targets = LinkTargets.forNamespace(field.keyNamespace(), origin.name(),
                    labels, resolvers);
            if (targets.isEmpty()) {
                continue;
            }
            List<Found> found = new ArrayList<>();
            walk(tree, field.path().split("\\."), 0, "", found);
            for (Found value : found) {
                for (LinkTargets.Target target : targets) {
                    String identity = target.name() + '\0' + field.keyNamespace().value() + '\0'
                            + (target.toNamespace() == null ? "" : target.toNamespace().value()) + '\0' + value.key();
                    if (!seen.add(identity)) {
                        continue;
                    }
                    if (!target.eligible()) {
                        omittedByOrigin.merge(origin.label(), 1, Integer::sum);
                        continue;
                    }
                    out.add(new Candidate(origin.label(), target, value.path(), field.keyNamespace(),
                            value.key().getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }

    private List<ChainableField> chainableFields(Class<?> type) {
        return fieldsByType.computeIfAbsent(type, t -> {
            try {
                return Optional.of(ChainableFields.of(t, descriptors));
            } catch (RuntimeException invalid) {
                return Optional.empty(); // fail closed
            }
        }).orElse(List.of());
    }

    private static boolean declaredNamesAreSafe(String declaredPath, Predicate<String> nameIsSafe) {
        for (String segment : declaredPath.split("\\.")) {
            String name = segment.endsWith("[]") ? segment.substring(0, segment.length() - 2) : segment;
            if (name.isEmpty() || !nameIsSafe.test(name)) {
                return false;
            }
        }
        return true;
    }

    private record Found(String path, String key) {
        @Override
        public String toString() {
            return "Found[redacted]";
        }
    }

    /** Walks the declared path through the raw tree, producing the concrete indexed path of each scalar. */
    private static void walk(JsonNode node, String[] segments, int at, String prefix, List<Found> out) {
        String segment = segments[at];
        boolean array = segment.endsWith("[]");
        String name = array ? segment.substring(0, segment.length() - 2) : segment;
        JsonNode child = node == null || !node.isObject() ? null : node.get(name);
        if (child == null || child.isNull() || child.isMissingNode()) {
            return;
        }
        boolean last = at == segments.length - 1;
        if (array) {
            if (!child.isArray()) {
                return;
            }
            for (int i = 0; i < child.size(); i++) {
                step(child.get(i), segments, at, prefix + name + "[" + i + "]", last, out);
            }
        } else {
            step(child, segments, at, prefix + name, last, out);
        }
    }

    private static void step(JsonNode value, String[] segments, int at, String path, boolean last,
                             List<Found> out) {
        if (last) {
            if (value != null && (value.isString() || value.isNumber())) {
                String key = value.asString();
                if (!key.isEmpty()) {
                    out.add(new Found(path, key));
                }
            }
        } else {
            walk(value, segments, at + 1, path + ".", out);
        }
    }

    private Optional<IssuedLink> seal(Candidate candidate, LinkBinding binding, Instant now) {
        try {
            byte[] linkId = new byte[8];
            random.nextBytes(linkId);
            LinkRelation relation = candidate.target().relation();
            FollowPayload payload = new FollowPayload(linkId, now, candidate.namespace().value(),
                    Optional.ofNullable(candidate.target().resolverName()), candidate.key(),
                    candidate.target().label(), relation);
            if (!(codec.sealDetailed(payload, binding) instanceof SealResult.Issued issued)) {
                return Optional.empty();
            }
            KeyNamespace to = candidate.target().toNamespace();
            return Optional.of(new IssuedLink(rel(relation), candidate.target().label(), candidate.field(),
                    candidate.namespace().value(), to == null ? null : to.value(), issued.link().token(),
                    issued.link().expiry().toString()));
        } catch (RuntimeException failure) {
            return Optional.empty(); // fail closed; the message could name a value
        }
    }

    private static String rel(LinkRelation relation) {
        return relation == LinkRelation.DIRECT ? DIRECT : TRANSLATED;
    }
}
