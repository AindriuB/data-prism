package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.mcp.CorrelationRequirement;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.mcp.ToolOptions;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.time.Clock;
import java.util.Set;

/** The streamable-HTTP MCP transport, its server and its servlet registration. */
@Configuration(proxyBeanMethods = false)
class McpTransportWiring {
    private static final org.slf4j.Logger AUDIT_LOG = org.slf4j.LoggerFactory.getLogger(DataPrismAutoConfiguration.class);


    /**
     * The Spring auto-configuration has no stdio transport of its own: every
     * {@code @Bean} below this point is gated on {@code mode=HTTP}, and nothing
     * here ever calls {@code DataPrismMcpServer.stdio()} — that stays the hand-built
     * {@code data-prism-integration-tests} entry point's job. Without this refusal,
     * {@code dataprism.transport.mode=stdio} with {@code fixture-development=true}
     * passes {@link DataPrismProperties#validate()} and the context would start
     * successfully while serving no MCP transport at all — fail-open. Unconditional,
     * like {@link PropertiesValidation#dataPrismPropertiesValidated}, so every consumer of the starter
     * inherits it rather than only the standalone server's own
     * {@code standaloneTransportValidated} bean.
     */
    @Bean
    Object dataPrismStdioTransportRefused(DataPrismProperties properties) {
        if (properties.getTransport().getMode() == TransportProperties.Mode.STDIO) {
            throw new DataPrismConfigurationException("STDIO_TRANSPORT_UNSUPPORTED",
                    "the stdio transport has no Spring auto-configuration; dataprism.transport.mode=stdio is refused here");
        }
        return new Object();
    }

    @Bean
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    Object dataPrismHttpTransportValidated(
            ObjectProvider<McpTransportContextExtractor<HttpServletRequest>> extractors) {
        if (extractors.getIfAvailable() == null) {
            throw new DataPrismConfigurationException("MISSING_CALLER_CONTEXT_EXTRACTOR",
                    "HTTP transport requires an McpTransportContextExtractor<HttpServletRequest> bean");
        }
        return new Object();
    }

    @Bean @ConditionalOnMissingBean @ConditionalOnBean(McpTransportContextExtractor.class)
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @DependsOn("dataPrismHttpTransportValidated")
    DataPrismMcpServer.HttpTransport dataPrismHttpTransport(ContextOrchestrator orchestrator, AuthorizationService authorization,
            ScopeResolver scopeResolver, McpTransportContextExtractor<HttpServletRequest> extractor,
            PrivacyMetrics metrics, AuditRecorder audit, Clock clock, DataPrismProperties properties,
            ToolAdmission admission, ParameterFingerprinter fingerprinter, CorrelationMdc correlationMdc) {
        AuditedEntityTypes entityTypes = AuditedEntityTypes.of(properties.getAudit().getEntityTypes());
        if (entityTypes.isShapeMode()) {
            // Not a WARN: leaving the list unset is a supported mode, but the shape cannot tell ACC123 from CUSTOMER.
            AUDIT_LOG.info("dataprism.audit.entity-types is not set: the audit record's entityType is kept only when "
                    + "it matches [A-Z][A-Z0-9_]{0,63} and is otherwise recorded as {}. Set "
                    + "dataprism.audit.entity-types to the exact entity types in use.",
                    AuditedEntityTypes.UNREGISTERED);
        }
        ToolOptions options = ToolOptions.defaults()
                .admission(admission, fingerprinter)
                .correlationRequirement(properties.getCorrelation().getInbound().isRequired()
                        ? CorrelationRequirement.REQUIRED : CorrelationRequirement.OPTIONAL)
                .mdc(correlationMdc)
                .entityTypes(entityTypes)
                .build();
        return DataPrismMcpServer.streamableHttp(orchestrator, authorization, scopeResolver, extractor,
                properties.getTransport().getHttp().getPath(), metrics, audit, clock, options);
    }

    @Bean(destroyMethod = "closeGracefully")
    @ConditionalOnBean(DataPrismMcpServer.HttpTransport.class)
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    McpSyncServer dataPrismMcpSyncServer(DataPrismMcpServer.HttpTransport transport) {
        return transport.server();
    }

    @Bean
    @ConditionalOnMissingBean(name = "dataPrismMcpServlet")
    @ConditionalOnBean(DataPrismMcpServer.HttpTransport.class)
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> dataPrismMcpServlet(
            DataPrismMcpServer.HttpTransport transport, DataPrismProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport.transportProvider(),
                        properties.getTransport().getHttp().getPath());
        registration.setAsyncSupported(true);
        return registration;
    }
}
