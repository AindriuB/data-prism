package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Oversight state, approvals, rate limiting and admission, plus the parameter fingerprinter that
 * admission and the orchestrator share.
 */
@Configuration(proxyBeanMethods = false)
class OversightWiring {
    /**
     * Oversight state when no cluster supplied it: per process, honestly. Declared on the outer
     * class, so it is registered after {@link ClusterBackedState}'s and yields to it.
     */
    @Bean @ConditionalOnMissingBean
    OversightState dataPrismOversightState() { return new InMemoryOversightState(); }
    @Bean @ConditionalOnMissingBean
    ApprovalStore dataPrismApprovalStore() { return new InMemoryApprovalStore(); }
    @Bean @ConditionalOnMissingBean
    CallerRateLimiter dataPrismCallerRateLimiter() { return new InMemoryCallerRateLimiter(); }
    @Bean
    OversightPolicy dataPrismOversightPolicy(DataPrismProperties properties) {
        OversightProperties o = properties.getOversight();
        Integer requests = o.getCallerRateLimit().getRequests();
        return new OversightPolicy(Set.copyOf(o.getApprovalRequiredTools()),
                requests == null ? OptionalInt.empty() : OptionalInt.of(requests),
                o.getCallerRateLimit().getWindow(), o.getApprovalTtl(), o.getMaxPendingPerRequester());
    }
    /**
     * Always built, never optional: the MCP server is given this admission unconditionally, so a
     * pause or an approval requirement can never be silently skipped by a missing bean.
     */
    @Bean
    ToolAdmission dataPrismToolAdmission(OversightState state, ApprovalStore approvals, CallerRateLimiter limiter,
            OversightPolicy policy, Clock clock) {
        return new ToolAdmission(state, approvals, limiter, policy, clock);
    }
    @Bean @ConditionalOnMissingBean
    ParameterFingerprinter dataPrismParameterFingerprinter(SecretKeyProvider keys) {
        return new ParameterFingerprinter(keys);
    }
}
