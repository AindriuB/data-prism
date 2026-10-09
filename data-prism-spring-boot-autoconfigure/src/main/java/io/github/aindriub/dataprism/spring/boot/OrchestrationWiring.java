package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/** Correlation MDC and the context orchestrator. */
@Configuration(proxyBeanMethods = false)
class OrchestrationWiring {
    /** Off unless {@code dataprism.correlation.mdc-key} is set; validated by {@link DataPrismProperties#validate()}. */
    @Bean @ConditionalOnMissingBean
    CorrelationMdc dataPrismCorrelationMdc(DataPrismProperties properties) {
        String key = properties.getCorrelation().getMdcKey();
        return key == null ? CorrelationMdc.off() : CorrelationMdc.of(key);
    }

    @Bean @ConditionalOnMissingBean
    ContextOrchestrator dataPrismContextOrchestrator(List<DataSourceAdapter<?>> adapters, IdentityResolver identities,
            JsonTreeScrubbingEngine scrubber, FieldMetadataResolver metadata, List<LlmResponseValidator> validators,
            SyntheticValueSource synthetics, ValueTokenSource tokens, SecretKeyProvider keys, AuditRecorder audit,
            ScopeBudget budget, PrivacyMetrics metrics, Clock clock, ParameterFingerprinter fingerprinter,
            CorrelationMdc correlationMdc) {
        return new DefaultContextOrchestrator(adapters, scrubber, metadata, List.copyOf(validators), synthetics,
                fingerprinter, audit, identities,
                new SourceFanOut(SourceCircuitBreaker.disabled(), clock,
                        new SourceFanOutOptions(metrics, correlationMdc)), budget, RequestLimits.DEFAULT,
                new NamespaceCorrelationService(metadata), new SourceAliasing(tokens), metrics);
    }
}
