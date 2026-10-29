package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which sources a call asks, decided before anything is reserved or called (catalog section 6).
 *
 * <p>With {@code targetSources}, each label must resolve to a permitted source that serves the
 * entity type; one that does not refuses the whole call as {@code SOURCE_NOT_AVAILABLE}, whatever
 * the reason, so the call cannot probe the estate. Without it, the permitted sources serving the
 * entity type are counted, and a count above the limit refuses the call (D-RM-9).
 *
 * <p>The client sees a bare code. {@link Refusal#auditReason()} is for the audit trail only, and
 * is never built from a label the client sent.
 */
public final class SourceSelection {

    public static final String NOT_AVAILABLE = "SOURCE_NOT_AVAILABLE";
    public static final String TOO_LARGE = "SOURCE_SELECTION_TOO_LARGE";
    public static final String REQUIRED = "SOURCE_SELECTION_REQUIRED";
    public static final String ENTITY_TYPE_MISMATCH = "CATALOG_ENTITY_TYPE_MISMATCH";

    /** A refusal before any source is called. The path and detail are fixed text. */
    public static final class Refusal extends PrivacyRefusedException {

        private final String auditReason;

        Refusal(String code, String auditReason) {
            super(code, "$", "source selection refused");
            this.auditReason = auditReason;
        }

        /** The audit-only reason for the {@code catalog} disposition, or {@code null} when the code says it all. */
        public String auditReason() {
            return auditReason;
        }
    }

    private SourceSelection() {
    }

    /**
     * @param targets    the labels the client sent, in the order sent
     * @param maxSources the most sources one call may ask
     * @return the real names of the chosen sources, each once, in the order sent
     * @throws Refusal {@code SOURCE_SELECTION_TOO_LARGE} or {@code SOURCE_NOT_AVAILABLE}
     */
    public static Set<String> targeted(SourceLabels labels, List<String> targets, String entityType,
                                       int maxSources) {
        if (targets.size() > maxSources) {
            throw new Refusal(TOO_LARGE, null);
        }
        Set<String> chosen = new LinkedHashSet<>();
        for (String label : targets) {
            switch (labels.resolve(label)) {
                case SourceLabels.Unresolved unresolved ->
                        throw new Refusal(NOT_AVAILABLE, unresolved.reason().name());
                case SourceLabels.Found found -> {
                    if (!SourceLabels.serves(found.entry(), entityType)) {
                        throw new Refusal(NOT_AVAILABLE, ENTITY_TYPE_MISMATCH);
                    }
                    chosen.add(found.entry().name());
                }
            }
        }
        return chosen;
    }

    /**
     * @param limit the most permitted sources serving the entity type that an unselected call may ask
     * @return the real names of the permitted sources, every one of which is asked
     * @throws Refusal {@code SOURCE_SELECTION_REQUIRED} when the count is above the limit
     */
    public static Set<String> unselected(SourceLabels labels, String entityType, int limit) {
        Map<String, SourceCatalog.Entry> permitted = labels.permittedEntries();
        long serving = permitted.values().stream().filter(e -> SourceLabels.serves(e, entityType)).count();
        if (serving > limit) {
            throw new Refusal(REQUIRED, null);
        }
        return new LinkedHashSet<>(permitted.keySet());
    }
}
