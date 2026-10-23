package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The declared key resolvers. Refuses a bad declaration at construction, so a deployment never starts with two
 * resolvers for one pair, a resolver that translates a namespace into itself, or a name that could carry data.
 * The messages start with the refusal code and carry no resolver name or namespace.
 */
public final class KeyResolvers {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /** A declared (from, to) pair. */
    public record Pair(KeyNamespace from, KeyNamespace to) {}

    /** Name and namespaces read once, at construction, so no later call touches resolver code for them. */
    private record Entry(String name, KeyResolver resolver, Pair pair) {}

    private final List<Entry> byName;

    public KeyResolvers(Collection<? extends KeyResolver> resolvers) {
        Objects.requireNonNull(resolvers, "resolvers");
        Set<Pair> seen = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Entry> accepted = new ArrayList<>();
        for (KeyResolver resolver : resolvers) {
            String name = resolver.name();
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("INVALID_KEY_RESOLVER: a resolver name must match [a-z][a-z0-9-]{0,31}");
            }
            KeyNamespace from = resolver.from();
            KeyNamespace to = resolver.to();
            if (from == null || to == null || from.equals(to)) {
                throw new IllegalArgumentException("INVALID_KEY_RESOLVER: a resolver must translate between two different namespaces");
            }
            if (!seen.add(new Pair(from, to))) {
                throw new IllegalArgumentException("DUPLICATE_KEY_RESOLVER: two resolvers are declared for one (from, to) pair");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("DUPLICATE_KEY_RESOLVER: two resolvers share one name");
            }
            accepted.add(new Entry(name, resolver, new Pair(from, to)));
        }
        accepted.sort(Comparator.comparing(Entry::name));
        this.byName = List.copyOf(accepted);
    }

    /** The resolvers that accept {@code from}, in ascending name order. */
    public List<KeyResolver> from(KeyNamespace from) {
        return byName.stream().filter(e -> e.pair().from().equals(from)).map(Entry::resolver).toList();
    }

    /**
     * The declared pairs that cannot be used because a namespace at either end is used by no field or input key.
     * A startup warning, not a refusal. In ascending resolver-name order.
     */
    public List<Pair> unusedPairs(Set<KeyNamespace> used) {
        Objects.requireNonNull(used, "used");
        return byName.stream()
                .map(Entry::pair)
                .filter(p -> !used.contains(p.from()) || !used.contains(p.to()))
                .toList();
    }
}
