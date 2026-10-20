package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What each source says about itself, validated once at startup.
 *
 * <p>Immutable, holds no personal data and does no I/O after {@link #build}. Entries are in
 * ascending byte order of the real name, whatever order the adapters were registered in. Labels
 * are not computed here: they vary by scope.
 */
public final class SourceCatalog {

    private static final Comparator<String> BYTE_ORDER = (a, b) ->
            Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    /** Which name the caller is shown, and so which description text goes with it. */
    public enum LabelKind { ALIAS, REAL }

    /**
     * One source.
     *
     * @param name        the real source name; operator-side data
     * @param description the validated declaration
     * @param aliasText   the text under an alias: the declared one, or the generated fallback
     * @param realText    the text under real names: the declared one, or {@code aliasText}
     * @param generated   true when {@code aliasText} was generated
     */
    public record Entry(String name, SourceDescription description, String aliasText, String realText,
                        boolean generated) {

        public String textFor(LabelKind kind) {
            return kind == LabelKind.REAL ? realText : aliasText;
        }
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byName;
    private final int nonconformingNames;

    private SourceCatalog(List<Entry> entries, int nonconformingNames) {
        this.entries = List.copyOf(entries);
        Map<String, Entry> index = new HashMap<>();
        entries.forEach(entry -> index.put(entry.name(), entry));
        this.byName = Map.copyOf(index);
        this.nonconformingNames = nonconformingNames;
    }

    /**
     * @param baseUrlHostBySource the host of each source's configured base URL, by real name
     * @throws IllegalArgumentException if a description fails rules 1 to 6 or a name repeats; the
     *                                  message names the source and the rule, never the text
     */
    public static SourceCatalog build(List<DataSourceAdapter<?>> adapters, Map<String, String> baseUrlHostBySource) {
        Objects.requireNonNull(adapters, "adapters");
        Map<String, String> hosts = baseUrlHostBySource == null ? Map.of() : baseUrlHostBySource;
        List<DataSourceAdapter<?>> ordered = new ArrayList<>(adapters);
        ordered.sort(Comparator.comparing(DataSourceAdapter::sourceName, BYTE_ORDER));

        Set<String> names = new HashSet<>();
        for (DataSourceAdapter<?> adapter : ordered) {
            if (!names.add(adapter.sourceName())) {
                throw new IllegalArgumentException("duplicate source name");
            }
        }

        SourceDescriptionChecks checks = new SourceDescriptionChecks();
        List<Entry> built = new ArrayList<>();
        for (DataSourceAdapter<?> adapter : ordered) {
            String name = adapter.sourceName();
            SourceDescription description = describe(adapter, name);
            List<String> forbidden = new ArrayList<>();
            forbidden.add(SourceDescriptionChecks.normalise(name));
            forbidden.add(SourceDescriptionChecks.normalise(adapter.getClass().getSimpleName()));
            String host = hosts.get(name);
            if (host != null) {
                forbidden.add(SourceDescriptionChecks.normalise(host));
            }
            names.stream().filter(other -> !other.equals(name))
                    .forEach(other -> forbidden.add(SourceDescriptionChecks.normalise(other)));
            checks.check(name, description, forbidden);

            boolean generated = description.aliasDescription() == null;
            String aliasText = generated ? GeneratedDescriptions.generate(description) : description.aliasDescription();
            String realText = description.realDescription() == null ? aliasText : description.realDescription();
            built.add(new Entry(name, description, aliasText, realText, generated));
        }
        return new SourceCatalog(built, SourceNames.nonconformingCount(names));
    }

    private static SourceDescription describe(DataSourceAdapter<?> adapter, String name) {
        SourceDescription description;
        try {
            description = adapter.description();
        } catch (RuntimeException e) {
            // The cause is dropped on purpose: an adapter's message may carry the text.
            throw SourceDescriptionChecks.refuse(SourceDescriptionChecks.INVALID, name, 4);
        }
        return description == null ? SourceDescription.undeclared() : description;
    }

    /** Every source, in ascending byte order of the real name. */
    public List<Entry> entries() {
        return entries;
    }

    public Optional<Entry> entry(String sourceName) {
        return Optional.ofNullable(byName.get(sourceName));
    }

    /** How many sources use the generated fallback. */
    public int generatedDescriptionCount() {
        return generatedDescriptionSources().size();
    }

    /** Real names of the sources using the generated fallback: operator-side data. */
    public List<String> generatedDescriptionSources() {
        return entries.stream().filter(Entry::generated).map(Entry::name).toList();
    }

    /** How many source names fall outside {@code [a-z][a-z0-9-]{0,63}}. Nothing refuses in 0.8. */
    public int nonconformingNameCount() {
        return nonconformingNames;
    }
}
