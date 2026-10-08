package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



/** The scope budget: in-memory for {@code single-node}, cluster-backed for {@code embedded}. */
@Configuration(proxyBeanMethods = false)
class ScopeBudgetWiring {
    /**
     * {@code single-node}, or no topology configured at all (fixture-development,
     * where {@link DataPrismProperties#validate()} never requires one): the budget
     * is enforced once per process. {@code matchIfMissing} covers only the
     * unvalidated dev case — a protected deployment with no topology configured
     * never reaches bean creation, because {@link PropertiesValidation#dataPrismPropertiesValidated}
     * refuses it first with {@code MISSING_CLUSTER_TOPOLOGY}.
     */
    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean({IdentityResolver.class, SecretKeyProvider.class, AuditSink.class, PrivacyMetrics.class, DataSourceAdapter.class})
    @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "single-node", matchIfMissing = true)
    ScopeBudget dataPrismScopeBudget() { return new InMemoryScopeBudget(); }
    /**
     * {@code embedded}: the budget is enforced once across the cluster, not once per process, over
     * the same {@link PrivacyCluster} member as the oversight state ({@link ClusterBackedState}).
     * Declared here, not on that nested class, so its {@code @ConditionalOnBean} is evaluated after
     * {@link PrivacyEngineWiring#dataPrismSecretKeyProvider} is registered, exactly as before. The cluster arrives as an
     * {@link ObjectProvider} so this signature names no optional type: a {@code single-node} consumer
     * without {@code data-prism-hazelcast} never loads {@link ClusterBackedState}.
     */
    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean({IdentityResolver.class, SecretKeyProvider.class, AuditSink.class, PrivacyMetrics.class, DataSourceAdapter.class})
    @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "embedded")
    @ConditionalOnClass(HazelcastInstance.class)
    ScopeBudget dataPrismClusterScopeBudget(ObjectProvider<PrivacyCluster> cluster,
            ConfigurableListableBeanFactory beanFactory) {
        return ClusterBackedState.budgetOver(cluster, beanFactory, "dataPrismClusterScopeBudget");
    }
}
