package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/** Authorization, purpose and scope resolution. */
@Configuration(proxyBeanMethods = false)
class SecurityWiring {
    @Bean @ConditionalOnMissingBean
    SecurityPolicy dataPrismSecurityPolicy(DataPrismProperties properties) {
        return new SecurityPolicy(Set.copyOf(properties.getSecurityPolicy().getPurposes()), properties.getSecurityPolicy().getRoles().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }
    @Bean @ConditionalOnMissingBean
    AuthorizationService dataPrismAuthorizationService(SecurityPolicy policy, DataPrismProperties properties) { return new AuthorizationService(policy, properties.getPrivacy().getProfile(), io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION); }
    @Bean @ConditionalOnMissingBean
    ScopeResolver dataPrismScopeResolver(PseudonymisationVersion version, DataPrismProperties properties) { return new ScopeResolver(version, properties.getPrivacy().getScopeLifetime(), new PurposeValidator(Set.copyOf(properties.getSecurityPolicy().getPurposes()))); }
}
