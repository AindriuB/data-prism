package io.github.aindriub.dataprism.connectors.rest;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
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

    /** The optional global default for the outbound correlation header; a source's own key wins. */
    private static final String OUTBOUND_HEADER_PROPERTY = "dataprism.correlation.outbound.header";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        String location = bindString(environment, ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY);
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
        String location = bindString(environment, ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY);
        boolean fixtureDevelopment = Binder.get(environment)
                .bind(FIXTURE_DEVELOPMENT_PROPERTY, Boolean.class)
                .orElse(false);
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            String outboundHeader = bindString(environment, OUTBOUND_HEADER_PROPERTY);
            return ConfiguredJsonSources.fromYaml(in, fixtureDevelopment, outboundHeader);
        } catch (IOException e) {
            // The cause is not chained (its message repeats the raw location) and the location is
            // shown without user-info, query string or fragment.
            throw new IllegalStateException(ConfiguredJsonSourcesAutoConfiguration.CONFIG_LOCATION_PROPERTY
                    + " '" + describe(location) + "' could not be read");
        }
    }

    /**
     * A config location safe to name in a message, safe by construction:
     * <ul>
     *   <li>anything containing {@code @} is never shown (credentials can hide in a form no parser
     *       can be trusted to split);</li>
     *   <li>a {@code classpath:} or {@code file:} location, or a plain path, is cut at the first
     *       {@code ?} or {@code #} and cut to 64 characters;</li>
     *   <li>any other scheme is reduced to {@code scheme://host[:port]}, never a path (it can embed
     *       a token), user-info, query or fragment.</li>
     * </ul>
     */
    static String describe(String location) {
        if (location.indexOf('@') >= 0) {
            return LOCATION_WITH_CREDENTIALS;
        }
        java.util.regex.Matcher scheme = SCHEME.matcher(location);
        boolean otherScheme = scheme.lookingAt() && scheme.group(1).length() > 1
                && !scheme.group(1).equalsIgnoreCase("classpath") && !scheme.group(1).equalsIgnoreCase("file");
        if (!otherScheme) {
            int cut = indexOfAny(location, '?', '#');
            return io.github.aindriub.dataprism.core.model.StrictYaml.shown(
                    cut < 0 ? location : location.substring(0, cut));
        }
        try {
            URI uri = new URI(location);
            if (uri.getHost() == null || uri.getScheme() == null) {
                return LOCATION_WITH_CREDENTIALS;
            }
            return io.github.aindriub.dataprism.core.model.StrictYaml.shown(uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() >= 0 ? ":" + uri.getPort() : ""));
        } catch (URISyntaxException e) {
            return LOCATION_WITH_CREDENTIALS;
        }
    }

    private static final java.util.regex.Pattern SCHEME = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");

    static final String LOCATION_WITH_CREDENTIALS = "<location not shown>";

    private static int indexOfAny(String text, char first, char second) {
        int a = text.indexOf(first);
        int b = text.indexOf(second);
        return a < 0 ? b : b < 0 ? a : Math.min(a, b);
    }

    /**
     * {@code DataPrismContractValidator} (owned by the base auto-configuration
     * module, not this one) no longer requires a {@code dataprism.sources}
     * entry for a {@code DataSourceAdapter} bean this initializer registers: a
     * configured JSON source's transport is stated exactly once, here,
     * authoritatively, in {@code json-sources:}. An earlier task made the
     * cross-name check unconditional and could not relax it without editing a
     * module it did not own; this one owns both, and the cross-check itself
     * has been narrowed instead (see {@code DataPrismContractValidator
     * .validateIntegrations}) so the duplicate entry is no longer needed to
     * satisfy it.
     *
     * <p>Nothing stops an operator from also naming the source under {@code
     * dataprism.sources} — the field still exists, unconditionally optional now
     * rather than required — and if they do, this method still refuses startup
     * the moment the two disagree, naming the source and the two properties but neither URL, since the validated URL
     * silently losing to the one {@link ConfiguredJsonDataSourceAdapter}
     * actually dials would otherwise go unnoticed.
     */
    private static void rejectTransportDisagreement(Environment environment, ConfiguredJsonSourcesConfig config) {
        for (Map.Entry<String, ConfiguredJsonSource> entry : config.sources().entrySet()) {
            String name = entry.getKey();
            URI declaredHere = entry.getValue().transport().baseUrl();
            String declaredElsewhereRaw = bindString(environment, "dataprism.sources." + name + ".base-url");
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
                // Neither URL is repeated: either may carry user-info.
                String shownName = io.github.aindriub.dataprism.core.model.StrictYaml.shown(name);
                throw new IllegalStateException("json source " + shownName + " base-url disagrees: json-sources "
                        + "declares one transport but dataprism.sources." + shownName + ".base-url declares another"
                        + "; these must name the same transport");
            }
        }
    }

    /**
     * Reads one {@code dataprism.*} property the same way {@code
     * @ConfigurationProperties} does: through {@link Binder}'s relaxed binding,
     * not {@link Environment#getProperty}.
     *
     * <p>{@code Environment#getProperty}, on a bare {@link
     * org.springframework.core.env.SystemEnvironmentPropertySource}, already
     * resolves a dotted name against the literal uppercased form with every
     * {@code .} and {@code -} substituted by {@code _}: {@code
     * dataprism.sources.customers.base-url} matches {@code
     * DATAPRISM_SOURCES_CUSTOMERS_BASE_URL} through either call, Binder or not.
     * What it cannot see is the hyphen <em>dropped</em> rather than
     * substituted — {@code DATAPRISM_SOURCES_CUSTOMERS_BASEURL} — which is
     * {@code compose.yaml}'s own documented convention (see its comment
     * there) and the form {@link Binder}'s relaxed matching additionally
     * accepts. A raw {@code getProperty} call here meant this exact property,
     * paired against {@code dataprism.sources.<name>.base-url}, could never be
     * read back in that specific form, and the disagreement check built on it
     * looked satisfied by an absent value it never actually saw.
     *
     * <p>In a full {@code SpringApplication} bootstrap, {@code
     * ConfigDataEnvironmentPostProcessor} already attaches a relaxed-binding
     * -aware property source to the environment before any {@code
     * ApplicationContextInitializer} — this one included — ever runs, which
     * makes even a plain {@code getProperty} call relaxed-binding-aware by the
     * time this class's {@code initialize} executes in the packaged server.
     * That does not make the raw call correct: it means this class's own
     * correctness was, until this method existed, an accident of when
     * something else in the startup sequence happens to run, not a property
     * of this code. Calling {@link Binder} directly removes that dependency on
     * an unrelated part of the bootstrap sequence having already run first.
     */
    private static String bindString(Environment environment, String propertyName) {
        return Binder.get(environment).bind(propertyName, String.class).orElse(null);
    }
}
