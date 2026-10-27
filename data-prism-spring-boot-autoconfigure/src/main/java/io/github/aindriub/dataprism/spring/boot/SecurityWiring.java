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
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authorization, purpose and scope resolution. A deployer's own {@link CaseAuthorizer} and
 * {@link ClientAllowlist} bean is used when present; more than one of either refuses startup
 * ({@code AMBIGUOUS_CASE_AUTHORIZER}, {@code AMBIGUOUS_CLIENT_ALLOWLIST}), as does one that is not a singleton
 * ({@code NON_SINGLETON_CASE_AUTHORIZER}, {@code NON_SINGLETON_CLIENT_ALLOWLIST}), checked before anything else
 * and whoever built the resolver or service. Beans in a parent context count. An
 * application that replaces {@link ScopeResolver} while a {@link CaseAuthorizer} bean exists, or
 * {@link AuthorizationService} while a {@link ClientAllowlist} bean exists, without building it with
 * that bean, would silently bypass the deployer's check, so startup refuses
 * ({@code CASE_AUTHORIZER_BYPASSED}, {@code CLIENT_ALLOWLIST_BYPASSED}). The test is identity:
 * {@link ScopeResolver#usesCaseAuthorizer} and {@link AuthorizationService#usesClientAllowlist}.
 *
 * <p>Supported custom configuration: define the {@link CaseAuthorizer} bean and a {@link ScopeResolver}
 * bean constructed with that injected bean (likewise {@link ClientAllowlist} and
 * {@link AuthorizationService}). It starts in every version, including 0.8.0 and later, where the
 * {@link CaseAuthorizer} bean is required. A resolver built with another authorizer or
 * {@code CaseAuthorizer.tokenTrusted()} is refused while the bean exists. A request- or session-scoped
 * {@link CaseAuthorizer} or {@link ClientAllowlist} behind a scoped proxy is supported: the proxy is the bean,
 * its {@code scopedTarget.*} twin is not counted, and the proxy is a singleton, so the identity checks hold.
 *
 * <p>The token-trusted {@link CaseAuthorizer} fallback (no bean) is for development only. From version
 * 0.8.0 on, a build refuses to start with it ({@code MISSING_CASE_AUTHORIZER}), whoever built the
 * {@link ScopeResolver}. The version is embedded at build time in {@code META-INF/data-prism/version.properties}
 * and read from this library's own class loader resource; if it is missing, present more than once on the
 * class path, or unparseable it counts as a release. Only an explicit {@code -SNAPSHOT} or a release below
 * 0.8.0 keeps the fallback, with its WARN.
 */
@Configuration(proxyBeanMethods = false)
class SecurityWiring {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityWiring.class);
    private static final Pattern VERSION = Pattern.compile("^(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(-.+)?$");
    private static final String VERSION_RESOURCE = "META-INF/data-prism/version.properties";

    /** Where the running library's version comes from; a bean of this package-private type lets a test drive it. */
    @FunctionalInterface
    interface LibraryVersion {
        /** The version, or null when unknown. */
        String get();
    }

    /**
     * The version embedded at build time in this library's own resource; null when missing, unreadable or
     * present more than once on the class path (another file of that name could decide the gate).
     */
    static String embeddedVersion() {
        return embeddedVersion(SecurityWiring.class.getClassLoader());
    }

    static String embeddedVersion(ClassLoader loader) {
        try {
            if (loader == null) {
                return null;
            }
            List<URL> urls = Collections.list(loader.getResources(VERSION_RESOURCE));
            if (urls.size() != 1) {
                return null;
            }
            try (InputStream in = urls.get(0).openStream()) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                return version == null ? null : version.strip();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * True unless the version is a parseable explicit {@code -SNAPSHOT}, or a release below 0.8.0. A null,
     * unparseable or oversized version is forbidden: fail closed.
     */
    static boolean fallbackForbidden(String version) {
        if (version == null) {
            return true;
        }
        Matcher m = VERSION.matcher(version);
        if (!m.matches()) {
            return true;
        }
        if ("-SNAPSHOT".equals(m.group(4))) {
            return false;
        }
        return Integer.parseInt(m.group(1)) > 0 || Integer.parseInt(m.group(2)) >= 8;
    }

    /**
     * Remembers the instances this auto-configuration built and, once every singleton exists,
     * compares instances rather than names, so a same-named or FactoryBean-produced replacement
     * is seen as itself.
     */
    static final class BypassCheck implements SmartInitializingSingleton {
        private final ListableBeanFactory beanFactory;
        private final ObjectProvider<LibraryVersion> libraryVersion;
        private volatile ScopeResolver scopeResolver;
        private volatile AuthorizationService authorizationService;

        BypassCheck(ListableBeanFactory beanFactory, ObjectProvider<LibraryVersion> libraryVersion) {
            this.beanFactory = beanFactory;
            this.libraryVersion = libraryVersion;
        }

        @Override
        public void afterSingletonsInstantiated() {
            Map<String, CaseAuthorizer> authorizers = lookup(CaseAuthorizer.class, "CASE_AUTHORIZER", "CaseAuthorizer",
                    "which check applies");
            Map<String, ClientAllowlist> allowlists = lookup(ClientAllowlist.class, "CLIENT_ALLOWLIST",
                    "ClientAllowlist", "which allowlist applies");
            if (authorizers.isEmpty()
                    && fallbackForbidden(libraryVersion.getIfAvailable(() -> SecurityWiring::embeddedVersion).get())) {
                throw new DataPrismConfigurationException("MISSING_CASE_AUTHORIZER",
                        "a CaseAuthorizer bean is required from 0.8.0; the token-trusted fallback is not "
                                + "available in a release");
            }
            Collection<CaseAuthorizer> authorizerBeans = authorizers.values();
            if (!authorizerBeans.isEmpty() && bypasses(all(ScopeResolver.class), scopeResolver,
                    resolver -> authorizerBeans.stream().anyMatch(resolver::usesCaseAuthorizer))) {
                throw new DataPrismConfigurationException("CASE_AUTHORIZER_BYPASSED",
                        "a ScopeResolver bean replaces the default and is not built with the CaseAuthorizer bean, "
                                + "so that bean would never be consulted; construct your ScopeResolver with the "
                                + "injected CaseAuthorizer bean, or remove your ScopeResolver");
            }
            Collection<ClientAllowlist> allowlistBeans = allowlists.values();
            if (!allowlistBeans.isEmpty() && bypasses(all(AuthorizationService.class), authorizationService,
                    service -> allowlistBeans.stream().anyMatch(service::usesClientAllowlist))) {
                throw new DataPrismConfigurationException("CLIENT_ALLOWLIST_BYPASSED",
                        "an AuthorizationService bean replaces the default and is not built with the ClientAllowlist "
                                + "bean, so that bean would never be consulted; construct your AuthorizationService "
                                + "with the injected ClientAllowlist bean, or remove your AuthorizationService");
            }
        }

        /** Every bean of the type, this context and its ancestors, as the defaults' unique lookups see them. */
        private <T> Collection<T> all(Class<T> type) {
            return BeanFactoryUtils.beansOfTypeIncludingAncestors(beanFactory, type, true, true).values();
        }

        /**
         * One lookup for a deployer-supplied type: refuses more than one bean, and any bean that is not a
         * singleton (an identity check cannot hold for a prototype or scoped bean).
         */
        private <T> Map<String, T> lookup(Class<T> type, String codeStem, String simpleName, String applies) {
            // A scoped proxy is the bean the defaults inject; its scopedTarget.* twin is not a candidate.
            String[] names = Arrays.stream(
                    BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, type, true, true))
                    .filter(name -> !ScopedProxyUtils.isScopedTarget(name)).toArray(String[]::new);
            if (names.length > 1) {
                throw new DataPrismConfigurationException("AMBIGUOUS_" + codeStem,
                        "more than one " + simpleName + " bean is defined; define exactly one, so that it is "
                                + "unambiguous " + applies);
            }
            for (String name : names) {
                if (!beanFactory.isSingleton(name)) {
                    throw new DataPrismConfigurationException("NON_SINGLETON_" + codeStem,
                            "the " + simpleName + " bean is not a singleton; define it as a singleton, so that "
                                    + "the check can be compared by identity");
                }
            }
            Map<String, T> beans = new LinkedHashMap<>();
            for (String name : names) {
                beans.put(name, beanFactory.getBean(name, type));
            }
            return beans;
        }
    }

    @Lazy(false)
    @Bean
    static BypassCheck dataPrismSecurityBypassCheck(ListableBeanFactory beanFactory,
            ObjectProvider<LibraryVersion> libraryVersion) {
        return new BypassCheck(beanFactory, libraryVersion);
    }

    /**
     * True when any instance is not our default (looking through AOP proxies, nested ones too, to the target)
     * and does not use the deployer's bean.
     */
    private static <T> boolean bypasses(Collection<T> instances, Object ours, Predicate<T> usesDeployersBean) {
        return instances.stream().anyMatch(instance -> unwrap(instance) != ours && !usesDeployersBean.test(instance));
    }

    /**
     * Follows singleton AOP targets to the innermost object. Defensive: {@link ScopeResolver} and
     * {@link AuthorizationService} are final, so no proxy can currently wrap them and only a non-proxy
     * replacement can reach {@link #replaced}; this keeps the check correct if either stops being final.
     */
    static Object unwrap(Object bean) {
        Object current = bean;
        while (true) {
            Object target = AopProxyUtils.getSingletonTarget(current);
            if (target == null || target == current) {
                return current;
            }
            current = target;
        }
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
            ObjectProvider<ClientAllowlist> allowlist, BypassCheck built) {
        ClientAllowlist clients = allowlist.getIfUnique();
        if (clients == null) {
            LOG.warn("No ClientAllowlist bean is defined: every client is allowed. Supply a ClientAllowlist bean.");
            clients = ClientAllowlist.unrestricted();
        }
        AuthorizationService service = new AuthorizationService(policy, properties.getPrivacy().getProfile(),
                PrivacyScopeType.INVESTIGATION, clients);
        built.authorizationService = service;
        return service;
    }

    @Bean
    @ConditionalOnMissingBean
    ScopeResolver dataPrismScopeResolver(PseudonymisationVersion version, DataPrismProperties properties,
            ObjectProvider<CaseAuthorizer> caseAuthorizer, BypassCheck built) {
        CaseAuthorizer authorizer = caseAuthorizer.getIfUnique();
        if (authorizer == null) {
            LOG.warn("No CaseAuthorizer bean is defined: the case claim in the token is trusted without "
                    + "assignment checks. Supply a CaseAuthorizer bean.");
            authorizer = CaseAuthorizer.tokenTrusted();
        }
        PurposeValidator purposes = new PurposeValidator(Set.copyOf(properties.getSecurityPolicy().getPurposes()));
        ScopeResolver resolver = new ScopeResolver(version, properties.getPrivacy().getScopeLifetime(), purposes,
                authorizer);
        built.scopeResolver = resolver;
        return resolver;
    }
}
