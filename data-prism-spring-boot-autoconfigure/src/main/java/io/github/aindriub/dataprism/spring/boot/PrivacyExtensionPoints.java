package io.github.aindriub.dataprism.spring.boot;

import java.util.Map;
import java.util.Set;

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
            entry("dataPrismSecurityPolicy", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuthorizationService", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismScopeResolver", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismAuditRecorder", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismSlf4jAuditSink", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismHashChainedAuditSink", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismScopeBudget", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismClusterScopeBudget", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismSharedBudgetPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismContextOrchestrator", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismStdioTransportRefused", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismMcpTransportPreflight", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismHttpTransportValidated", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismHttpTransport", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismMcpSyncServer", Classification.REPLACEABLE, Guard.NONE),
            entry("dataPrismMcpServlet", Classification.REPLACEABLE, Guard.NONE),
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

    private static Map.Entry<String, BeanContract> entry(String name, Classification classification, Guard guard) {
        return Map.entry(name, new BeanContract(classification, guard));
    }

    private PrivacyExtensionPoints() {
    }
}
