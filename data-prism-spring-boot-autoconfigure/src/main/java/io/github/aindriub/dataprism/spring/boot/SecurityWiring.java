package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Authorization, purpose and scope resolution. Case authorisation and the client allowlist are explicit
 * decisions: a {@link CaseAuthorizer} bean or {@code dataprism.security-policy.case-authorisation=token-trusted},
 * and a {@link ClientAllowlist} bean, {@code dataprism.security-policy.allowed-clients} or
 * {@code dataprism.security-policy.client-allowlist=unrestricted}. Neither has a default; a setting together with
 * an application bean is refused before any bean exists (see {@link Preflights}), and again by
 * {@link BypassCheck} on the real instances, which catches a bean declared as another type.
 *
 * <p>The default {@link ScopeResolver} and {@link AuthorizationService} take the deployer's bean through the one
 * injection Spring itself uses, so none, many or a non-candidate is Spring's to refuse: two beans fail with
 * {@link NoUniqueBeanDefinitionException} unless one is {@code @Primary}, and no injectable bean is refused here
 * ({@code MISSING_CASE_AUTHORIZER}, {@code MISSING_CLIENT_ALLOWLIST}).
 *
 * <p>What is checked after that is the outcome ({@link BypassCheck}): the bean Spring would inject must be a
 * singleton (or a scoped proxy), and every {@link ScopeResolver} and {@link AuthorizationService} in the context
 * and its ancestors must hold it, or startup is refused ({@code CASE_AUTHORIZER_BYPASSED},
 * {@code CLIENT_ALLOWLIST_BYPASSED}). The test is identity for the authoriser ({@link ScopeResolver#usesCaseAuthorizer})
 * and value equality for the allowlist ({@link AuthorizationService#usesClientAllowlist}), so a service shared
 * from a parent context that holds an equal allowlist passes. An application that replaces either with one built around
 * another authoriser or allowlist is refused; one built with the injected bean starts. A request- or
 * session-scoped {@link CaseAuthorizer} behind a scoped proxy is supported, because the proxy is a singleton.
 *
 * <p>Limit: the check inspects {@link ScopeResolver} and {@link AuthorizationService} beans. An application
 * {@code HttpTransport} built by hand around its own resolver is not inspected, because {@code HttpTransport}
 * exposes none.
 */
@Configuration(proxyBeanMethods = false)
class SecurityWiring {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityWiring.class);

    private static final String CASE_AUTHORIZER_BEAN = "dataPrismCaseAuthorizer";
    private static final String CLIENT_ALLOWLIST_BEAN = "dataPrismClientAllowlist";
    private static final String CASE_SETTING = "dataprism.security-policy.case-authorisation";
    private static final String ALLOWED_CLIENTS_SETTING = "dataprism.security-policy.allowed-clients";
    private static final String CLIENT_ALLOWLIST_SETTING = "dataprism.security-policy.client-allowlist";

    /**
     * Once every singleton exists, checks the outcome rather than the wiring: the {@link CaseAuthorizer} and
     * {@link ClientAllowlist} that Spring injects must be held by every {@link ScopeResolver} or
     * {@link AuthorizationService} in the context, the defaults included. It compares what each holds, not names, so a
     * same-named or FactoryBean-produced replacement is seen as itself; names decide only whether the settings'
     * own bean is the one resolved.
     */
    static final class BypassCheck implements SmartInitializingSingleton {
        private final ConfigurableListableBeanFactory beanFactory;
        private final Environment environment;

        BypassCheck(ConfigurableListableBeanFactory beanFactory, Environment environment) {
            this.beanFactory = beanFactory;
            this.environment = environment;
        }

        @Override
        public void afterSingletonsInstantiated() {
            NamedBeanHolder<CaseAuthorizer> authorizer = resolve(CaseAuthorizer.class, "MISSING_CASE_AUTHORIZER",
                    "no CaseAuthorizer bean is available, so a ScopeResolver that skips the default has none to "
                            + "consult; supply a CaseAuthorizer bean, or set " + CASE_SETTING + "=token-trusted");
            SecurityPolicyProperties decisions = SecurityPolicyProperties.decisionsFrom(environment);
            if (decisions.tokenTrusted()
                    && !isOnlyInstance(authorizer, CaseAuthorizer.class, CASE_AUTHORIZER_BEAN)) {
                throw new DataPrismConfigurationException("CASE_AUTHORISATION_CONFLICT",
                        CASE_SETTING + "=token-trusted and an application CaseAuthorizer are both present; remove "
                                + "the setting to use the bean, or remove the bean");
            }
            requireSingleton(authorizer.getBeanName(), "CASE_AUTHORIZER_BYPASSED", "CaseAuthorizer");
            if (bypasses(all(ScopeResolver.class), resolver -> resolver.usesCaseAuthorizer(authorizer.getBeanInstance()))) {
                throw new DataPrismConfigurationException("CASE_AUTHORIZER_BYPASSED",
                        "a ScopeResolver bean replaces the default and is not built with the CaseAuthorizer bean, "
                                + "so that bean would never be consulted; construct your ScopeResolver with the "
                                + "injected CaseAuthorizer bean, or remove your ScopeResolver");
            }
            NamedBeanHolder<ClientAllowlist> allowlist = resolve(ClientAllowlist.class, "MISSING_CLIENT_ALLOWLIST",
                    "no ClientAllowlist bean is available, so an AuthorizationService that skips the default has "
                            + "none to consult; supply a ClientAllowlist bean, or set " + ALLOWED_CLIENTS_SETTING
                            + " or " + CLIENT_ALLOWLIST_SETTING + "=unrestricted");
            if (decisions.clientAllowlistSet()
                    && !isOnlyInstance(allowlist, ClientAllowlist.class, CLIENT_ALLOWLIST_BEAN)) {
                throw new DataPrismConfigurationException("CLIENT_ALLOWLIST_CONFLICT",
                        ALLOWED_CLIENTS_SETTING + " or " + CLIENT_ALLOWLIST_SETTING + " and an application "
                                + "ClientAllowlist are both present; remove the setting to use the bean, or "
                                + "remove the bean");
            }
            requireSingleton(allowlist.getBeanName(), "CLIENT_ALLOWLIST_BYPASSED", "ClientAllowlist");
            if (bypasses(all(AuthorizationService.class), service -> service.usesClientAllowlist(allowlist.getBeanInstance()))) {
                throw new DataPrismConfigurationException("CLIENT_ALLOWLIST_BYPASSED",
                        "an AuthorizationService bean replaces the default and is not built with the ClientAllowlist "
                                + "bean, so that bean would never be consulted; construct your AuthorizationService "
                                + "with the injected ClientAllowlist bean, or remove your AuthorizationService");
            }
        }

        /**
         * The bean Spring injects for the type, ancestors included: the same resolution as the defaults'
         * injection. Anything but "no injectable bean" is Spring's own error and propagates as raised.
         */
        private <T> NamedBeanHolder<T> resolve(Class<T> type, String missingCode, String missingMessage) {
            try {
                return beanFactory.resolveNamedBean(type);
            } catch (NoUniqueBeanDefinitionException e) {
                throw e;
            } catch (NoSuchBeanDefinitionException e) {
                throw new DataPrismConfigurationException(missingCode, missingMessage);
            }
        }

        /**
         * When the settings define the bean, the resolved bean must be theirs and no other already-created
         * singleton of the type may exist in this context or an ancestor (a bean of the settings' own name in an
         * ancestor is theirs too). Beans are judged by declared type ({@code getType}, which for a FactoryBean
         * is {@code getObjectType()}), so nothing is created to be looked at; this catches a bean declared as
         * another type that really is one. Only a bean whose type is unknown is created. A FactoryBean that
         * misreports its type is not caught here, which is harmless: Spring injects by the same declared type,
         * so its product is never injected by type, and wiring it by name is caught as BYPASSED. Accepted
         * residual: a FactoryBean with a null object type has its product created whenever a setting is in
         * use; if its {@code getObject()} throws, startup fails with that exception (fail closed).
         * A bean named {@code dataPrismCaseAuthorizer} or {@code dataPrismClientAllowlist} in an ancestor context
         * is treated as the settings' own bean, the same name exemption {@code Preflights.hasOtherBean} applies.
         */
        private <T> boolean isOnlyInstance(NamedBeanHolder<T> resolved, Class<T> type, String ownName) {
            if (!ownName.equals(resolved.getBeanName())) {
                return false;
            }
            BeanFactory current = beanFactory;
            while (current != null) {
                BeanFactory factory = current instanceof ConfigurableApplicationContext c
                        ? c.getBeanFactory() : current;
                if (factory instanceof ConfigurableListableBeanFactory configurable) {
                    for (String name : configurable.getBeanDefinitionNames()) {
                        if (name.equals(ownName) || !(configurable.containsSingleton(name)
                                || configurable.containsSingleton("&" + name))) {
                            continue;
                        }
                        Class<?> declared = configurable.getType(name);
                        // Only a bean whose type is unknown is created to be looked at; a known type is judged
                        // without running a FactoryBean's getObject().
                        if (declared != null ? type.isAssignableFrom(declared)
                                : type.isInstance(configurable.getBean(name))) {
                            return false;
                        }
                    }
                }
                current = factory instanceof HierarchicalBeanFactory h
                        ? h.getParentBeanFactory() : null;
            }
            return true;
        }

        private void requireSingleton(String name, String code, String simpleName) {
            if (!beanFactory.isSingleton(name)) {
                throw new DataPrismConfigurationException(code, "the " + simpleName + " bean is not a singleton, "
                        + "so no holder can be shown to hold it; define it as a singleton (or a scoped proxy)");
            }
        }

        /** Every bean of the type, this context and its ancestors. */
        private <T> Collection<T> all(Class<T> type) {
            return BeanFactoryUtils.beansOfTypeIncludingAncestors(beanFactory, type, true, true).values();
        }
    }

    @Lazy(false)
    @Bean
    static BypassCheck dataPrismSecurityBypassCheck(ConfigurableListableBeanFactory beanFactory,
            Environment environment) {
        return new BypassCheck(beanFactory, environment);
    }

    /**
     * True when any instance, ours included, does not use the deployer's bean. This is a consistency check on
     * the outcome: whatever the injection decided, the resolver or service in use must hold the deployer's bean.
     */
    private static <T> boolean bypasses(Collection<T> instances, Predicate<T> usesDeployersBean) {
        return instances.stream().anyMatch(instance -> !usesDeployersBean.test(instance));
    }

    /** True when the {@code allowed-clients} or {@code client-allowlist} setting defines the allowlist. */
    static final class ClientAllowlistSettings implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return SecurityPolicyProperties.decisionsFrom(context.getEnvironment()).clientAllowlistSet();
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "dataprism.security-policy", name = "case-authorisation",
            havingValue = SecurityPolicyProperties.TOKEN_TRUSTED)
    CaseAuthorizer dataPrismCaseAuthorizer() {
        LOG.warn("{}={}: the case claim in the token is trusted without assignment checks. Supply a CaseAuthorizer "
                + "bean instead of this setting for a real deployment.", CASE_SETTING,
                SecurityPolicyProperties.TOKEN_TRUSTED);
        return CaseAuthorizer.tokenTrusted();
    }

    @Bean
    @Conditional(ClientAllowlistSettings.class)
    ClientAllowlist dataPrismClientAllowlist(DataPrismProperties properties) {
        SecurityPolicyProperties policy = properties.getSecurityPolicy();
        if (policy.unrestricted()) {
            LOG.warn("{}={}: every client is allowed. List the allowed clients in {}, or supply a ClientAllowlist "
                    + "bean, for a real deployment.", CLIENT_ALLOWLIST_SETTING,
                    SecurityPolicyProperties.UNRESTRICTED, ALLOWED_CLIENTS_SETTING);
            return ClientAllowlist.unrestricted();
        }
        return ClientAllowlist.of(Set.copyOf(policy.getAllowedClients()));
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityPolicy dataPrismSecurityPolicy(DataPrismProperties properties) {
        Set<String> purposes = Set.copyOf(properties.getSecurityPolicy().getPurposes());
        Map<String, Set<String>> roles = properties.getSecurityPolicy().getRoles().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        return new SecurityPolicy(purposes, roles);
    }

    @Bean
    @ConditionalOnMissingBean
    AuthorizationService dataPrismAuthorizationService(SecurityPolicy policy, DataPrismProperties properties,
            ObjectProvider<ClientAllowlist> allowlist) {
        ClientAllowlist clients;
        try {
            clients = allowlist.getObject();
        } catch (NoUniqueBeanDefinitionException e) {
            throw e;
        } catch (NoSuchBeanDefinitionException e) {
            throw new DataPrismConfigurationException("MISSING_CLIENT_ALLOWLIST",
                    "no ClientAllowlist bean is available; supply a ClientAllowlist bean, or set "
                            + ALLOWED_CLIENTS_SETTING + " or " + CLIENT_ALLOWLIST_SETTING + "=unrestricted");
        }
        return new AuthorizationService(policy, properties.getPrivacy().getProfile(),
                PrivacyScopeType.INVESTIGATION, clients);
    }

    @Bean
    @ConditionalOnMissingBean
    ScopeResolver dataPrismScopeResolver(PseudonymisationVersion version, DataPrismProperties properties,
            ObjectProvider<CaseAuthorizer> caseAuthorizer) {
        CaseAuthorizer authorizer;
        try {
            authorizer = caseAuthorizer.getObject();
        } catch (NoUniqueBeanDefinitionException e) {
            throw e;
        } catch (NoSuchBeanDefinitionException e) {
            throw new DataPrismConfigurationException("MISSING_CASE_AUTHORIZER",
                    "no CaseAuthorizer bean is available; supply a CaseAuthorizer bean, or set "
                            + CASE_SETTING + "=token-trusted");
        }
        PurposeValidator purposes = new PurposeValidator(Set.copyOf(properties.getSecurityPolicy().getPurposes()));
        return new ScopeResolver(version, properties.getPrivacy().getScopeLifetime(), purposes, authorizer);
    }
}
