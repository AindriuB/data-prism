package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.engine.ChainableFields;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.link.LinkConfigurationException;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueries;
import io.github.aindriub.dataprism.orchestration.catalog.PagingSettings;
import io.github.aindriub.dataprism.orchestration.catalog.RealNamesPolicy;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceHosts;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The source catalog, the options that carry it into every orchestrator, the link key that page links need, and the
 * queries behind {@code list_sources} and {@code describe_source}.
 *
 * <p>The catalog is built once from every {@link DataSourceAdapter} bean and the base-url host of every configured
 * source, and is the one catalog of the context: the orchestrator, configured or not, and the queries use it. A
 * description that breaks a rule refuses startup. None of these beans can be replaced by an application bean: each is
 * refused by {@link #dataPrismCatalogPreflight()}.
 */
@Configuration(proxyBeanMethods = false)
class CatalogWiring {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogWiring.class);

    /** The longest link, in characters, the served {@code page} argument schema allows: the link default. */
    static final int PAGE_MAX_LENGTH = 1024;

    private static final Duration LINK_LIFETIME = Duration.ofMinutes(30);
    private static final Duration LINK_CLOCK_SKEW = Duration.ofSeconds(5);

    /** Resolve this before singleton creation: an application bean of one of these types takes none of them over. */
    @Bean
    static BeanFactoryPostProcessor dataPrismCatalogPreflight() {
        return factory -> {
            refuseCompeting(factory, SourceCatalog.class, "dataPrismSourceCatalog");
            refuseCompeting(factory, OrchestrationOptions.class, "dataPrismOrchestrationOptions");
            refuseCompeting(factory, CatalogQueries.class, "dataPrismCatalogQueries");
            refuseCompeting(factory, LinkKeys.class, "dataPrismLinkKeys");
            refuseCompeting(factory, LinkCodec.class, "dataPrismLinkCodec");
        };
    }

    private static void refuseCompeting(ConfigurableListableBeanFactory factory, Class<?> type, String ownName) {
        PrivacyExtensionPoints.refuseCompetingBeans(factory, type, ownName,
                () -> new DataPrismConfigurationException("FORBIDDEN_PRIVACY_OVERRIDE",
                        "an application " + type.getSimpleName() + " bean cannot replace the framework's"));
    }

    /**
     * Built from every adapter, so a configured source is in it too. The hosts are published by the connector module
     * when {@code dataprism.json-sources.config-location} is set.
     */
    @Bean
    SourceCatalog dataPrismSourceCatalog(List<DataSourceAdapter<?>> adapters, ObjectProvider<SourceHosts> hosts,
                                         ObjectProvider<JsonTreeScrubbingEngine> engines,
                                         DataPrismProperties properties) {
        checkChainableDeclarations(adapters, engines.getIfUnique());
        SourceCatalog catalog;
        try {
            catalog = SourceCatalog.build(adapters, sourceHosts(properties, hosts.getIfAvailable(SourceHosts::none)));
        } catch (IllegalArgumentException refused) {
            throw catalogRefusal(refused);
        }
        PagingProperties.ListSources paging = properties.getPaging().getListSources();
        if (catalog.entries().size() > paging.pageSizeValue() && !properties.getLinks().keyConfigured()) {
            throw new DataPrismConfigurationException("MISSING_PAGING_KEY",
                    "more sources are registered than dataprism.paging.list-sources.page-size; "
                            + "dataprism.links.key-id is required to page list_sources");
        }
        log(catalog);
        return catalog;
    }

    /**
     * The host of every configured source: each {@code dataprism.sources.<name>.base-url}, then the hosts the connector
     * module published, which win for a name in both. A base URL that cannot be read here is left to the property
     * validation, which refuses it with its own code.
     */
    private static Map<String, String> sourceHosts(DataPrismProperties properties, SourceHosts published) {
        Map<String, String> hosts = new HashMap<>();
        properties.getSources().forEach((name, source) -> {
            String baseUrl = source == null ? null : source.getBaseUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                try {
                    String host = URI.create(baseUrl.strip()).getHost();
                    if (host != null) {
                        hosts.put(name, host);
                    }
                } catch (IllegalArgumentException unreadable) {
                    // Not a URI: INVALID_SOURCE_URL is raised by the property validation.
                }
            }
        });
        hosts.putAll(published.byName());
        return hosts;
    }

    /**
     * What a person declared must be right before anything is served: a chainable path that is not a key, not
     * classified, or longer than the cap refuses startup, not the first {@code describe_source} that meets it.
     */
    private static void checkChainableDeclarations(List<DataSourceAdapter<?>> adapters,
                                                   JsonTreeScrubbingEngine engine) {
        for (DataSourceAdapter<?> adapter : adapters) {
            try {
                ChainableFields.of(adapter.responseType(), engine == null ? Map.of() : engine.descriptors());
            } catch (IllegalStateException refused) {
                throw new DataPrismConfigurationException(ChainableFields.CODE,
                        "source " + adapter.sourceName() + ": " + afterCode(refused.getMessage(), ChainableFields.CODE));
            }
        }
    }

    private static String afterCode(String message, String code) {
        String prefix = code + ":";
        return message != null && message.startsWith(prefix) ? message.substring(prefix.length()).strip()
                : "a chainable declaration is not valid";
    }

    private static DataPrismConfigurationException catalogRefusal(IllegalArgumentException refused) {
        String message = String.valueOf(refused.getMessage());
        for (String code : List.of("SOURCE_DESCRIPTION_LEAKS_NAME", "INVALID_SOURCE_DESCRIPTION")) {
            if (message.startsWith(code + ":")) {
                // The catalog names the source and the rule, never the text; the cause is dropped all the same.
                return new DataPrismConfigurationException(code, message.substring(code.length() + 1).strip());
            }
        }
        return new DataPrismConfigurationException("INVALID_SOURCE_DESCRIPTION", "the source catalog could not be built");
    }

    /** One INFO line for the sources on the generated fallback, one WARN line for the odd names. Never a description. */
    private static void log(SourceCatalog catalog) {
        if (catalog.generatedDescriptionCount() > 0) {
            LOG.info("{} source(s) have no declared description and use the generated fallback: {}",
                    catalog.generatedDescriptionCount(), catalog.generatedDescriptionSources());
        }
        if (catalog.nonconformingNameCount() > 0) {
            LOG.warn("{} source name(s) do not follow the source-name pattern; list_sources serves them all the same",
                    catalog.nonconformingNameCount());
        }
    }

    /**
     * The one set of options every orchestrator in the context is built with: the catalog above, the real-names
     * scopes and the unselected-source limit. The connector module's orchestrator takes it too.
     */
    @Bean
    OrchestrationOptions dataPrismOrchestrationOptions(SourceCatalog catalog, DataPrismProperties properties) {
        CatalogProperties settings = properties.getCatalog();
        return OrchestrationOptions.defaults().withCatalog(catalog)
                .withRealNames(new RealNamesPolicy(settings.getRealNamesScopes()))
                .withUnselectedSourceLimit(settings.unselectedSourceLimitValue());
    }

    /**
     * The link keys, built only when a link key is configured, by the same rule the startup check for an estate larger
     * than one page uses ({@link LinksProperties#keyConfigured()}): page links need them even while
     * {@code dataprism.links.enabled} is false.
     */
    @Bean
    @Conditional(LinkKeyConfigured.class)
    LinkKeys dataPrismLinkKeys(DataPrismProperties properties, ObjectProvider<HmacKeyReferenceResolver> resolver) {
        return LinkKeyResolution.resolve(properties, resolver.getIfAvailable());
    }

    @Bean
    @Conditional(LinkKeyConfigured.class)
    LinkCodec dataPrismLinkCodec(LinkKeys keys, DataPrismProperties properties, Clock clock) {
        try {
            return new LinkCodec(new LinkSettings(properties.getLinks().getKeyId(), Optional.of(LINK_LIFETIME),
                    PAGE_MAX_LENGTH, true, LINK_CLOCK_SKEW), keys, clock);
        } catch (LinkConfigurationException refused) {
            throw new DataPrismConfigurationException(refused.code(), "dataprism.links: the link settings are not valid");
        }
    }

    /**
     * {@code list_sources} and {@code describe_source}. It asks the context's orchestrator (299), which holds the
     * scrubber that really scrubs a configured source. Page links are not issued yet (task 255): without them a call
     * returns the whole visible list. Startup refuses an estate larger than one page only while no link key is
     * configured; with a key configured it does not keep the list to one page until 255 adds the gate.
     */
    @Bean
    CatalogQueries dataPrismCatalogQueries(SourceCatalog catalog, OrchestrationOptions options,
                                           ContextOrchestrator orchestrator, ValueTokenSource tokens,
                                           List<LlmResponseValidator> validators, PrivacyMetrics metrics, Clock clock,
                                           DataPrismProperties properties) {
        PagingProperties paging = properties.getPaging();
        return new CatalogQueries(catalog, options.visibility(), new SourceAliasing(tokens), options.realNames(),
                orchestrator, List.copyOf(validators),
                new PagingSettings(paging.getListSources().pageSizeValue(), paging.getListSources().maxPageSizeValue(),
                        paging.lifetimeValue()),
                null, false, metrics, clock);
    }

    /**
     * True when {@code dataprism.links.key-id} has text, which is what {@link LinksProperties#keyConfigured()} says.
     * {@code @ConditionalOnProperty} is not used: it reads the text {@code false} as "not set".
     */
    static final class LinkKeyConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String keyId = context.getEnvironment().getProperty("dataprism.links.key-id");
            return keyId != null && !keyId.isBlank();
        }
    }
}
