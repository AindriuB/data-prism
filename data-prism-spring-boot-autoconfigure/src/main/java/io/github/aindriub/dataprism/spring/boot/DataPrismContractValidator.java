package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.InitializingBean;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Final cross-checks which property binding alone cannot prove. */
final class DataPrismContractValidator implements InitializingBean {
    private final DataPrismProperties properties; private final ObjectProvider<DataSourceAdapter<?>> adapters;
    private final ObjectProvider<IdentityResolver> identities; private final ObjectProvider<HmacKeyReferenceResolver> keys;
    private final ObjectProvider<AuditSink> audit; private final ObjectProvider<PrivacyMetrics> metrics;
    private final ObjectProvider<Set<String>> configuredJsonSourceNames;
    DataPrismContractValidator(DataPrismProperties p,ObjectProvider<DataSourceAdapter<?>> a,ObjectProvider<IdentityResolver> i,ObjectProvider<HmacKeyReferenceResolver> k,ObjectProvider<AuditSink> au,ObjectProvider<PrivacyMetrics> m,
            @Qualifier(CONFIGURED_JSON_SOURCE_NAMES_BEAN) ObjectProvider<Set<String>> j){properties=p;adapters=a;identities=i;keys=k;audit=au;metrics=m;configuredJsonSourceNames=j;}
    @Override public void afterPropertiesSet() {
        properties.validate();
        validateIntegrations(properties, adapters.orderedStream().toList(), identities, keys, audit, metrics, configuredJsonSourceNames);
    }
    /**
     * Matches {@code data-prism-connectors-rest}'s {@code
     * ConfiguredJsonSourcesAutoConfiguration.CONFIGURED_JSON_SOURCE_NAMES_BEAN}
     * by literal value, not by importing that class's constant: this module
     * keeps no compile dependency, optional or otherwise, on that one at all
     * (see {@code ConfiguredJsonSourcesAutoConfiguration#configuredJsonSourceNames}
     * for why). A {@code @Qualifier} whose value matches no explicit qualifier
     * annotation on any candidate bean falls back to matching by bean name,
     * which is what lets this resolve the one bean that module registers
     * under that name without ever naming that module's class here.
     */
    private static final String CONFIGURED_JSON_SOURCE_NAMES_BEAN = "dataPrismConfiguredJsonSourceNames";
    static void validateIntegrations(DataPrismProperties properties, java.util.List<DataSourceAdapter<?>> adapterList,
            ObjectProvider<IdentityResolver> identities, ObjectProvider<HmacKeyReferenceResolver> keys,
            ObjectProvider<AuditSink> audit, ObjectProvider<PrivacyMetrics> metrics,
            ObjectProvider<Set<String>> configuredJsonSourceNames) {
        if (properties.getTransport().isFixtureDevelopment()
                && properties.getTransport().getMode() == DataPrismProperties.Transport.Mode.STDIO) return;
        Set<String> configured=properties.getSources().keySet(); Set<String> supplied=adapterList.stream().map(DataSourceAdapter::sourceName).collect(Collectors.toSet());
        if(configured.isEmpty() && supplied.isEmpty()) throw new DataPrismConfigurationException("MISSING_SOURCE_ADAPTER","dataprism.sources must name at least one reviewed adapter, or a configured JSON source must supply one");
        // A configured JSON source (io.github.aindriub.dataprism.connectors.rest)
        // registers a DataSourceAdapter bean with no matching dataprism.sources
        // entry at all -- its transport lives solely in its own catalogue, not
        // here -- so `supplied` legitimately outgrows `configured`. That
        // exemption is restored here narrowed to exactly the names that
        // mechanism itself publishes (the "dataPrismConfiguredJsonSourceNames"
        // bean data-prism-connectors-rest registers when present), not --
        // as an earlier, since-corrected relaxation let through -- every
        // DataSourceAdapter bean on the classpath, reviewed or not.
        if(!supplied.containsAll(configured)) throw new DataPrismConfigurationException("UNRESOLVED_SOURCE_ADAPTER","configured sources and DataSourceAdapter beans differ");
        Set<String> reviewed = new HashSet<>(configured);
        Set<String> catalogueNames = configuredJsonSourceNames.getIfAvailable();
        if (catalogueNames != null) reviewed.addAll(catalogueNames);
        for (String name : supplied) {
            if (!reviewed.contains(name)) {
                throw new DataPrismConfigurationException("UNREVIEWED_SOURCE_ADAPTER",
                        "DataSourceAdapter '" + name + "' is named neither by dataprism.sources"
                                + " nor supplied by the JSON-catalogue mechanism, so it is not a reviewed adapter");
            }
        }
        if(identities.getIfAvailable()==null) throw new DataPrismConfigurationException("MISSING_IDENTITY_RESOLVER","provide an IdentityResolver bean");
        if(keys.getIfAvailable()==null) throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER","provide an HmacKeyReferenceResolver bean for the configured reference");
        if(audit.getIfAvailable()==null) {
            // approved-sink is validate()'s deployment-supplied-bean contract (see its
            // Javadoc on DataPrismProperties); this is the phase that can actually see
            // whether the deployment kept it.
            if (DataPrismProperties.APPROVED_SINK.equals(properties.getAudit().getSink())) {
                throw new DataPrismConfigurationException("AUDIT_SINK_BEAN_REQUIRED",
                        "dataprism.audit.sink=" + DataPrismProperties.APPROVED_SINK
                                + " requires the deployment to supply an AuditSink bean");
            }
            // Task 69: this arm is unreachable through the wiring this module ships.
            // DataPrismProperties.validate() admits exactly three dataprism.audit.sink
            // values -- approved-sink (handled above), slf4j and hash-chained -- and
            // DataPrismAutoConfiguration.AuditSinkSelection declares both slf4j's and
            // hash-chained's AuditSink beans @ConditionalOnMissingBean, so one of them
            // always supplies a bean unless an application-supplied AuditSink bean
            // already won, in which case a bean exists either way. It is kept only as
            // a defensive fail-closed guard should a future accepted sink value add no
            // bean of its own before this method is updated to match; it does not
            // describe a path any deployment can reach today.
            throw new DataPrismConfigurationException("MISSING_AUDIT_SINK","provide an AuditSink bean");
        }
        if(metrics.getIfAvailable()==null) throw new DataPrismConfigurationException("MISSING_METRICS_BINDING","provide a PrivacyMetrics bean");
    }
}
