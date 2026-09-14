package io.github.aindriub.dataprism.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mechanical guard {@link PrivacyExtensionPoints} exists for: every
 * {@code @Bean} method {@link DataPrismAutoConfiguration} declares must be
 * classified, and every {@code PRIVACY_CRITICAL} one must be structurally
 * impossible to silently replace. Add a {@code @Bean} method without a row in
 * {@link PrivacyExtensionPoints} and the first test below fails; misclassify
 * one as {@code PRIVACY_CRITICAL} without a real guard and the second does.
 */
class AutoConfiguredBeanClassificationTest {

    private static List<Method> declaredBeanMethods() {
        return Arrays.stream(DataPrismAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();
    }

    @Test
    void every_declared_bean_method_is_classified() {
        for (Method method : declaredBeanMethods()) {
            assertThat(PrivacyExtensionPoints.contractOf(method.getName()))
                    .as("classification for @Bean method %s", method.getName())
                    .isNotNull();
        }
    }

    @Test
    void the_classification_registry_has_no_stale_entries() {
        List<String> declared = declaredBeanMethods().stream().map(Method::getName).toList();
        assertThat(PrivacyExtensionPoints.classifiedBeanNames()).containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void privacy_critical_beans_never_carry_conditional_on_missing_bean_and_are_guarded() {
        for (Method method : declaredBeanMethods()) {
            PrivacyExtensionPoints.BeanContract contract = PrivacyExtensionPoints.contractOf(method.getName());
            if (contract.classification() != PrivacyExtensionPoints.Classification.PRIVACY_CRITICAL) {
                continue;
            }
            assertThat(method.isAnnotationPresent(ConditionalOnMissingBean.class))
                    .as("%s is PRIVACY_CRITICAL and must not carry @ConditionalOnMissingBean", method.getName())
                    .isFalse();
            boolean primary = method.isAnnotationPresent(Primary.class);
            boolean competingBeanRefusal = contract.guard() == PrivacyExtensionPoints.Guard.COMPETING_BEAN_REFUSAL;
            assertThat(primary || competingBeanRefusal)
                    .as("%s is PRIVACY_CRITICAL and must be @Primary or guarded by the competing-bean refusal",
                            method.getName())
                    .isTrue();
            if (primary) {
                assertThat(contract.guard())
                        .as("%s is @Primary and must declare Guard.PRIMARY", method.getName())
                        .isEqualTo(PrivacyExtensionPoints.Guard.PRIMARY);
            }
        }
    }

    @Test
    void the_three_privacy_critical_extension_points_are_classified_privacy_critical() {
        assertThat(PrivacyExtensionPoints.classify("dataPrismSecretKeyProvider"))
                .isEqualTo(PrivacyExtensionPoints.Classification.PRIVACY_CRITICAL);
        assertThat(PrivacyExtensionPoints.classify("dataPrismPrivacyPolicyResolver"))
                .isEqualTo(PrivacyExtensionPoints.Classification.PRIVACY_CRITICAL);
        assertThat(PrivacyExtensionPoints.classify("dataPrismRawValueLeakValidator"))
                .isEqualTo(PrivacyExtensionPoints.Classification.PRIVACY_CRITICAL);
    }
}
