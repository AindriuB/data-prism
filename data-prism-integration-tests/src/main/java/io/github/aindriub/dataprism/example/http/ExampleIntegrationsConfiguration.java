package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.example.StubAccountAdapter;
import io.github.aindriub.dataprism.example.StubCustomerAdapter;
import io.github.aindriub.dataprism.example.StubOrderAdapter;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/** Reviewed application-owned integrations; the starter assembles and exposes the pipeline. */
@Configuration(proxyBeanMethods = false)
class ExampleIntegrationsConfiguration {

    @Bean
    DataSourceAdapter<?> customerAdapter() {
        return new StubCustomerAdapter();
    }

    @Bean
    DataSourceAdapter<?> accountAdapter() {
        return new StubAccountAdapter();
    }

    @Bean
    DataSourceAdapter<?> orderAdapter() {
        return new StubOrderAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    IdentityResolver identityResolver() {
        return new PassThroughIdentityResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    HmacKeyReferenceResolver hmacKeyReferenceResolver() {
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
    AuditSink auditSink() {
        return new Slf4jAuditSink();
    }

    @Bean
    @ConditionalOnMissingBean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    PrivacyMetrics privacyMetrics(MeterRegistry registry) {
        return new MicrometerPrivacyMetrics(registry);
    }
}
