package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.orchestration.catalog.RealNamesPolicy;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceVisibility;
import io.github.aindriub.dataprism.orchestration.links.KeyResolvers;
import io.github.aindriub.dataprism.orchestration.links.ResolverInvoker;

import java.time.Clock;
import java.time.Duration;
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
 * @param follow                how a sealed link is followed; empty means {@code follow} refuses every link
 */
public record OrchestrationOptions(Optional<SourceCatalog> catalog, SourceVisibility visibility,
                                   RealNamesPolicy realNames, int unselectedSourceLimit,
                                   Optional<LinkIssuing> links, Optional<FollowSettings> follow) {

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

    /**
     * The settings for following a link ({@code dataprism.links.*} and {@code dataprism.key-resolvers.*}, bound
     * in a later task).
     *
     * @param gate            the only way a token is opened; owns the failed-attempt limiter
     * @param keys            the link keys, for the keyed fingerprint that orders a one-to-many follow
     * @param resolvers       the declared key resolvers
     * @param invoker         the bounded caller of a resolver
     * @param maxTargets      {@code dataprism.key-resolvers.max-targets}: the most keys a resolver may return
     * @param resolverTimeout {@code dataprism.key-resolvers.timeout}
     * @param budgetCost      {@code dataprism.key-resolvers.budget-cost}: reads one resolver call is charged
     */
    public record FollowSettings(LinkAttemptGate gate, LinkKeys keys, KeyResolvers resolvers,
                                 ResolverInvoker invoker, int maxTargets, Duration resolverTimeout,
                                 int budgetCost) {

        public static final int DEFAULT_MAX_TARGETS = 5;
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
        public static final int DEFAULT_BUDGET_COST = 1;

        public FollowSettings {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(keys, "keys");
            Objects.requireNonNull(resolvers, "resolvers");
            Objects.requireNonNull(invoker, "invoker");
            Objects.requireNonNull(resolverTimeout, "resolverTimeout");
            if (maxTargets < 1) {
                throw new IllegalArgumentException("INVALID_KEY_RESOLVER_SETTING: max-targets must be positive");
            }
            if (resolverTimeout.isZero() || resolverTimeout.isNegative()) {
                throw new IllegalArgumentException("INVALID_KEY_RESOLVER_SETTING: timeout must be positive");
            }
            if (budgetCost < 1) {
                throw new IllegalArgumentException("INVALID_KEY_RESOLVER_SETTING: budget-cost must be positive");
            }
        }

        public static FollowSettings of(LinkAttemptGate gate, LinkKeys keys, KeyResolvers resolvers,
                                        ResolverInvoker invoker) {
            return new FollowSettings(gate, keys, resolvers, invoker, DEFAULT_MAX_TARGETS, DEFAULT_TIMEOUT,
                    DEFAULT_BUDGET_COST);
        }
    }

    public OrchestrationOptions(Optional<SourceCatalog> catalog, SourceVisibility visibility,
                                RealNamesPolicy realNames, int unselectedSourceLimit) {
        this(catalog, visibility, realNames, unselectedSourceLimit, Optional.empty(), Optional.empty());
    }

    public OrchestrationOptions(Optional<SourceCatalog> catalog, SourceVisibility visibility,
                                RealNamesPolicy realNames, int unselectedSourceLimit,
                                Optional<LinkIssuing> links) {
        this(catalog, visibility, realNames, unselectedSourceLimit, links, Optional.empty());
    }

    public OrchestrationOptions {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(realNames, "realNames");
        Objects.requireNonNull(links, "links");
        Objects.requireNonNull(follow, "follow");
        if (unselectedSourceLimit < 1) {
            throw new IllegalArgumentException("INVALID_CATALOG_SETTING: unselectedSourceLimit must be positive");
        }
    }

    public static OrchestrationOptions defaults() {
        return new OrchestrationOptions(Optional.empty(), SourceVisibility.allowAll(), RealNamesPolicy.none(),
                DEFAULT_UNSELECTED_SOURCE_LIMIT, Optional.empty(), Optional.empty());
    }

    public OrchestrationOptions withCatalog(SourceCatalog catalog) {
        return new OrchestrationOptions(Optional.of(catalog), visibility, realNames, unselectedSourceLimit, links, follow);
    }

    public OrchestrationOptions withVisibility(SourceVisibility visibility) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit, links, follow);
    }

    public OrchestrationOptions withRealNames(RealNamesPolicy realNames) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit, links, follow);
    }

    public OrchestrationOptions withUnselectedSourceLimit(int unselectedSourceLimit) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit, links, follow);
    }

    public OrchestrationOptions withLinks(LinkIssuing links) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit,
                Optional.of(links), follow);
    }

    public OrchestrationOptions withFollow(FollowSettings follow) {
        return new OrchestrationOptions(catalog, visibility, realNames, unselectedSourceLimit, links,
                Optional.of(follow));
    }
}
