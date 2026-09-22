package io.github.aindriub.dataprism.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mechanical guard {@link PrivacyExtensionPoints} exists for: every
 * {@code @Bean} method {@link DataPrismAutoConfiguration} declares must be
 * classified, and every {@code PRIVACY_CRITICAL} one must be structurally
 * impossible to silently replace. Add a {@code @Bean} method without a row in
 * {@link PrivacyExtensionPoints} and the first test below fails; misclassify
 * one as {@code PRIVACY_CRITICAL} without a real guard and the second does.
 *
 * <p>The sweep is not limited to methods declared directly on {@link
 * DataPrismAutoConfiguration}: it also walks every nested class it declares
 * and every class it {@code @Import}s (recursively, so a nested class of an
 * imported class is swept too). That is what stops a bean placed on a nested,
 * {@code @Import}ed configuration class — such as {@code
 * DataPrismAutoConfiguration.IdentityResolverSelection}'s {@code
 * dataPrismPassThroughIdentityResolver} — from dodging classification by
 * sitting outside a sweep of {@link DataPrismAutoConfiguration#getDeclaredMethods()}
 * alone. {@link #the_sweep_collects_bean_methods_from_nested_and_imported_configuration_classes()}
 * proves the collection step itself finds such a bean, against a fixture this
 * test owns rather than against {@link DataPrismAutoConfiguration}.
 */
class AutoConfiguredBeanClassificationTest {

    private static List<Method> declaredBeanMethods() {
        return collectBeanMethods(DataPrismAutoConfiguration.class);
    }

    /**
     * Collects every {@code @Bean} method reachable from {@code root}: the
     * methods it declares directly, plus every {@code @Bean} method reachable
     * (recursively) from its nested classes and the classes it {@code @Import}s.
     */
    private static List<Method> collectBeanMethods(Class<?> root) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        collectClasses(root, classes);
        List<Method> methods = new ArrayList<>();
        for (Class<?> declaring : classes) {
            for (Method method : declaring.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static void collectClasses(Class<?> root, Set<Class<?>> collected) {
        if (!collected.add(root)) {
            return;
        }
        for (Class<?> nested : root.getDeclaredClasses()) {
            collectClasses(nested, collected);
        }
        Import imported = root.getAnnotation(Import.class);
        if (imported != null) {
            for (Class<?> importedClass : imported.value()) {
                collectClasses(importedClass, collected);
            }
        }
    }

    @Test
    void the_sweep_collects_bean_methods_from_nested_and_imported_configuration_classes() {
        List<String> names = collectBeanMethods(SweepFixtureRoot.class).stream().map(Method::getName).toList();

        assertThat(names)
                .as("the collection step must find @Bean methods nested inside the root and imported by it")
                .contains("sweepFixtureNestedBean", "sweepFixtureImportedBean");

        // Neither fixture bean has a row in PrivacyExtensionPoints: were the
        // collection step vacuous (e.g. it silently skipped nested/imported
        // classes), this assertion would find nothing to reject and the
        // exhaustiveness check in every_declared_bean_method_is_classified
        // would never even see these methods, no matter how it were rerun
        // against this fixture.
        for (String name : names) {
            assertThat(PrivacyExtensionPoints.classifiedBeanNames())
                    .as("%s is a deliberately unclassified fixture bean", name)
                    .doesNotContain(name);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> PrivacyExtensionPoints.contractOf(name));
        }
    }

    @Import(SweepFixtureImport.class)
    private static class SweepFixtureRoot {
        @Configuration(proxyBeanMethods = false)
        static class SweepFixtureNested {
            @Bean
            Object sweepFixtureNestedBean() {
                return new Object();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    private static class SweepFixtureImport {
        @Bean
        Object sweepFixtureImportedBean() {
            return new Object();
        }
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
