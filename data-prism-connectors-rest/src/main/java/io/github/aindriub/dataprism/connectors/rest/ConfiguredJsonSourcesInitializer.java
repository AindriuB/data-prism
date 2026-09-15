package io.github.aindriub.dataprism.connectors.rest;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Registers one {@link ConfiguredJsonDataSourceAdapter} bean per configured
 * source, before the context refreshes.
 *
 * <p>Discovered automatically by Spring Boot from {@code META-INF/spring.factories}
 * in this jar, under the {@code org.springframework.context.ApplicationContextInitializer}
 * key — {@code ApplicationContextInitializer} is not one of the SPI types the
 * newer {@code META-INF/spring/*.imports} mechanism covers, unlike {@link
 * ConfiguredJsonSourcesAutoConfiguration}'s own registration. Whichever file
 * loads it, this runs earlier in startup than that class's beans do. See that
 * class's Javadoc for why the timing matters: {@code
 * @ConditionalOnBean(DataSourceAdapter.class)} elsewhere in the platform cannot
 * see an adapter registered any later than this.
 *
 * <p>No-ops when {@link ConfiguredJsonSourcesAutoConfiguration#CONFIG_LOCATION_PROPERTY}
 * is unset, so a server distribution that never configured this feature is
 * completely unaffected by this jar being present on its loader path.
 */
public final class ConfiguredJsonSourcesInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        String location = environment.getProperty(ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY);
        if (location == null || location.isBlank()) {
            return;
        }

        ConfiguredJsonSourcesConfig config = loadConfig(environment);
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

        RestClient client = config.tlsConfigured()
                ? MutualTlsRestClients.build(config.tls())
                : RestClient.create();
        beanFactory.registerSingleton("dataPrismConfiguredJsonSourcesRestClient", client);

        for (Map.Entry<String, ConfiguredJsonSource> entry : config.sources().entrySet()) {
            beanFactory.registerSingleton("dataPrismConfiguredJsonSource_" + entry.getKey(),
                    new ConfiguredJsonDataSourceAdapter(entry.getValue(), client));
        }
    }

    static ConfiguredJsonSourcesConfig loadConfig(Environment environment) {
        String location = environment.getProperty(ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY);
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            return ConfiguredJsonSources.fromYaml(in);
        } catch (IOException e) {
            throw new IllegalStateException(ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY
                    + " '" + location + "' could not be read", e);
        }
    }
}
