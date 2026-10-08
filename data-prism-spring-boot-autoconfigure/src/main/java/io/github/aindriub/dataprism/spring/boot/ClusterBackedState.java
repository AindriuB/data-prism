package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.hazelcast.ClusterMembership;
import io.github.aindriub.dataprism.hazelcast.HazelcastApprovalStore;
import io.github.aindriub.dataprism.hazelcast.HazelcastCallerRateLimiter;
import io.github.aindriub.dataprism.hazelcast.HazelcastOversightState;
import io.github.aindriub.dataprism.hazelcast.HazelcastScopeBudget;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.oversight.OversightState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.util.Optional;

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
    /**
     * Built from the explicit {@code dataprism.hazelcast} cluster settings, never from a default
     * {@code Config}. Waits for {@link PropertiesValidation#dataPrismPropertiesValidated} so a refused configuration
     * never starts a member.
     */
    @Bean @ConditionalOnMissingBean @DependsOn("dataPrismPropertiesValidated")
    PrivacyCluster dataPrismPrivacyCluster(DataPrismProperties properties) {
        HazelcastProperties h = properties.getHazelcast();
        try {
            ClusterMembership membership = new ClusterMembership(h.getClusterName().strip(), join(h.getJoin()),
                    h.getMember().getPort() == null ? ClusterMembership.DEFAULT_PORT : h.getMember().getPort(),
                    Optional.ofNullable(h.getMember().getInterface()));
            return PrivacyCluster.embedded(membership, h.isReidentificationEnabled());
        } catch (PrivacyClusterRefusal refusal) {
            String code = refusal.code().name();
            throw new DataPrismConfigurationException(code, refusal.getMessage().substring(code.length() + 2));
        }
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
        return new HazelcastScopeBudget(resolved);
    }

    @Bean @ConditionalOnMissingBean
    OversightState dataPrismClusterOversightState(PrivacyCluster cluster) {
        return new HazelcastOversightState(cluster);
    }

    @Bean @ConditionalOnMissingBean
    ApprovalStore dataPrismClusterApprovalStore(PrivacyCluster cluster) {
        return new HazelcastApprovalStore(cluster);
    }

    @Bean @ConditionalOnMissingBean
    CallerRateLimiter dataPrismClusterCallerRateLimiter(PrivacyCluster cluster) {
        return new HazelcastCallerRateLimiter(cluster);
    }
}
