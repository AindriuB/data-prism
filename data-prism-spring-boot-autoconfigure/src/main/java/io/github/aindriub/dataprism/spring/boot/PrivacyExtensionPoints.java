package io.github.aindriub.dataprism.spring.boot;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.AbstractApplicationContext;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Checked-in classification of every {@code @Bean} method declared by
 * {@link DataPrismAutoConfiguration}, enforced by the reflection sweep in
 * {@code AutoConfiguredBeanClassificationTest}. Add a row here in the same
 * change that adds a {@code @Bean} method, or that test fails the build —
 * that is the point: the next bean cannot silently reopen the hole a
 * {@link Classification#PRIVACY_CRITICAL} bean is closed against.
 *
 * <p>A {@code PRIVACY_CRITICAL} bean must never carry
 * {@code @ConditionalOnMissingBean}; that annotation is exactly what lets an
 * application replace it without a startup refusal. Instead each one carries
 * a {@link Guard}: {@link Guard#PRIMARY} means the framework bean is
 * {@code @Primary} so it always wins injection even though a competing
 * application bean may also exist ({@code SecretKeyProvider}).
 * {@link Guard#COMPETING_BEAN_REFUSAL} means an application bean can never
 * silently take over — either because the framework refuses startup outright
 * when one is registered ({@code PrivacyPolicyResolver}, see
 * {@code FORBIDDEN_PRIVACY_OVERRIDE}), or because the framework bean is
 * unconditional and collected into a list rather than a single slot, so an
 * application bean can only add to the framework's, never exclude it
 * ({@code LlmResponseValidator}). Both are proven, not merely declared, by
 * the behavioural tests in {@code PrivacyExtensionPointsTest}.
 */
public final class PrivacyExtensionPoints {

    public enum Classification { REPLACEABLE, PRIVACY_CRITICAL }

    public enum Guard { NONE, PRIMARY, COMPETING_BEAN_REFUSAL }

    public record BeanContract(Classification classification, Guard guard) {
        public BeanContract {
            if (classification == Classification.PRIVACY_CRITICAL && guard == Guard.NONE) {
                throw new IllegalArgumentException("a PRIVACY_CRITICAL bean must declare a guard");
            }
        }
    }

    private static final Map<String, BeanContract> BEANS = Map.ofEntries(
            entry("dataPrismIdentityResolverPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismReidentificationModulePreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismPropertiesValidated", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismContractValidator", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismClock", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismFieldMetadataResolver", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismVocabulary", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismPseudonymisationVersion", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismSyntheticValueSource", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismValueTokenSource", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismSecretKeyProvider", Classification.PRIVACY_CRITICAL, Guard.PRIMARY),
            entry("dataPrismPrivacyPolicyResolver", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismPrivacyPolicyResolverPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismScrubber", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismRawValueLeakValidator", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismSensitivePatternValidator", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismSecurityBypassCheck", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismSecurityPolicyPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismCaseAuthorizer", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismClientAllowlist", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismSecurityPolicy", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuthorizationService", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismScopeResolver", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuditRecorder", Classification.REPLACEABLE, Guard.NONE),
            // Applications add AuditEventListener beans, collected into the list this bean owns, and cannot
            // replace the dispatcher (D-163-C): it keeps listeners after the write, off the call, and isolated.
            entry("dataPrismAuditEventListeners", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismSlf4jAuditSink", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismHashChainedAuditSink", Classification.REPLACEABLE, Guard.NONE),
            // The JSON projection writes audit-derived output; it cannot be replaced (D-0.6-8): its type is
            // package-private and final, and a same-named bean is refused as a bean-definition override.
            entry("dataPrismJsonAuditProjection", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismJsonAuditProjectionPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuditCheckpointSink", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuditRecorderKeyPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuditRetention", Classification.REPLACEABLE, Guard.NONE),
            // Internal scheduler, not an extension point; the enum has no "internal" value, and it is not privacy-critical.
            entry("auditIntegrityHealthIndicator", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuditMaintenance", Classification.REPLACEABLE, Guard.NONE),
            entry("clusterSecurityHealthIndicator", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismScopeBudget", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismClusterScopeBudget", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismSharedBudgetPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismCorrelationMdc", Classification.REPLACEABLE, Guard.NONE),
            // Carries the scope read budget, so it is not replaceable: a second RequestLimits bean is refused.
            entry("dataPrismRequestLimits", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismRequestLimitsPreflight", Classification.REPLACEABLE, Guard.NONE),
            // The catalog decides which source names a caller may see and what each source says about itself; the
            // options carry the real-names policy. None can be replaced: a competing bean is refused at startup.
            entry("dataPrismSourceCatalog", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismOrchestrationOptions", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismCatalogQueries", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismLinkKeys", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismLinkCodec", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismCatalogPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismCatalogSignals", Classification.REPLACEABLE, Guard.NONE),
            // The gate is the only way a sealed link is opened, and the parts carry it into the orchestration
            // options: neither can be replaced. The limiter is the extension point (a cluster-wide one is
            // supplied by the application, and refused where one is needed and absent).
            entry("dataPrismLinkAttemptGate", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismLinkParts", Classification.PRIVACY_CRITICAL, Guard.COMPETING_BEAN_REFUSAL),
            entry("dataPrismLinkPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismLinkAttemptLimiter", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismLinkLimiterValidated", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismLinkKeySignals", Classification.REPLACEABLE, Guard.NONE),
            entry("linkKeysHealthIndicator", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismContextOrchestrator", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismStdioTransportRefused", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismMcpTransportPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismHttpTransportValidated", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismHttpTransport", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismMcpSyncServer", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismMcpServlet", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismPrivacyCluster", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismSuppliedClusterGauge", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismOversightState", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismClusterOversightState", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismApprovalStore", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismClusterApprovalStore", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismCallerRateLimiter", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismClusterCallerRateLimiter", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismOversightPolicy", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismToolAdmission", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismParameterFingerprinter", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismReidentificationPolicy", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismReidentificationService", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismReidentificationIndexFeed", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismPassThroughIdentityResolver", Classification.REPLACEABLE, Guard.NONE));

    /** @throws IllegalStateException if {@code beanMethodName} has no checked-in row. */
    public static BeanContract contractOf(String beanMethodName) {
        BeanContract contract = BEANS.get(beanMethodName);
        if (contract == null) {
            throw new IllegalStateException("unclassified @Bean method '" + beanMethodName
                    + "': add a row to " + PrivacyExtensionPoints.class.getSimpleName()
                    + " classifying it REPLACEABLE or PRIVACY_CRITICAL");
        }
        return contract;
    }

    public static Classification classify(String beanMethodName) {
        return contractOf(beanMethodName).classification();
    }

    /** Every bean method name this registry has a row for, for the sweep's exhaustiveness check. */
    public static Set<String> classifiedBeanNames() {
        return BEANS.keySet();
    }

    /**
     * Refuses startup when a bean definition other than {@code ownName} produces {@code type}, with the refusal
     * {@code refusal} builds. Two checks, because Spring cannot read the type of a
     * bean whose type is not declared (a raw {@link FactoryBean}, or a method declared to return {@code Object}):
     * its type is unknown until it is created.
     *
     * <ol>
     * <li>Now, for every definition whose type is declared: the refusal comes before any singleton is created.
     * <li>As beans are created ({@link Watcher}): from the point the application context has registered every bean
     * post-processor and initialised its event multicaster (before {@code onRefresh}, which creates the servlet and
     * its dependencies), a raw factory is read before the next bean is instantiated, and any bean that turns out to
     * be the type is refused as it is created, whatever it was declared to be. The refusal comes before anything is
     * injected with it, so it is the refusal and not a "found 2 matching beans" error that the application sees.
     * <li>Once every singleton exists ({@link PostCheck}): the real beans of the type are inspected, which catches
     * what the first two cannot see: a post-processor that swapped a bean after the watcher ran, a definition added
     * late, a singleton registered by hand. The watcher stops reading at that point.
     * </ol>
     * A deliberate post-processor that replaces the guarded bean under its own name is not detected: that is
     * operator code (docs/architecture.md, trust boundary).
     */
    static void refuseCompetingBeans(ConfigurableListableBeanFactory factory, Class<?> type, String ownName,
                                     Supplier<? extends RuntimeException> refusal) {
        if (competing(factory, type, ownName)) {
            throw refusal.get();
        }
        Watcher watcher = new Watcher(factory, type, ownName, refusal);
        factory.addBeanPostProcessor(watcher);
        if (factory instanceof BeanDefinitionRegistry registry) {
            RootBeanDefinition check = new RootBeanDefinition(PostCheck.class,
                    () -> new PostCheck(factory, watcher, type, ownName, refusal));
            check.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition("dataPrismCompetitionCheck." + type.getSimpleName(), check);
        }
    }

    /** Once every singleton exists, refuses any real bean of the type other than the settings' own. */
    static final class PostCheck implements SmartInitializingSingleton {
        private final ConfigurableListableBeanFactory factory;
        private final Watcher watcher;
        private final Class<?> type;
        private final String ownName;
        private final Supplier<? extends RuntimeException> refusal;

        PostCheck(ConfigurableListableBeanFactory factory, Watcher watcher, Class<?> type, String ownName,
                  Supplier<? extends RuntimeException> refusal) {
            this.factory = factory;
            this.watcher = watcher;
            this.type = type;
            this.ownName = ownName;
            this.refusal = refusal;
        }

        @Override
        public void afterSingletonsInstantiated() {
            watcher.stop();
            for (String name : factory.getBeanNamesForType(type, true, true)) {
                if (!name.equals(ownName)) {
                    throw refusal.get();
                }
            }
        }
    }

    /** Whether {@code bean} is, or is a factory for, a {@code type}; reads a factory's declared product type only. */
    private static boolean produces(Object bean, Class<?> type) {
        if (type.isInstance(bean)) {
            return true;
        }
        if (bean instanceof FactoryBean<?> factoryBean) {
            Class<?> product = factoryBean.getObjectType();
            return product != null && type.isAssignableFrom(product);
        }
        return false;
    }

    /**
     * Looks for a competing bean while beans are being created. It does nothing until the application context has
     * registered every bean post-processor (its event multicaster exists, which {@code refresh} initialises after
     * {@code registerBeanPostProcessors} and before {@code onRefresh}), because reading a raw factory creates it, and
     * a factory created earlier would be created without the post-processors. From then, before each bean is
     * instantiated, it reads the product type of every raw factory whose type is still unknown (so before anything is
     * injected with its product), until every factory has been read or {@link #stop} is called; a factory on a
     * configuration class that is itself being created is read at a later bean. After a bean is initialised it
     * refuses one that is a competing type whatever it was declared to be.
     */
    private static final class Watcher implements InstantiationAwareBeanPostProcessor {
        /** Set while a read runs, so that the creation of a raw factory it triggers cannot re-enter. */
        private static final ThreadLocal<Boolean> READING = new ThreadLocal<>();

        private final ConfigurableListableBeanFactory factory;
        private final Class<?> type;
        private final String ownName;
        private final Supplier<? extends RuntimeException> refusal;
        private final Set<String> read = ConcurrentHashMap.newKeySet();
        private volatile boolean allRead;

        /** No further reads: every singleton exists, and {@link PostCheck} inspects the beans themselves. */
        void stop() {
            allRead = true;
        }

        Watcher(ConfigurableListableBeanFactory factory, Class<?> type, String ownName,
                Supplier<? extends RuntimeException> refusal) {
            this.factory = factory;
            this.type = type;
            this.ownName = ownName;
            this.refusal = refusal;
        }

        @Override
        public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
            if (allRead || READING.get() != null
                    || !factory.containsSingleton(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
                return null;
            }
            READING.set(Boolean.TRUE);
            try {
                boolean unread = false;
                for (String factoryName : factory.getBeanNamesForType(FactoryBean.class, true, false)) {
                    String name = BeanFactoryUtils.transformedBeanName(factoryName);
                    if (read.contains(name)) {
                        continue;
                    }
                    if (factory.getType(name, false) != null) {
                        read.add(name);
                        continue;
                    }
                    Class<?> product = factory.getType(name, true);
                    if (product == null) {
                        unread = true;
                        continue;
                    }
                    read.add(name);
                    if (!name.equals(ownName) && type.isAssignableFrom(product)) {
                        throw refusal.get();
                    }
                }
                allRead = !unread;
            } finally {
                READING.remove();
            }
            return null;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!ownName.equals(beanName) && produces(bean, type)) {
                throw refusal.get();
            }
            return bean;
        }
    }

    private static boolean competing(ListableBeanFactory factory, Class<?> type, String ownName) {
        Set<String> names = new LinkedHashSet<>(Arrays.asList(factory.getBeanNamesForType(type, true, false)));
        names.remove(ownName);
        return !names.isEmpty();
    }

    private static Map.Entry<String, BeanContract> entry(String name, Classification classification, Guard guard) {
        return Map.entry(name, new BeanContract(classification, guard));
    }

    private PrivacyExtensionPoints() {
    }
}
