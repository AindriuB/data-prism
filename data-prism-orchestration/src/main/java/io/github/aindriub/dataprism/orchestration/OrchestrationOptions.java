package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.orchestration.catalog.RealNamesPolicy;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceVisibility;
import io.github.aindriub.dataprism.orchestration.links.KeyResolvers;

import java.time.Clock;
import java.util.Map;
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
 * @param links                 how sealed links are issued; empty means links are off and a response is exactly
 *                              what it was before links existed
 */
public record OrchestrationOptions(Optional<SourceCatalog> catalog, SourceVisibility visibility,
                                   RealNamesPolicy realNames, int unselectedSourceLimit,
                                   Optional<LinkIssuing> links) {

    /** The default for {@code dataprism.catalog.unselected-source-limit}. */
    public static final int DEFAULT_UNSELECTED_SOURCE_LIMIT = 8;

    /**
     * The settings for issuing links ({@code dataprism.links.*}, bound in a later task).
     *
     * @param codec          seals the links; carries the key, the lifetime and the maximum length
     * @param maxPerResponse the cap across the whole response, 1 to 500
     * @param resolvers      the declared key resolvers; only their declarations are read
     * @param descriptors    model descriptors by type name, as the chainable analysis reads them
     * @param clock          the issue-time source
     */
    public record LinkIssuing(LinkCodec codec, int maxPerResponse, KeyResolvers resolvers,
                              Map<String, ModelDescriptor> descriptors, Clock clock) {

        public LinkIssuing {
            Objects.requireNonNull(codec, "codec");
            if (maxPerResponse < 1 || maxPerResponse > 500) {
                throw new IllegalArgumentException("INVALID_LINK_LIMIT: max-per-response must be 1 to 500");
            }
            Objects.requireNonNull(resolvers, "resolvers");
            descriptors = Map.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
            Objects.requireNonNull(clock, "clock");
        }

        public static LinkIssuing of(LinkCodec codec, int maxPerResponse, KeyResolvers resolvers) {
            return new LinkIssuing(codec, maxPerResponse, resolvers, Map.of(), Clock.systemUTC());
        }
    }

    public OrchestrationOptions(Optional<SourceCatalog> catalog, SourceVisibility visibility,
                                RealNamesPolicy realNames, int unselectedSourceLimit) {
        this(catalog, visibility, realNames, unselectedSourceLimit, Optional.empty());
    }

    public OrchestrationOptions {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(realNames, "realNames");
        Objects.requireNonNull(links, "links");
        if (unselectedSourceLimit < 1) {
            throw new IllegalArgumentException("INVALID_CATALOG_SETTING: unselectedSourceLimit must be positive");
        }
    }

    public static OrchestrationOptions defaults() {
        return new OrchestrationOptions(Optional.empty(), SourceVisibility.allowAll(), RealNamesPolicy.none(),
                DEFAULT_UNSELECTED_SOURCE_LIMIT, Optional.empty());
    }

    public OrchestrationOptions withCatalog(SourceCatalog catalog) {
        return new OrchestrationOptions(Optional.of(catalog), visibility, realNames, unselectedSourceLimit, links);
    }

    public OrchestrationOptions withVisibility(SourceVisibility visibility) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit, links);
    }

    public OrchestrationOptions withRealNames(RealNamesPolicy realNames) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit, links);
    }

    public OrchestrationOptions withUnselectedSourceLimit(int unselectedSourceLimit) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit, links);
    }

    public OrchestrationOptions withLinks(LinkIssuing links) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit,
                Optional.of(links));
    }
}
