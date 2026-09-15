package io.github.aindriub.dataprism.connectors.rest;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
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

    /** Mirrors {@code dataprism.transport.fixture-development}; see {@link ConfiguredJsonSources#fromYaml(InputStream, boolean)}. */
    private static final String FIXTURE_DEVELOPMENT_PROPERTY = "dataprism.transport.fixture-development";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        String location = environment.getProperty(ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY);
        if (location == null || location.isBlank()) {
            return;
        }

        ConfiguredJsonSourcesConfig config = loadConfig(environment);
        rejectTransportDisagreement(environment, config);
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
        boolean fixtureDevelopment = environment.getProperty(FIXTURE_DEVELOPMENT_PROPERTY, Boolean.class, false);
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            return ConfiguredJsonSources.fromYaml(in, fixtureDevelopment);
        } catch (IOException e) {
            throw new IllegalStateException(ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY
                    + " '" + location + "' could not be read", e);
        }
    }

    /**
     * {@code DataPrismContractValidator} (owned by the base auto-configuration
     * module, not this one) requires every {@code DataSourceAdapter} bean's name
     * to also appear under {@code dataprism.sources}, so a configured JSON
     * source's transport is necessarily stated twice: once, authoritatively,
     * here; once more, only to satisfy that unrelated name/adapter cross-check.
     * Two statements of one fact can drift, and the validated one is not the one
     * {@link ConfiguredJsonDataSourceAdapter} actually dials — so rather than
     * let the two disagree silently, refuse startup the moment they do, naming
     * both values. Removing the {@code dataprism.sources} entry's URL
     * requirement outright would be the more thorough fix, but that field is
     * declared in a module this task does not own; refusing on disagreement is
     * achievable entirely within this one, and closes the actual hazard: the
     * validated URL always being the one that also gets dialled.
     */
    private static void rejectTransportDisagreement(Environment environment, ConfiguredJsonSourcesConfig config) {
        for (Map.Entry<String, ConfiguredJsonSource> entry : config.sources().entrySet()) {
            String name = entry.getKey();
            URI declaredHere = entry.getValue().transport().baseUrl();
            String declaredElsewhereRaw = environment.getProperty("dataprism.sources." + name + ".base-url");
            if (declaredElsewhereRaw == null || declaredElsewhereRaw.isBlank()) {
                continue;
            }
            URI declaredElsewhere;
            try {
                declaredElsewhere = new URI(declaredElsewhereRaw);
            } catch (URISyntaxException e) {
                declaredElsewhere = null;
            }
            if (!declaredHere.equals(declaredElsewhere)) {
                throw new IllegalStateException("json source " + name + " base-url disagrees: json-sources "
                        + "declares " + declaredHere + " but dataprism.sources." + name + ".base-url declares "
                        + declaredElsewhereRaw + "; these must name the same transport");
            }
        }
    }
}
