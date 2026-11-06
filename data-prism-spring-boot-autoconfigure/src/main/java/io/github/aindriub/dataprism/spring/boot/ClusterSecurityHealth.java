package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.hazelcast.ClusterSecurityStatus;
import io.github.aindriub.dataprism.hazelcast.HazelcastOversightState;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.oversight.OversightState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code clusterSecurity} health contributor (docs/plan/design/cluster-security.md 4.4): {@code DOWN} while this
 * instance is not serving, so a refusing instance leaves rotation. It is placed in the readiness group and not the
 * liveness group, because a restart does not repair a hostile or mismatched cluster. Present only when Spring Boot
 * Actuator is, and imported by {@link ClusterBackedState}, so it exists exactly when the {@code embedded} topology has
 * a {@link PrivacyCluster} bean.
 */
@ConditionalOnClass({HealthIndicator.class, HealthEndpointGroupsPostProcessor.class})
@Configuration(proxyBeanMethods = false)
class ClusterSecurityHealth {
    static final String READINESS = "readiness";
    static final String CONTRIBUTOR = "clusterSecurity";

    /** The bean name minus {@code HealthIndicator} is the contributor name: {@code clusterSecurity}. */
    @Bean
    Contributor clusterSecurityHealthIndicator(ObjectProvider<PrivacyCluster> cluster,
            ObjectProvider<OversightState> oversight) {
        // Resolved on each look, so this bean never forces the cluster to start early.
        return new Contributor(() -> statusOf(cluster.getIfUnique(), oversight.getIfUnique()));
    }

    /**
     * Pause freshness is the oversight state's to report: the cluster's own gate stays open while the document is
     * stale, so recovery can happen. Any other oversight state falls back to the cluster's status. Anything that
     * cannot be read is not serving.
     */
    static ClusterSecurityStatus statusOf(PrivacyCluster cluster, OversightState oversight) {
        try {
            if (oversight instanceof HazelcastOversightState hazelcast) {
                return hazelcast.securityStatus();
            }
            if (cluster != null) {
                return cluster.securityStatus();
            }
        } catch (RuntimeException unreadable) {
            // Falls through: unknown is not serving.
        }
        return new ClusterSecurityStatus(false, ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE, 0, 0);
    }

    /**
     * One object, so the bean inventory gains a single line: the indicator, and the post-processor that adds it to
     * the readiness group (a group that exists only when probes are enabled; none is created here).
     */
    static final class Contributor implements HealthIndicator, HealthEndpointGroupsPostProcessor {
        private final Supplier<ClusterSecurityStatus> status;

        Contributor(Supplier<ClusterSecurityStatus> status) {
            this.status = status;
        }

        @Override
        public Health health() {
            ClusterSecurityStatus s = status.get();
            return (s.serving() ? Health.up() : Health.down())
                    .withDetail("reason", s.reason().name())
                    .withDetail("members", s.memberCount())
                    .withDetail("unverified", s.unverifiedCount())
                    .build();
        }

        @Override
        public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
            if (!groups.getNames().contains(READINESS)) {
                return groups;
            }
            Map<String, HealthEndpointGroup> all = new LinkedHashMap<>();
            for (String name : groups.getNames()) {
                HealthEndpointGroup group = groups.get(name);
                all.put(name, READINESS.equals(name) ? new WithContributor(group) : group);
            }
            return HealthEndpointGroups.of(groups.getPrimary(), all);
        }
    }

    /** A group that also has {@code clusterSecurity} as a member. */
    private record WithContributor(HealthEndpointGroup delegate) implements HealthEndpointGroup {
        @Override public boolean isMember(String name) {
            return CONTRIBUTOR.equals(name) || delegate.isMember(name);
        }
        @Override public boolean showComponents(SecurityContext context) {
            return delegate.showComponents(context);
        }
        @Override public boolean showDetails(SecurityContext context) {
            return delegate.showDetails(context);
        }
        @Override public StatusAggregator getStatusAggregator() {
            return delegate.getStatusAggregator();
        }
        @Override public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return delegate.getHttpCodeStatusMapper();
        }
        @Override public AdditionalHealthEndpointPath getAdditionalPath() {
            return delegate.getAdditionalPath();
        }
    }
}
