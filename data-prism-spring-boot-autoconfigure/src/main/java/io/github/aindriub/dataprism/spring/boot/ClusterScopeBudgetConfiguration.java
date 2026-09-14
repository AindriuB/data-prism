package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.hazelcast.HazelcastScopeBudget;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;

/**
 * Builds the {@code topology: embedded} {@link ScopeBudget}: a
 * {@link PrivacyCluster} member joined from whatever discovery configuration
 * the deployment supplies through Hazelcast's own configuration mechanism
 * (a {@code hazelcast.xml}/{@code hazelcast.yaml} or the {@code hazelcast.config}
 * system property — cluster name and discovery are a deployment decision, not
 * something {@code dataprism.hazelcast.*} defaults), wrapped in
 * {@link HazelcastScopeBudget} so the budget is enforced once across every
 * member rather than once per process.
 *
 * <p>{@code com.hazelcast} types are referenced only here and in
 * {@code data-prism-hazelcast} itself, never in {@link DataPrismAutoConfiguration}'s
 * own signatures. That is deliberate: the {@code @ConditionalOnClass} guard on
 * the bean method that calls {@link #build} is what keeps this class from ever
 * being loaded — and these optional classes from ever being resolved — on a
 * {@code single-node} consumer that never put {@code data-prism-hazelcast} on
 * its classpath.
 */
final class ClusterScopeBudgetConfiguration {

    private ClusterScopeBudgetConfiguration() {
    }

    static ScopeBudget build(DataPrismProperties.Hazelcast settings) {
        PrivacyCluster cluster = PrivacyCluster.embedded(new Config(), settings.isReidentificationEnabled());
        return new HazelcastScopeBudget(cluster);
    }
}
