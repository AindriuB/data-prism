package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Wiring leaf for the reviewed privacy pipeline. It deliberately creates no
 * connector: applications must provide source adapters and identity resolution.
 * The HTTP transport is built only through {@link DataPrismMcpServer}, which
 * owns the single scrubbed MCP mapper.
 *
 * <p>This class declares no bean itself: it names, in registration order, the
 * package-private configuration classes that do, one per concern. The order
 * matters. {@code @ConditionalOnMissingBean} and {@code @ConditionalOnBean}
 * resolve against what is already registered, so a class that selects a bean
 * from a property comes before every class whose conditions look for that bean:
 * <ol>
 *   <li>{@link IdentityResolverSelection} and {@link AuditSinkSelection} register the
 *       built-in identity resolver and audit sinks ahead of everything that is conditional
 *       on one existing, so the property value that selects them is already reflected.
 *   <li>{@link AuditIntegrityHealth}, {@link ClusterBackedState} and
 *       {@link ReidentificationWiring} are the optional-dependency groups (Actuator,
 *       Hazelcast, the re-identification module), registered ahead of the in-memory
 *       fall-backs that yield to them.
 *   <li>{@link Preflights} are the {@code BeanFactoryPostProcessor} refusals, which run
 *       before any singleton exists.
 *   <li>{@link PropertiesValidation}, {@link PrivacyEngineWiring}, {@link SecurityWiring},
 *       {@link AuditWiring}, {@link ScopeBudgetWiring}, {@link OversightWiring},
 *       {@link OrchestrationWiring} and {@link McpTransportWiring} are the pipeline itself, in
 *       the order its beans depend on one another.
 * </ol>
 */
@AutoConfiguration
@EnableConfigurationProperties(DataPrismProperties.class)
@Import({IdentityResolverSelection.class, AuditSinkSelection.class, AuditIntegrityHealth.class,
        ClusterBackedState.class, ReidentificationWiring.class, Preflights.class, PropertiesValidation.class,
        PrivacyEngineWiring.class, SecurityWiring.class, AuditWiring.class, ScopeBudgetWiring.class,
        OversightWiring.class, OrchestrationWiring.class, McpTransportWiring.class})
public class DataPrismAutoConfiguration {
}
