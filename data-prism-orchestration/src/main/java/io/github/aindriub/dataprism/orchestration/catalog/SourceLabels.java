package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What every source is called in one response: one decision per request (D-RM-10).
 *
 * <p>The label kind is {@code REAL} only when the scope permits real names AND the caller holds
 * {@link Capability#EXPOSE_SOURCE_NAMES}; otherwise {@code ALIAS}. It is a property of the request,
 * computed from the session, and applies to every source in the response, so one response never
 * mixes kinds. Labels are not stored: this is built per request, and resolving a label back to a
 * source is a lookup over the labels of this request.
 *
 * <p>A label of the wrong kind never resolves, so a caller cannot learn whether a real name exists
 * by whether a call succeeded while aliases are in effect.
 */
public final class SourceLabels {

    /** Why a label did not resolve to a source the caller may use. Audit-only; never sent to a client. */
    public enum Reason { CATALOG_UNKNOWN_SOURCE, CATALOG_SOURCE_NOT_PERMITTED }

    /** The answer to {@link #resolve}: a source, or a reason. */
    public sealed interface Resolution permits Found, Unresolved {
    }

    public record Found(SourceCatalog.Entry entry) implements Resolution {
    }

    public record Unresolved(Reason reason) implements Resolution {
    }

    /**
     * Two permitted sources have one label in this scope. {@link #sources()} are the real names,
     * which are operator-side data: audit them, never return them.
     */
    public static final class Collision extends PrivacyRefusedException {

        public static final String CODE = "SOURCE_LABEL_COLLISION";

        private final transient Set<String> sources;

        Collision(Set<String> sources) {
            super(CODE, "$", "two sources have the same label in this scope");
            this.sources = Collections.unmodifiableSet(new TreeSet<>(sources));
        }

        public Set<String> sources() {
            return sources;
        }
    }

    private final SourceCatalog.LabelKind kind;
    private final SourceAliasing aliasing;
    private final PrivacyContext context;
    private final Map<String, String> labelBySource;
    private final Map<String, SourceCatalog.Entry> permittedByLabel;
    private final Set<String> unpermittedLabels;

    private SourceLabels(SourceCatalog.LabelKind kind, SourceAliasing aliasing, PrivacyContext context,
                         Map<String, String> labelBySource, Map<String, SourceCatalog.Entry> permittedByLabel,
                         Set<String> unpermittedLabels) {
        this.kind = kind;
        this.aliasing = aliasing;
        this.context = context;
        this.labelBySource = Map.copyOf(labelBySource);
        this.permittedByLabel = Map.copyOf(permittedByLabel);
        this.unpermittedLabels = Set.copyOf(unpermittedLabels);
    }

    /** The effective setting for one request: both conditions are needed, and either alone is not enough. */
    public static SourceCatalog.LabelKind effectiveKind(RealNamesPolicy realNames, InvestigationContext caller,
                                                        PrivacyContext context) {
        Objects.requireNonNull(realNames, "realNames");
        return realNames.permits(context.scopeId()) && caller.has(Capability.EXPOSE_SOURCE_NAMES)
                ? SourceCatalog.LabelKind.REAL : SourceCatalog.LabelKind.ALIAS;
    }

    /**
     * @throws Collision when two permitted sources have the same label in this scope
     */
    public static SourceLabels forRequest(SourceCatalog catalog, SourceVisibility visibility,
                                          SourceAliasing aliasing, RealNamesPolicy realNames,
                                          InvestigationContext caller, PrivacyContext context) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(aliasing, "aliasing");
        SourceCatalog.LabelKind kind = effectiveKind(realNames, caller, context);

        Map<String, String> labelBySource = new LinkedHashMap<>();
        Map<String, List<SourceCatalog.Entry>> permittedByLabel = new LinkedHashMap<>();
        Set<String> unpermitted = new LinkedHashSet<>();
        for (SourceCatalog.Entry entry : catalog.entries()) {
            String label = kind == SourceCatalog.LabelKind.REAL
                    ? entry.name() : aliasing.aliasFor(entry.name(), context);
            labelBySource.put(entry.name(), label);
            boolean permitted = visibility.permitted(caller, entry.name());
            if (permitted) {
                permittedByLabel.computeIfAbsent(label, l -> new ArrayList<>()).add(entry);
            } else {
                unpermitted.add(label);
            }
        }
        Set<String> colliding = new LinkedHashSet<>();
        Map<String, SourceCatalog.Entry> unique = new LinkedHashMap<>();
        permittedByLabel.forEach((label, entries) -> {
            if (entries.size() > 1) {
                entries.forEach(e -> colliding.add(e.name()));
            } else {
                unique.put(label, entries.get(0));
            }
        });
        if (!colliding.isEmpty()) {
            throw new Collision(colliding);
        }
        return new SourceLabels(kind, aliasing, context, labelBySource, unique, unpermitted);
    }

    /** {@code ALIAS} or {@code REAL}: the value the audit key {@code names} records. */
    public SourceCatalog.LabelKind kind() {
        return kind;
    }

    /**
     * The label of a source in this response. A source the catalog does not hold is labelled with
     * its alias, never its name: fail closed.
     */
    public String labelOf(String sourceName) {
        String label = labelBySource.get(sourceName);
        return label != null ? label : aliasing.aliasFor(sourceName, context);
    }

    /** The label of every permitted source, by real name. */
    public Map<String, String> permittedLabels() {
        Map<String, String> out = new LinkedHashMap<>();
        permittedByLabel.forEach((label, entry) -> out.put(entry.name(), label));
        return Collections.unmodifiableMap(out);
    }

    /** Every permitted source, by real name. */
    public Map<String, SourceCatalog.Entry> permittedEntries() {
        Map<String, SourceCatalog.Entry> out = new LinkedHashMap<>();
        permittedByLabel.values().forEach(entry -> out.put(entry.name(), entry));
        return Collections.unmodifiableMap(out);
    }

    /** Resolves a label the client sent. A label of the other kind is simply unknown. */
    public Resolution resolve(String label) {
        SourceCatalog.Entry entry = label == null ? null : permittedByLabel.get(label);
        if (entry != null) {
            return new Found(entry);
        }
        if (label != null && unpermittedLabels.contains(label)) {
            return new Unresolved(Reason.CATALOG_SOURCE_NOT_PERMITTED);
        }
        return new Unresolved(Reason.CATALOG_UNKNOWN_SOURCE);
    }

    /** Whether the source answers this entity type: it declares it, or declares none. */
    public static boolean serves(SourceCatalog.Entry entry, String entityType) {
        Set<String> types = entry.description().entityTypes();
        return types.isEmpty() || types.contains(entityType);
    }
}
