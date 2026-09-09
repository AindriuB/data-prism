package io.github.aindriub.dataprism.example.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.example.DataPrismAssembly;
import io.github.aindriub.dataprism.example.StubAccountAdapter;
import io.github.aindriub.dataprism.example.StubCustomerAdapter;
import io.github.aindriub.dataprism.example.StubOrderAdapter;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires the pipeline the same way {@code ExampleApplication} does for stdio,
 * substituting a per-request {@link JwtCallerContextExtractor} for stdio's
 * single development caller.
 *
 * <p>Every bean here that a test might reasonably want to replace —
 * {@link Clock}, {@link AuditSink}, {@link ContextOrchestrator},
 * {@link PrivacyMetrics} — is declared with {@link ConditionalOnMissingBean},
 * so a test-only configuration class registered ahead of this one in the
 * {@code SpringApplicationBuilder} source list wins without needing Spring's
 * bean-definition overriding disabled.
 */
@Configuration
@EnableConfigurationProperties({JwtSecurityProperties.class, SecurityPolicyProperties.class})
class McpAssemblyConfig {

    /** Matches {@code ExampleApplication}'s stdio wiring: one profile, one scope type. */
    private static final String PRIVACY_PROFILE = "DEFAULT";

    /**
     * Distinct from {@code ExampleApplication}'s stdio tool audit instance
     * ({@code example-1-mcp}) and from {@code DataPrismAssembly}'s own internal
     * orchestrator instance ({@code example-1}), so an audit reader can tell
     * which layer, and which transport, wrote a given event.
     */
    private static final String TOOL_AUDIT_INSTANCE_ID = "example-1-http";

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    AuditSink auditSink() {
        return new Slf4jAuditSink();
    }

    @Bean
    DataPrismAssembly dataPrismAssembly(Clock clock, AuditSink auditSink) {
        return new DataPrismAssembly(
                List.of(new StubCustomerAdapter(), new StubAccountAdapter(), new StubOrderAdapter()),
                clock, auditSink);
    }

    @Bean
    @ConditionalOnMissingBean(ContextOrchestrator.class)
    ContextOrchestrator orchestrator(DataPrismAssembly assembly) {
        return assembly.orchestrator();
    }

    /**
     * Re-serialises the bound {@link SecurityPolicyProperties} and hands it to
     * {@link SecurityPolicy#fromYaml}, which is the only place that validates a
     * purpose list or a role's capabilities — see that record's own contract.
     */
    @Bean
    SecurityPolicy securityPolicy(SecurityPolicyProperties properties) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("purposes", properties.purposes());
        root.put("roles", properties.roles());

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            yaml.writeValue(bytes, root);
        } catch (IOException e) {
            throw new UncheckedIOException("security policy from configuration could not be re-serialised", e);
        }
        return SecurityPolicy.fromYaml(new ByteArrayInputStream(bytes.toByteArray()));
    }

    @Bean
    AuthorizationService authorizationService(SecurityPolicy policy) {
        return new AuthorizationService(policy, PRIVACY_PROFILE, PrivacyScopeType.INVESTIGATION);
    }

    @Bean
    ScopeResolver scopeResolver(DataPrismAssembly assembly, SecurityPolicyProperties properties) {
        return new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.copyOf(properties.purposes())));
    }

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry meterRegistry() {
        // No spring-boot-starter-actuator here, so nothing auto-configures
        // this — and nothing binds JVM/system metrics onto it either, which is
        // what lets a test assert the registry holds exactly Metric's names.
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(PrivacyMetrics.class)
    PrivacyMetrics privacyMetrics(MeterRegistry registry) {
        return new MicrometerPrivacyMetrics(registry);
    }

    @Bean
    AuditRecorder toolAuditRecorder(AuditSink auditSink, Clock clock) {
        return new AuditRecorder(auditSink, clock, TOOL_AUDIT_INSTANCE_ID);
    }

    @Bean
    JwtCallerContextExtractor jwtCallerContextExtractor() {
        return new JwtCallerContextExtractor();
    }

    @Bean
    DataPrismMcpServer.HttpTransport mcpHttpTransport(ContextOrchestrator orchestrator,
            AuthorizationService authorizationService, ScopeResolver scopeResolver,
            JwtCallerContextExtractor contextExtractor, PrivacyMetrics metrics,
            AuditRecorder toolAuditRecorder, Clock clock) {
        return DataPrismMcpServer.streamableHttp(orchestrator, authorizationService, scopeResolver,
                contextExtractor, metrics, toolAuditRecorder, clock);
    }

    /**
     * {@code destroyMethod} so the server — and the transport it owns — closes
     * when the application context does, rather than leaving a session map or a
     * keep-alive scheduler thread running past the end of a test.
     */
    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpSyncServer(DataPrismMcpServer.HttpTransport transport) {
        return transport.server();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            DataPrismMcpServer.HttpTransport transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport.transportProvider(), "/mcp");
        // The transport streams responses over Server-Sent Events using an
        // AsyncContext; without this the servlet container refuses startAsync()
        // with an IllegalStateException on the first request.
        registration.setAsyncSupported(true);
        return registration;
    }
}
