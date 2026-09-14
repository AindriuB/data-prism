package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.InitializingBean;
import java.util.Set;
import java.util.stream.Collectors;

/** Final cross-checks which property binding alone cannot prove. */
final class DataPrismContractValidator implements InitializingBean {
    private final DataPrismProperties properties; private final ObjectProvider<DataSourceAdapter<?>> adapters;
    private final ObjectProvider<IdentityResolver> identities; private final ObjectProvider<HmacKeyReferenceResolver> keys;
    private final ObjectProvider<AuditSink> audit; private final ObjectProvider<PrivacyMetrics> metrics;
    DataPrismContractValidator(DataPrismProperties p,ObjectProvider<DataSourceAdapter<?>> a,ObjectProvider<IdentityResolver> i,ObjectProvider<HmacKeyReferenceResolver> k,ObjectProvider<AuditSink> au,ObjectProvider<PrivacyMetrics> m){properties=p;adapters=a;identities=i;keys=k;audit=au;metrics=m;}
    @Override public void afterPropertiesSet() {
        properties.validate();
        validateIntegrations(properties, adapters.orderedStream().toList(), identities, keys, audit, metrics);
    }
    static void validateIntegrations(DataPrismProperties properties, java.util.List<DataSourceAdapter<?>> adapterList,
            ObjectProvider<IdentityResolver> identities, ObjectProvider<HmacKeyReferenceResolver> keys,
            ObjectProvider<AuditSink> audit, ObjectProvider<PrivacyMetrics> metrics) {
        if (properties.getTransport().isFixtureDevelopment()) return;
        Set<String> configured=properties.getSources().keySet(); Set<String> supplied=adapterList.stream().map(DataSourceAdapter::sourceName).collect(Collectors.toSet());
        if(configured.isEmpty()) throw new DataPrismConfigurationException("MISSING_SOURCE_ADAPTER","dataprism.sources must name at least one reviewed adapter");
        if(!configured.equals(supplied)) throw new DataPrismConfigurationException("UNRESOLVED_SOURCE_ADAPTER","configured sources and DataSourceAdapter beans differ");
        if(identities.getIfAvailable()==null) throw new DataPrismConfigurationException("MISSING_IDENTITY_RESOLVER","provide an IdentityResolver bean");
        if(keys.getIfAvailable()==null) throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER","provide an HmacKeyReferenceResolver bean for the configured reference");
        if(audit.getIfAvailable()==null) throw new DataPrismConfigurationException("MISSING_AUDIT_SINK","provide an AuditSink bean");
        if(metrics.getIfAvailable()==null) throw new DataPrismConfigurationException("MISSING_METRICS_BINDING","provide a PrivacyMetrics bean");
    }
}
