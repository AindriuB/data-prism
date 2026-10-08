package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.spring.boot.validation.DataPrismContractValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Cross-property and cross-integration validation, run as a bean so that it precedes the pipeline. */
@Configuration(proxyBeanMethods = false)
class PropertiesValidation {
    /**
     * Task 69: {@code configuredJsonSourceNames} is deliberately {@code
     * ObjectProvider<Set<String>>}, not a type declared by {@code
     * data-prism-connectors-rest}. That module registers this bean, when
     * present, under the literal name {@link
     * #CONFIGURED_JSON_SOURCE_NAMES_BEAN} — see {@code
     * ConfiguredJsonSourcesAutoConfiguration#configuredJsonSourceNames} for
     * why a plain JDK type, matched by bean name through {@code @Qualifier}
     * rather than by importing that module's own class, is what keeps this
     * unconditional {@code @Bean} method safe to run on a classpath that
     * genuinely never includes that module at all (the base standalone
     * server's own {@code pom.xml} declares it test-scope only).
     */
    @Bean
    Object dataPrismPropertiesValidated(DataPrismProperties properties, List<DataSourceAdapter<?>> adapters,
            ObjectProvider<IdentityResolver> identities, ObjectProvider<HmacKeyReferenceResolver> keys,
            ObjectProvider<AuditSink> audit, ObjectProvider<PrivacyMetrics> metrics,
            @Qualifier(CONFIGURED_JSON_SOURCE_NAMES_BEAN) ObjectProvider<Set<String>> configuredJsonSourceNames,
            Environment environment, ConfigurableListableBeanFactory beanFactory) {
        properties.validate();
        Integer serverPort = environment.getProperty("server.port", Integer.class, 8080);
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        properties.validateOperatorPort(serverPort, managementPort);
        properties.validateCluster(applicationSuppliesCluster(beanFactory), serverPort, managementPort);
        DataPrismContractValidator.validateIntegrations(properties, adapters, identities, keys, audit, metrics,
                configuredJsonSourceNames);
        validateProfile(properties);
        validateKey(properties, keys.getIfAvailable());
        return new Object();
    }

    private static final String CLUSTER_TYPE = "io.github.aindriub.dataprism.hazelcast.PrivacyCluster";

    /**
     * Ownership is the bean definition's origin, never its name: this auto-configuration's cluster is
     * the one produced by {@link ClusterBackedState}, so an application bean that happens to share
     * the default name is still the application's.
     */
    private static boolean isFrameworkCluster(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!beanFactory.containsBeanDefinition(name)) {
            return false;
        }
        String factoryBean = beanFactory.getBeanDefinition(name).getFactoryBeanName();
        if (factoryBean == null) {
            return false;
        }
        if (beanFactory.containsBeanDefinition(factoryBean)) {
            String factoryClass = beanFactory.getBeanDefinition(factoryBean).getBeanClassName();
            return ClusterBackedState.class.getName().equals(factoryClass);
        }
        return false;
    }

    /**
     * Whether a {@code PrivacyCluster} other than this auto-configuration's own is defined. Looked up
     * by type name so a {@code single-node} consumer without {@code data-prism-hazelcast} never
     * resolves the optional class.
     */
    private static boolean applicationSuppliesCluster(ConfigurableListableBeanFactory beanFactory) {
        if (!ClassUtils.isPresent(CLUSTER_TYPE, beanFactory.getBeanClassLoader())) {
            return false;
        }
        try {
            Class<?> type = ClassUtils.forName(CLUSTER_TYPE, beanFactory.getBeanClassLoader());
            return Arrays.stream(beanFactory.getBeanNamesForType(type, true, false))
                    .anyMatch(name -> !isFrameworkCluster(beanFactory, name));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Matches {@code data-prism-connectors-rest}'s {@code
     * ConfiguredJsonSourcesAutoConfiguration.CONFIGURED_JSON_SOURCE_NAMES_BEAN}
     * by literal value; see {@link #dataPrismPropertiesValidated}'s Javadoc
     * for why this class never imports that module's own constant or type.
     */
    private static final String CONFIGURED_JSON_SOURCE_NAMES_BEAN = "dataPrismConfiguredJsonSourceNames";

    @Bean
    DataPrismContractValidator dataPrismContractValidator(DataPrismProperties properties,
            ObjectProvider<DataSourceAdapter<?>> adapters, ObjectProvider<IdentityResolver> identities,
            ObjectProvider<HmacKeyReferenceResolver> keys, ObjectProvider<AuditSink> audit,
            ObjectProvider<PrivacyMetrics> metrics,
            @Qualifier(CONFIGURED_JSON_SOURCE_NAMES_BEAN) ObjectProvider<Set<String>> configuredJsonSourceNames) {
        return new DataPrismContractValidator(properties, adapters, identities, keys, audit, metrics,
                configuredJsonSourceNames);
    }

    static void validateProfile(DataPrismProperties properties) {
        try (var input = DataPrismAutoConfiguration.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            if (!PrivacyProfiles.fromYaml(input).containsKey(properties.getPrivacy().getProfile())) {
                throw new DataPrismConfigurationException("UNKNOWN_PRIVACY_PROFILE", properties.getPrivacy().getProfile());
            }
        } catch (IOException e) { throw new IllegalStateException("default privacy profiles could not be loaded", e); }
    }

    private static void validateKey(DataPrismProperties properties, HmacKeyReferenceResolver provider) {
        if (provider == null) throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER", "provide an HmacKeyReferenceResolver bean for the configured reference");
        String reference = properties.getPrivacy().getHmacKey().getEnvironmentVariable();
        if (reference == null || reference.isBlank()) reference = properties.getPrivacy().getHmacKey().getProviderReference();
        try {
            byte[] key = provider.resolve(properties.getPrivacy().getHmacKey().getKeyId(), reference);
            if (key == null || key.length < 32) throw new DataPrismConfigurationException("HMAC_KEY_WEAK", "configured key material is shorter than 32 bytes");
            Arrays.fill(key, (byte) 0);
        } catch (DataPrismConfigurationException e) { throw e;
        } catch (RuntimeException e) { throw new DataPrismConfigurationException("HMAC_KEY_UNRESOLVED", "configured HMAC key reference could not be resolved"); }
    }
}
