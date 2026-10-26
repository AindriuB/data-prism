package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.ClusterMembership;
import io.github.aindriub.dataprism.hazelcast.HazelcastApprovalStore;
import io.github.aindriub.dataprism.hazelcast.HazelcastCallerRateLimiter;
import io.github.aindriub.dataprism.hazelcast.HazelcastOversightState;
import io.github.aindriub.dataprism.hazelcast.HazelcastScopeBudget;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.oversight.OversightState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;

import java.util.OptionalInt;

/**
 * {@code embedded}: the one {@link PrivacyCluster} member, and everything built on it, so the
 * budget (see {@link ScopeBudgetWiring#dataPrismClusterScopeBudget}), the oversight state, the approval store and the caller rate limiter share a single
 * member rather than each starting its own. This nested class is {@code @ConditionalOnClass}
 * guarded so a {@code single-node} consumer that never put {@code data-prism-hazelcast} on its
 * classpath never loads it; its beans are registered ahead of the outer class's, so the
 * in-memory fall-backs below see them. When the topology is {@code embedded} and that
 * dependency is absent, no {@link ScopeBudget} bean is created here at all, and
 * {@link Preflights#dataPrismSharedBudgetPreflight} turns that silence into {@code MISSING_SHARED_BUDGET}.
 */
