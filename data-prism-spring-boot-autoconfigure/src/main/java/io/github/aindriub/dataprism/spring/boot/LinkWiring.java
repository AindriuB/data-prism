package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.engine.ChainableFields;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkConfigurationException;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.links.KeyResolvers;
import io.github.aindriub.dataprism.orchestration.links.ResolverInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The agent-led surface: the failed-attempt limiter and the one {@link LinkAttemptGate}, the key resolvers and their
 * bounded caller, and the options that turn {@code _links} and {@code follow} on. Nothing here makes links more
 * visible than configuration says: {@code dataprism.links.enabled} defaults to {@code false}.
 *
 * <p><b>A sealed-token consumer is active</b> when {@code dataprism.links.enabled} is {@code true} or a link key is
 * configured (page links on {@code list_sources} are sealed links too, and paging has no switch of its own). That
 * condition is defined once, in {@link #consumerActive}, and every bean below uses it. While one is active, one gate
 * built from the link codec and the limiter goes to the orchestration services only, never to an mcp class and never
 * as the raw limiter.
 *
 * <p>The limiter. On {@code single-node}, and on {@code embedded} with {@code join.mode: none}, the in-memory limiter
 * is the default and is never refused. On {@code embedded} with {@code tcp-ip} or {@code kubernetes} the count must
 * be shared by every member, or one caller could try {@code limit} links on each member: unless the
 * {@link LinkAttemptLimiter} bean says {@code clusterWide()}, startup refuses with {@code MISSING_SHARED_LINK_LIMITER}.
 * No cluster-wide limiter ships in 1.0, so on such a member an application must supply one.
 */
@Configuration(proxyBeanMethods = false)
@Import(LinkKeysHealth.class)
class LinkWiring {
    private static final Logger LOG = LoggerFactory.getLogger(LinkWiring.class);

    /** The one definition of an active sealed-token consumer, for a bound {@link DataPrismProperties}. */
    static boolean consumerActive(DataPrismProperties properties) {
        LinksProperties links = properties.getLinks();
        return links.isEnabled() || links.keyConfigured();
    }

    /** The same definition, read from the environment before the properties are bound. */
    static final class ConsumerActive implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            String keyId = environment.getProperty("dataprism.links.key-id");
            if (keyId != null && !keyId.isBlank()) {
                return true;
            }
            try {
                return Boolean.TRUE.equals(environment.getProperty("dataprism.links.enabled", Boolean.class));
            } catch (RuntimeException notABoolean) {
                // The properties' own binding refuses the value; it is not a switch on.
                return false;
            }
        }
    }

    /**
     * What the options need from this wiring, as one bean so that {@link CatalogWiring} reads one thing. The
     * resolvers and the invoker exist only while links are enabled.
     */
    static final class Parts {
        private final LinkCodec codec;
        private final LinkKeys keys;
        private final LinkAttemptGate gate;
        private final KeyResolvers resolvers;
        private final ResolverInvoker invoker;
        private final Map<String, ModelDescriptor> descriptors;
        private final Clock clock;

        Parts(LinkCodec codec, LinkKeys keys, LinkAttemptGate gate, KeyResolvers resolvers, ResolverInvoker invoker,
              Map<String, ModelDescriptor> descriptors, Clock clock) {
            this.codec = codec;
            this.keys = keys;
            this.gate = gate;
            this.resolvers = resolvers;
            this.invoker = invoker;
            this.descriptors = descriptors;
            this.clock = clock;
        }

        /** With links off, the options are returned as they were: no {@code _links}, no {@code follow}. */
        OrchestrationOptions applyTo(OrchestrationOptions options, DataPrismProperties properties) {
            if (!properties.getLinks().isEnabled() || resolvers == null) {
                return options;
            }
            LinksProperties links = properties.getLinks();
            KeyResolverProperties resolverSettings = properties.getKeyResolvers();
            try {
                return options
                        .withLinks(new OrchestrationOptions.LinkIssuing(codec, links.maxPerResponseValue(), resolvers,
                                descriptors, clock))
                        .withFollow(new OrchestrationOptions.FollowSettings(gate, keys, resolvers, invoker,
                                resolverSettings.maxTargetsValue(), resolverSettings.timeoutValue(),
                                resolverSettings.budgetCostValue()));
            } catch (IllegalArgumentException refused) {
                throw new DataPrismConfigurationException(
                        refused.getMessage() != null && refused.getMessage().startsWith("INVALID_LINK_LIMIT")
                                ? "INVALID_LINK_LIMIT" : "INVALID_KEY_RESOLVER_SETTING",
                        "dataprism.links and dataprism.key-resolvers: the settings are not valid");
            }
        }

        @Override
        public String toString() {
            return "LinkWiring.Parts[redacted]";
        }
    }

    /** Resolve this before singleton creation: an application bean of one of these types takes none of them over. */
    @Bean
    static BeanFactoryPostProcessor dataPrismLinkPreflight() {
        return factory -> {
            PrivacyExtensionPoints.refuseCompetingBeans(factory, LinkAttemptGate.class, "dataPrismLinkAttemptGate",
                    () -> new DataPrismConfigurationException("FORBIDDEN_PRIVACY_OVERRIDE",
                            "an application LinkAttemptGate bean cannot replace the framework's"));
            PrivacyExtensionPoints.refuseCompetingBeans(factory, Parts.class, "dataPrismLinkParts",
                    () -> new DataPrismConfigurationException("FORBIDDEN_PRIVACY_OVERRIDE",
                            "an application bean cannot replace the framework's link wiring"));
        };
    }

    /**
     * The in-memory limiter, unless the application supplies its own. It is the default on every topology and is
     * refused later by {@link #dataPrismLinkLimiterValidated} where it cannot be shared.
     */
    @Bean @ConditionalOnMissingBean(LinkAttemptLimiter.class)
    @Conditional(ConsumerActive.class)
    LinkAttemptLimiter dataPrismLinkAttemptLimiter(DataPrismProperties properties, Clock clock) {
        LinksProperties links = properties.getLinks();
        try {
            return new InMemoryLinkAttemptLimiter(links.invalidAttemptLimitValue(), links.invalidAttemptWindowValue(),
                    clock);
        } catch (LinkConfigurationException refused) {
            throw new DataPrismConfigurationException(refused.code(), "dataprism.links: the attempt limit is not valid");
        }
    }

    @Bean
    @Conditional(ConsumerActive.class)
    LinkAttemptGate dataPrismLinkAttemptGate(LinkCodec codec, LinkAttemptLimiter limiter) {
        return new LinkAttemptGate(codec, limiter);
    }

    /**
     * {@code MISSING_SHARED_LINK_LIMITER}: on {@code embedded} with {@code tcp-ip} or {@code kubernetes}, a limiter
     * that does not say {@code clusterWide()} is refused while a consumer is active. Names the condition, never a
     * value. Never raised on {@code single-node} or on {@code join.mode: none}.
     */
    @Lazy(false)
    @Bean
    @Conditional(ConsumerActive.class)
    Object dataPrismLinkLimiterValidated(DataPrismProperties properties, ObjectProvider<LinkAttemptLimiter> limiter) {
        if (consumerActive(properties) && multiMember(properties.getHazelcast())) {
            LinkAttemptLimiter bean = limiter.getIfAvailable();
            boolean shared;
            try {
                shared = bean != null && bean.clusterWide();
            } catch (RuntimeException unreadable) {
                shared = false;
            }
            if (!shared) {
                throw new DataPrismConfigurationException("MISSING_SHARED_LINK_LIMITER",
                        "sealed links (follow links and page links) are in use on a member that joins a cluster "
                                + "through tcp-ip or kubernetes, so the failed-attempt count must be shared by every "
                                + "member: provide a LinkAttemptLimiter bean whose clusterWide() is true");
            }
        }
        return new Object();
    }

    private static boolean multiMember(HazelcastProperties hazelcast) {
        if (!"embedded".equals(hazelcast.getTopology())) {
            return false;
        }
        String mode = hazelcast.getJoin().getMode();
        return mode != null && ("tcp-ip".equals(mode.strip()) || "kubernetes".equals(mode.strip()));
    }

    /**
     * The link parts, built while a consumer is active. With links enabled it also collects every application
     * {@link KeyResolver} bean, in {@code @Order} order, and builds the bounded caller.
     */
    @Bean
    @Conditional(ConsumerActive.class)
    Parts dataPrismLinkParts(DataPrismProperties properties, LinkCodec codec, LinkKeys keys, LinkAttemptGate gate,
                             ObjectProvider<KeyResolver> resolverBeans, SourceCatalog catalog,
                             List<DataSourceAdapter<?>> adapters, ObjectProvider<JsonTreeScrubbingEngine> engines,
                             ObjectProvider<PrivacyMetrics> metrics, Clock clock) {
        JsonTreeScrubbingEngine engine = engines.getIfUnique();
        Map<String, ModelDescriptor> descriptors =
                engine == null ? Map.of() : engine.descriptors();
        if (!properties.getLinks().isEnabled()) {
            return new Parts(codec, keys, gate, null, null, descriptors, clock);
        }
        KeyResolvers resolvers = keyResolvers(resolverBeans.orderedStream().toList());
        warnUnused(resolvers, catalog, adapters, descriptors);
        return new Parts(codec, keys, gate, resolvers, new ResolverInvoker(metrics.getIfAvailable(PrivacyMetrics::none)),
                descriptors, clock);
    }

    private static KeyResolvers keyResolvers(List<KeyResolver> beans) {
        try {
            return new KeyResolvers(beans);
        } catch (IllegalArgumentException refused) {
            String message = String.valueOf(refused.getMessage());
            String code = message.startsWith("DUPLICATE_KEY_RESOLVER") ? "DUPLICATE_KEY_RESOLVER"
                    : "INVALID_KEY_RESOLVER";
            // The registry's message names the rule and never a resolver or namespace; the cause is dropped anyway.
            throw new DataPrismConfigurationException(code, message.startsWith(code + ": ")
                    ? message.substring(code.length() + 2) : "a KeyResolver bean is not valid");
        } catch (RuntimeException broken) {
            // A resolver that throws from name(), from() or to(): which one is not said.
            throw new DataPrismConfigurationException("INVALID_KEY_RESOLVER", "a KeyResolver bean could not be read");
        }
    }

    /** One WARN line with the count and the pair names only: namespaces are not data, but nothing else is said. */
    private static void warnUnused(KeyResolvers resolvers, SourceCatalog catalog, List<DataSourceAdapter<?>> adapters,
                                   Map<String, ModelDescriptor> descriptors) {
        Set<KeyNamespace> used = new HashSet<>();
        catalog.entries().forEach(entry -> used.addAll(entry.description().inputKeys()));
        for (DataSourceAdapter<?> adapter : adapters) {
            try {
                ChainableFields.of(adapter.responseType(), descriptors).forEach(f -> used.add(f.keyNamespace()));
            } catch (RuntimeException undeclared) {
                // A bad chainable declaration is refused where the catalog is built.
            }
        }
        List<KeyResolvers.Pair> unused = resolvers.unusedPairs(used);
        if (!unused.isEmpty()) {
            List<String> names = new ArrayList<>();
            unused.forEach(pair -> names.add(pair.from().value() + "->" + pair.to().value()));
            LOG.warn("{} key resolver pair(s) connect a namespace that no source or field uses: {}", unused.size(),
                    names);
        }
    }

    /**
     * The loaded-keys gauge of OD-08-10 (a): how many link keys this member holds. Ids are on the health contributor
     * only. A gauge that will not register is logged and startup goes on.
     */
    @Lazy(false)
    @Bean
    @Conditional(CatalogWiring.LinkKeyConfigured.class)
    static SmartInitializingSingleton dataPrismLinkKeySignals(ConfigurableListableBeanFactory beans,
                                                              ObjectProvider<PrivacyMetrics> metrics) {
        return () -> {
            try {
                LinkKeys keys = beans.getBean(LinkKeys.class);
                metrics.getIfAvailable(PrivacyMetrics::none).gauge(Metric.LINK_KEYS_LOADED,
                        () -> keys.heldKeyIds().size());
            } catch (RuntimeException registrationFailed) {
                LOG.warn("the loaded link keys gauge could not be registered: {}",
                        registrationFailed.getClass().getSimpleName());
            }
        };
    }
}
