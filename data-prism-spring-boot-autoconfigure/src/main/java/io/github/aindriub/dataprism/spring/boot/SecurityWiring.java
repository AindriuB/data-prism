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
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authorization, purpose and scope resolution. A deployer's own {@link CaseAuthorizer} and
 * {@link ClientAllowlist} bean is used when present; more than one of either refuses startup. An
 * application that replaces {@link ScopeResolver} while a {@link CaseAuthorizer} bean exists, or
 * {@link AuthorizationService} while a {@link ClientAllowlist} bean exists, would silently bypass
 * the deployer's check, so startup refuses ({@code CASE_AUTHORIZER_BYPASSED},
 * {@code CLIENT_ALLOWLIST_BYPASSED}).
 *
 * <p>The token-trusted {@link CaseAuthorizer} fallback (no bean) is for development only. From version
 * 0.8.0 on, a build refuses to start with it ({@code MISSING_CASE_AUTHORIZER}), whoever built the
 * {@link ScopeResolver}. The version is embedded at build time in {@code META-INF/data-prism/version.properties}
 * and read from this library's own class loader resource; if it is missing or unparseable it counts as a
 * release. Only an explicit {@code -SNAPSHOT} or a release below 0.8.0 keeps the fallback, with its WARN.
 */
@Configuration(proxyBeanMethods = false)
class SecurityWiring {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityWiring.class);
    private static final Pattern VERSION = Pattern.compile("^(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(-.+)?$");
    private static final String VERSION_RESOURCE = "/META-INF/data-prism/version.properties";

    /** Where the running library's version comes from; a bean of this package-private type lets a test drive it. */
    @FunctionalInterface
    interface LibraryVersion {
        /** The version, or null when unknown. */
        String get();
    }

    /** The version embedded at build time in this library's own resource; null when missing or unreadable. */
    static String embeddedVersion() {
        try (InputStream in = SecurityWiring.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            return version == null ? null : version.strip();
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
        private volatile ScopeResolver scopeResolver;
        private volatile AuthorizationService authorizationService;

        private final ObjectProvider<LibraryVersion> libraryVersion;

        BypassCheck(ListableBeanFactory beanFactory, ObjectProvider<LibraryVersion> libraryVersion) {
            this.beanFactory = beanFactory;
            this.libraryVersion = libraryVersion;
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (beanFactory.getBeansOfType(CaseAuthorizer.class, true, true).isEmpty()
                    && fallbackForbidden(libraryVersion.getIfAvailable(() -> SecurityWiring::embeddedVersion).get())) {
                throw new DataPrismConfigurationException("MISSING_CASE_AUTHORIZER",
                        "a CaseAuthorizer bean is required from 0.8.0; the token-trusted fallback is not "
                                + "available in a release");
            }
            if (replaced(beanFactory.getBeansOfType(ScopeResolver.class, true, true).values(), scopeResolver)
                    && !beanFactory.getBeansOfType(CaseAuthorizer.class, true, true).isEmpty()) {
                throw new DataPrismConfigurationException("CASE_AUTHORIZER_BYPASSED",
                        "a ScopeResolver bean replaces the default, so the CaseAuthorizer bean would never be "
                                + "consulted; call the CaseAuthorizer from your ScopeResolver and remove the bean, "
                                + "or remove your ScopeResolver");
            }
            if (replaced(beanFactory.getBeansOfType(AuthorizationService.class, true, true).values(),
                    authorizationService)
                    && !beanFactory.getBeansOfType(ClientAllowlist.class, true, true).isEmpty()) {
                throw new DataPrismConfigurationException("CLIENT_ALLOWLIST_BYPASSED",
                        "an AuthorizationService bean replaces the default, so the ClientAllowlist bean would never "
                                + "be consulted; apply the ClientAllowlist in your AuthorizationService and remove "
                                + "the bean, or remove your AuthorizationService");
            }
        }
    }

    @Bean
    static BypassCheck dataPrismSecurityBypassCheck(ListableBeanFactory beanFactory,
            ObjectProvider<LibraryVersion> libraryVersion) {
        return new BypassCheck(beanFactory, libraryVersion);
    }

    /** True when any instance is not our default, looking through AOP proxies (nested ones too) to the target. */
    private static boolean replaced(Collection<?> instances, Object ours) {
        return instances.stream().anyMatch(instance -> unwrap(instance) != ours);
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
        ClientAllowlist clients = allowlist.getIfAvailable();
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
        CaseAuthorizer authorizer = caseAuthorizer.getIfAvailable();
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