@ConditionalOnClass(HazelcastInstance.class)
@ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "embedded")
@Configuration(proxyBeanMethods = false)
class ClusterBackedState {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterBackedState.class);

    /**
     * Built from the explicit {@code dataprism.hazelcast} cluster settings, never from a default
     * {@code Config}. Waits for {@link PropertiesValidation#dataPrismPropertiesValidated} so a refused configuration
     * never starts a member.
     */
    @Bean @ConditionalOnMissingBean @DependsOn("dataPrismPropertiesValidated")
    PrivacyCluster dataPrismPrivacyCluster(DataPrismProperties properties,
            ObjectProvider<HmacKeyReferenceResolver> keyResolver, ObjectProvider<PrivacyMetrics> metrics) {
        HazelcastProperties h = properties.getHazelcast();
        // Resolved before anything starts; the material lives only as long as ClusterKeys needs to copy it.
        ClusterKeys keys = ClusterKeyResolution.resolve(properties, keyResolver::getObject).orElse(null);
        try {
            ClusterMembership membership = new ClusterMembership(h.getClusterName().strip(), join(h.getJoin()),
                    h.getMember().getPort() == null ? ClusterMembership.DEFAULT_PORT : h.getMember().getPort(),
                    h.getMember().getInterface());
            PrivacyCluster cluster = PrivacyCluster.embedded(membership, h.isReidentificationEnabled(),
                    approvalMinimum(h, properties), h.getApprovalSweepInterval(), keys);
            try {
                metrics.getIfAvailable(PrivacyMetrics::none).gauge(Metric.CLUSTER_LEGACY_ENTRIES,
                        cluster::legacyEntries);
            } catch (RuntimeException | Error registrationFailed) {
                // A member that was started here is not left running because a gauge would not register.
                closeSuppressing(cluster::close, registrationFailed);
                throw registrationFailed;
            }
            return cluster;
        } catch (PrivacyClusterRefusal refusal) {
            String code = refusal.code().name();
            throw new DataPrismConfigurationException(code, refusal.getMessage().substring(code.length() + 2),
                    refusal);
        }
    }

    /**
     * The legacy-entries gauge for a {@link PrivacyCluster} the application supplied, which the bean above does not
     * build. Fail-safe: that member is not ours to stop, so a gauge that will not register is logged by class and
     * startup goes on.
     */
    @Lazy(false)
    @Bean
    static SmartInitializingSingleton dataPrismSuppliedClusterGauge(ConfigurableListableBeanFactory beans,
            ObjectProvider<PrivacyMetrics> metrics) {
        return () -> beans.getBeansOfType(PrivacyCluster.class).forEach((name, cluster) -> {
            try {
                if (builtHere(beans, name)) {
                    return;
                }
                metrics.getIfAvailable(PrivacyMetrics::none).gauge(Metric.CLUSTER_LEGACY_ENTRIES,
                        cluster::legacyEntries);
            } catch (RuntimeException registrationFailed) {
                LOG.warn("the legacy-entries gauge could not be registered for the supplied cluster: {}",
                        registrationFailed.getClass().getSimpleName());
            }
        });
    }

    /**
     * Whether the bean was built by {@link #dataPrismPrivacyCluster}, told by its definition's factory method and not
     * by its name: an application bean may carry the same name.
     */
    private static boolean builtHere(ConfigurableListableBeanFactory beans, String name) {
        // A singleton registered by hand has no definition, and so was not built here.
        return beans.containsBeanDefinition(name)
                && beans.getMergedBeanDefinition(name) instanceof RootBeanDefinition root
                && root.getResolvedFactoryMethod() != null
                && root.getResolvedFactoryMethod().getDeclaringClass() == ClusterBackedState.class
                && "dataPrismPrivacyCluster".equals(root.getResolvedFactoryMethod().getName());
    }

    /**
     * Runs {@code close}; if it fails too, that failure is added to {@code failure} as suppressed, so the original
     * is what propagates.
     */
    static void closeSuppressing(Runnable close, Throwable failure) {
        try {
            close.run();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * The explicit minimum if given. Otherwise, with approvals in use (a gated tool or re-identification), empty,
     * which takes the default for the join mode. Without approvals in use, 1, which applies no protection, so no
     * quorum is computed from a member list that may not be countable.
     */
    private static OptionalInt approvalMinimum(HazelcastProperties h, DataPrismProperties properties) {
        if (h.getApprovalMinimumMembers() != null) {
            return OptionalInt.of(h.getApprovalMinimumMembers());
        }
        boolean inUse = !properties.getOversight().getApprovalRequiredTools().isEmpty()
                || properties.getReidentification().isEnabled();
        return inUse ? OptionalInt.empty() : OptionalInt.of(1);
    }

    private static ClusterMembership.Join join(HazelcastProperties.Join join) {
        HazelcastProperties.Kubernetes k = join.getKubernetes();
        return switch (join.getMode().strip()) {
            case "tcp-ip" -> new ClusterMembership.TcpIp(join.getMembers());
            case "kubernetes" -> new ClusterMembership.Kubernetes(k.getNamespace(), k.getServiceName(),
                    k.getServiceDns());
            case "none" -> new ClusterMembership.None();
            default -> throw new DataPrismConfigurationException("UNSUPPORTED_CLUSTER_JOIN",
                    "dataprism.hazelcast.join.mode is not a supported mode");
        };
    }

    /**
     * Called by {@link ScopeBudgetWiring#dataPrismClusterScopeBudget}; this class is never loaded unless that runs.
     * Registers the budget as dependent on whichever cluster bean it is built over, so the
     * budget is destroyed before the member it uses is shut down.
     */
    static ScopeBudget budgetOver(ObjectProvider<PrivacyCluster> cluster,
            ConfigurableListableBeanFactory beanFactory, String budgetBeanName) {
        PrivacyCluster resolved = cluster.getObject();
        for (String name : beanFactory.getBeanNamesForType(PrivacyCluster.class, true, false)) {
            beanFactory.registerDependentBean(name, budgetBeanName);
        }
        return new HazelcastScopeBudget(resolved,
                beanFactory.getBean(DataPrismProperties.class).getHazelcast().getLockTimeout());
    }

    @Bean @ConditionalOnMissingBean
    OversightState dataPrismClusterOversightState(PrivacyCluster cluster) {
        return new HazelcastOversightState(cluster);
    }

    @Bean @ConditionalOnMissingBean
    ApprovalStore dataPrismClusterApprovalStore(PrivacyCluster cluster, DataPrismProperties properties,
            ObjectProvider<PrivacyMetrics> metrics) {
        return new HazelcastApprovalStore(cluster, properties.getHazelcast().getLockTimeout(),
                metrics.getIfAvailable(PrivacyMetrics::none));
    }

    @Bean @ConditionalOnMissingBean
    CallerRateLimiter dataPrismClusterCallerRateLimiter(PrivacyCluster cluster, DataPrismProperties properties) {
        return new HazelcastCallerRateLimiter(cluster, properties.getHazelcast().getLockTimeout());
    }
}
