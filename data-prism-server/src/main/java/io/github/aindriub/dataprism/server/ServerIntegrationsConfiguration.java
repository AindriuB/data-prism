package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/** Production integrations that are independent of an organisation's source schema. */
@Configuration(proxyBeanMethods = false)
class ServerIntegrationsConfiguration {
    @Bean
    Object standaloneTransportValidated(DataPrismProperties properties) {
        if (properties.getTransport().isFixtureDevelopment()
                || properties.getTransport().getMode() != DataPrismProperties.Transport.Mode.HTTP) {
            throw new DataPrismConfigurationException("STANDALONE_HTTP_ONLY",
                    "data-prism-server supports protected HTTP deployments only");
        }
        return new Object();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dataprism.privacy.hmac-key", name = "environment-variable")
    HmacKeyReferenceResolver environmentHmacKeyReferenceResolver() {
        return (keyId, reference) -> {
            String value = System.getenv(reference);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("configured HMAC environment variable is unavailable");
            }
            return value.getBytes(StandardCharsets.UTF_8);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    HmacKeyReferenceResolver missingHmacKeyReferenceResolver() {
        return (keyId, reference) -> {
            throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER",
                    "provider-reference requires a reviewed HmacKeyReferenceResolver extension");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dataprism.audit", name = "sink", havingValue = "slf4j")
    AuditSink auditSink() {
        return new Slf4jAuditSink();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dataprism.metrics", name = "sink", havingValue = "micrometer")
    PrivacyMetrics privacyMetrics(MeterRegistry registry) {
        return new ServerPrivacyMetrics(registry);
    }
}
