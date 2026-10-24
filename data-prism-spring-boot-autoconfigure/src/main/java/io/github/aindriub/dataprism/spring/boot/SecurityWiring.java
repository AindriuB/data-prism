package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * Authorization, purpose and scope resolution. A deployer's own {@link CaseAuthorizer} and
 * {@link ClientAllowlist} bean is used when present; more than one of either refuses startup.
 */
@Configuration(proxyBeanMethods = false)
class SecurityWiring {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityWiring.class);

    @Bean @ConditionalOnMissingBean
    SecurityPolicy dataPrismSecurityPolicy(DataPrismProperties properties) {
        return new SecurityPolicy(Set.copyOf(properties.getSecurityPolicy().getPurposes()), properties.getSecurityPolicy().getRoles().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }
    @Bean @ConditionalOnMissingBean
    AuthorizationService dataPrismAuthorizationService(SecurityPolicy policy, DataPrismProperties properties, ObjectProvider<ClientAllowlist> allowlist) { return new AuthorizationService(policy, properties.getPrivacy().getProfile(), io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION, allowlist.getIfAvailable(ClientAllowlist::unrestricted)); }
    @Bean @ConditionalOnMissingBean
    ScopeResolver dataPrismScopeResolver(PseudonymisationVersion version, DataPrismProperties properties, ObjectProvider<CaseAuthorizer> caseAuthorizer) {
        CaseAuthorizer authorizer = caseAuthorizer.getIfAvailable();
        if (authorizer == null) {
            LOG.warn("No CaseAuthorizer bean is defined: the case claim in the token is trusted without assignment checks. Supply a CaseAuthorizer bean.");
            authorizer = CaseAuthorizer.tokenTrusted();
        }
        return new ScopeResolver(version, properties.getPrivacy().getScopeLifetime(), new PurposeValidator(Set.copyOf(properties.getSecurityPolicy().getPurposes())), authorizer); }
}
