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
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * Authorization, purpose and scope resolution. A deployer's own {@link CaseAuthorizer} and
 * {@link ClientAllowlist} bean is used when present; more than one of either refuses startup. An
 * application that replaces {@link ScopeResolver} while a {@link CaseAuthorizer} bean exists, or
 * {@link AuthorizationService} while a {@link ClientAllowlist} bean exists, would silently bypass
 * the deployer's check, so startup refuses ({@code CASE_AUTHORIZER_BYPASSED},
 * {@code CLIENT_ALLOWLIST_BYPASSED}).
 */
@Configuration(proxyBeanMethods = false)
class SecurityWiring {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityWiring.class);

    /**
     * Remembers the instances this auto-configuration built and, once every singleton exists,
     * compares instances rather than names, so a same-named or FactoryBean-produced replacement
     * is seen as itself.
     */
    static final class BypassCheck implements SmartInitializingSingleton {
        private final ListableBeanFactory beanFactory;
        private volatile ScopeResolver scopeResolver;
        private volatile AuthorizationService authorizationService;

        BypassCheck(ListableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (replaced(beanFactory.getBeansOfType(ScopeResolver.class, true, true).values(), scopeResolver)
                    && !beanFactory.getBeansOfType(CaseAuthorizer.class, true, true).isEmpty()) {
                throw new DataPrismConfigurationException("CASE_AUTHORIZER_BYPASSED",
                        "a ScopeResolver bean replaces the default, so the CaseAuthorizer bean would never be consulted; "
                                + "call the CaseAuthorizer from your ScopeResolver and remove the bean, or remove your ScopeResolver");
            }
            if (replaced(beanFactory.getBeansOfType(AuthorizationService.class, true, true).values(), authorizationService)
                    && !beanFactory.getBeansOfType(ClientAllowlist.class, true, true).isEmpty()) {
                throw new DataPrismConfigurationException("CLIENT_ALLOWLIST_BYPASSED",
                        "an AuthorizationService bean replaces the default, so the ClientAllowlist bean would never be consulted; "
                                + "apply the ClientAllowlist in your AuthorizationService and remove the bean, or remove your AuthorizationService");
            }
        }
    }

    @Bean
    static BypassCheck dataPrismSecurityBypassCheck(ListableBeanFactory beanFactory) {
        return new BypassCheck(beanFactory);
    }

    private static boolean replaced(java.util.Collection<?> instances, Object ours) {
        return instances.stream().anyMatch(instance -> instance != ours);
    }

    @Bean @ConditionalOnMissingBean
    SecurityPolicy dataPrismSecurityPolicy(DataPrismProperties properties) {
        return new SecurityPolicy(Set.copyOf(properties.getSecurityPolicy().getPurposes()), properties.getSecurityPolicy().getRoles().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }
    @Bean @ConditionalOnMissingBean
    AuthorizationService dataPrismAuthorizationService(SecurityPolicy policy, DataPrismProperties properties, ObjectProvider<ClientAllowlist> allowlist, BypassCheck built) {
        ClientAllowlist clients = allowlist.getIfAvailable();
        if (clients == null) {
            LOG.warn("No ClientAllowlist bean is defined: every client is allowed. Supply a ClientAllowlist bean.");
            clients = ClientAllowlist.unrestricted();
        }
        AuthorizationService service = new AuthorizationService(policy, properties.getPrivacy().getProfile(), io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION, clients);
        built.authorizationService = service;
        return service; }
    @Bean @ConditionalOnMissingBean
    ScopeResolver dataPrismScopeResolver(PseudonymisationVersion version, DataPrismProperties properties, ObjectProvider<CaseAuthorizer> caseAuthorizer, BypassCheck built) {
        CaseAuthorizer authorizer = caseAuthorizer.getIfAvailable();
        if (authorizer == null) {
            LOG.warn("No CaseAuthorizer bean is defined: the case claim in the token is trusted without assignment checks. Supply a CaseAuthorizer bean.");
            authorizer = CaseAuthorizer.tokenTrusted();
        }
        ScopeResolver resolver = new ScopeResolver(version, properties.getPrivacy().getScopeLifetime(), new PurposeValidator(Set.copyOf(properties.getSecurityPolicy().getPurposes())), authorizer);
        built.scopeResolver = resolver;
        return resolver; }
}
