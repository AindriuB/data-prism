package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.orchestration.catalog.RealNamesPolicy;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceVisibility;

import java.util.Objects;
import java.util.Optional;

/**
 * The optional collaborators of a {@link DefaultContextOrchestrator}, passed as the last argument of
 * its full constructor so that a later collaborator is added here and no call site moves again.
 *
 * <p>Immutable. Start from {@link #defaults()} and call a {@code with...} method per setting.
 *
 * @param catalog               what each source says about itself; empty means the orchestrator builds one
 *                              from its own adapters
 * @param visibility            which sources a caller may see; every source to every caller by default
 * @param realNames             which scopes may be shown real source names; none by default
 * @param unselectedSourceLimit the most permitted sources a call without {@code targetSources} may ask
 */
public record OrchestrationOptions(Optional<SourceCatalog> catalog, SourceVisibility visibility,
                                   RealNamesPolicy realNames, int unselectedSourceLimit) {

    /** The default for {@code dataprism.catalog.unselected-source-limit}. */
    public static final int DEFAULT_UNSELECTED_SOURCE_LIMIT = 8;

    public OrchestrationOptions {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(realNames, "realNames");
        if (unselectedSourceLimit < 1) {
            throw new IllegalArgumentException("INVALID_CATALOG_SETTING: unselectedSourceLimit must be positive");
        }
    }

    public static OrchestrationOptions defaults() {
        return new OrchestrationOptions(Optional.empty(), SourceVisibility.allowAll(), RealNamesPolicy.none(),
                DEFAULT_UNSELECTED_SOURCE_LIMIT);
    }

    public OrchestrationOptions withCatalog(SourceCatalog catalog) {
        return new OrchestrationOptions(Optional.of(catalog), visibility, realNames, unselectedSourceLimit);
    }

    public OrchestrationOptions withVisibility(SourceVisibility visibility) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit);
    }

    public OrchestrationOptions withRealNames(RealNamesPolicy realNames) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit);
    }

    public OrchestrationOptions withUnselectedSourceLimit(int unselectedSourceLimit) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit);
    }
}
