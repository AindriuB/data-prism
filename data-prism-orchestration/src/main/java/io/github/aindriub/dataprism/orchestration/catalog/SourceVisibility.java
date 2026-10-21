package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.model.InvestigationContext;

/**
 * Which sources a caller may see in the catalog.
 *
 * <p>{@link #allowAll()} is the only implementation in 0.8: every source is allowed to a caller
 * holding the tool capability (catalog OD-C5 (a)). From 0.9 a source that no pattern matches is
 * denied (D-RM-11).
 */
@FunctionalInterface
public interface SourceVisibility {

    boolean permitted(InvestigationContext caller, String sourceName);

    /** Permits every source to every caller. See the type documentation for when that ends. */
    static SourceVisibility allowAll() {
        return (caller, sourceName) -> true;
    }
}
