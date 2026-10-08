package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.CachingSyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.ScopeIdentityIndex;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.reidentification.Permission;
import io.github.aindriub.dataprism.reidentification.ReidentificationPolicy;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * The re-identification service, only when {@code dataprism.reidentification.enabled=true}.
 * Nothing here, or anywhere in this module, registers it as an MCP tool: the only transport
 * wiring is {@link McpTransportWiring#dataPrismHttpTransport}, which takes no re-identification type at all.
 */
@ConditionalOnClass({ReidentificationService.class, HazelcastInstance.class})
@ConditionalOnProperty(prefix = "dataprism.reidentification", name = "enabled", havingValue = "true")
@Configuration(proxyBeanMethods = false)
class ReidentificationWiring {
    @Bean
    ReidentificationPolicy dataPrismReidentificationPolicy(DataPrismProperties properties) {
        ReidentificationProperties r = properties.getReidentification();
        Map<String, Set<Permission>> roles = new java.util.HashMap<>();
        r.getRoles().forEach((role, permissions) -> roles.put(role, permissions.stream()
                .map(p -> Permission.valueOf(p.name())).collect(java.util.stream.Collectors.toSet())));
        return new ReidentificationPolicy(Set.copyOf(r.getPurposes()), roles, r.isFourEyes(),
                r.getApprovalTtl(), r.getMaxPendingPerRequester());
    }

    /**
     * Feeds the reverse index: every {@link SyntheticValueSource} in the context, the default or
     * the application's own, is wrapped in {@link CachingSyntheticValueSource} over the shared
     * {@link PrivacyCluster}, so each pseudonym handed out is entered where
     * {@link ScopeIdentityIndex} reads it. A decorator rather than a competing bean, so the
     * default's {@code @ConditionalOnMissingBean} still backs off for an application source. An
     * already-caching source is left alone, and a cache failure falls back to the wrapped source.
     * Only {@code embedded}; the cluster is resolved lazily to keep this post-processor early-safe.
     */
    @Bean @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "embedded")
    static BeanPostProcessor dataPrismReidentificationIndexFeed(ObjectProvider<PrivacyCluster> cluster,
            ObjectProvider<PrivacyMetrics> metrics) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SyntheticValueSource source && !(bean instanceof CachingSyntheticValueSource)) {
                    return new CachingSyntheticValueSource(source, cluster.getObject(),
                            metrics.getIfAvailable(PrivacyMetrics::none));
                }
                return bean;
            }
        };
    }

    @Bean
    ReidentificationService dataPrismReidentificationService(PrivacyCluster cluster, ApprovalStore approvals,
            AuditRecorder audit, ReidentificationPolicy policy, PrivacyMetrics metrics, Clock clock) {
        return new ReidentificationService(new ScopeIdentityIndex(cluster, metrics), approvals, audit, policy,
                clock);
    }
}
