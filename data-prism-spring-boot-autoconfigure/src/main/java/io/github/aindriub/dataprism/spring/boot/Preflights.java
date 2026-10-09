package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;



/**
 * The {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor} refusals that must run before
 * any singleton is created. Their relative order is the order they were declared in on
 * {@link DataPrismAutoConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
class Preflights {
    /**
     * Resolve this before singleton creation: an empty protected pipeline is never
     * valid. Also refuses an unrecognised {@code dataprism.identity.resolver}
     * value outright rather than silently falling back to pass-through, or to the
     * generic {@code MISSING_IDENTITY_RESOLVER} below. A recognised value never
     * reaches this check with no {@link IdentityResolver} bean present: see
     * {@link IdentityResolverSelection}.
     */
    @Bean
    static BeanFactoryPostProcessor dataPrismIdentityResolverPreflight(Environment environment) {
        return factory -> {
            String resolver = environment.getProperty("dataprism.identity.resolver");
            if (resolver != null && !resolver.isBlank() && !"pass-through".equals(resolver)) {
                throw new DataPrismConfigurationException("UNSUPPORTED_IDENTITY_RESOLVER",
                        "dataprism.identity.resolver must be one of: pass-through");
            }
            if (factory.getBeanNamesForType(IdentityResolver.class, true, false).length == 0) {
                throw new DataPrismConfigurationException("MISSING_IDENTITY_RESOLVER", "provide an IdentityResolver bean");
            }
        };
    }

    /** Resolve this before singleton creation, same reasoning as {@link #dataPrismIdentityResolverPreflight()}. */
    @Bean
    static BeanFactoryPostProcessor dataPrismPrivacyPolicyResolverPreflight() {
        return factory -> {
            if (factory.getBeanNamesForType(PrivacyPolicyResolver.class, true, false).length > 1) {
                throw new DataPrismConfigurationException("FORBIDDEN_PRIVACY_OVERRIDE",
                        "an application PrivacyPolicyResolver bean cannot replace the framework's profile-backed resolver");
            }
        };
    }

    /**
     * Fail closed on a missing module. {@link ReidentificationWiring} is
     * {@code @ConditionalOnClass}, so with {@code dataprism.reidentification.enabled=true} and
     * {@code data-prism-reidentification} absent from the classpath (the starter makes it
     * optional) the wiring would be skipped and startup would succeed with no service, while the
     * operator surface reports re-identification as switched on. Refuse instead.
     *
     * <p>Reads the raw {@code Environment} and checks the class by name, for the reasons given on
     * {@link #dataPrismSharedBudgetPreflight}; this class never references the type here, because
     * it is exactly the type that may be absent.
     */
    @Bean
    static BeanFactoryPostProcessor dataPrismReidentificationModulePreflight(Environment environment) {
        return factory -> {
            if ("true".equalsIgnoreCase(environment.getProperty("dataprism.reidentification.enabled"))
                    && !org.springframework.util.ClassUtils.isPresent(
                            "io.github.aindriub.dataprism.reidentification.ReidentificationService",
                            factory.getBeanClassLoader())) {
                throw new DataPrismConfigurationException("REIDENTIFICATION_MODULE_MISSING",
                        "dataprism.reidentification.enabled=true requires data-prism-reidentification"
                                + " on the classpath");
            }
        };
    }

    /**
     * Resolve this before singleton creation, same reasoning as
     * {@link #dataPrismIdentityResolverPreflight()}: an {@code embedded} topology
     * that silently ends up with no shared budget — because the optional
     * {@code data-prism-hazelcast} dependency is missing — is the exact defect
     * this task exists to close. {@code single-node} is unaffected: it never
     * depends on the missing classes, so it is never silently short a bean here.
     *
     * <p>Reads the raw {@code Environment} property rather than the bound
     * {@link DataPrismProperties} bean: forcing that bean's creation this early,
     * before {@code ConfigurationPropertiesBindingPostProcessor} is registered as
     * a {@code BeanPostProcessor} later in refresh, would hand every later
     * injection point an instance whose fields were never bound at all.
     */
    @Bean
    static BeanFactoryPostProcessor dataPrismSharedBudgetPreflight(Environment environment) {
        return factory -> {
            if ("embedded".equals(environment.getProperty("dataprism.hazelcast.topology"))
                    && factory.getBeanNamesForType(ScopeBudget.class, true, false).length == 0) {
                throw new DataPrismConfigurationException("MISSING_SHARED_BUDGET",
                        "dataprism.hazelcast.topology=embedded requires data-prism-hazelcast on the classpath");
            }
        };
    }

    /**
     * Resolve this before singleton creation, same reasoning as
     * {@link #dataPrismIdentityResolverPreflight()}. Checks whether the {@link
     * McpTransportWiring#dataPrismHttpTransportValidated} bean definition exists after conditions are
     * evaluated, rather than re-deriving the servlet/property check directly, so it
     * also catches a WebFlux application and a missing servlet dependency, not only
     * {@code spring.main.web-application-type=none}. Not a check for {@link
     * McpSyncServer} itself: that bean is additionally gated on an {@link
     * McpTransportContextExtractor}, and a servlet application missing only that
     * already gets the more specific {@code MISSING_CALLER_CONTEXT_EXTRACTOR} from
     * {@link McpTransportWiring#dataPrismHttpTransportValidated} instead — this preflight must not
     * shadow that. Excludes {@code dataprism.transport.mode=stdio}, which {@link
     * McpTransportWiring#dataPrismStdioTransportRefused} already refuses with a more specific message.
     *
     * <p>Four codes now mean "this deployment has no usable MCP transport", each at
     * a different layer with different remediation advice:
     * <ul>
     *   <li>{@code STDIO_DEVELOPMENT_ONLY} ({@link DataPrismProperties#validate()})
     *       — stdio requested without {@code fixture-development=true}.
     *   <li>{@code STDIO_TRANSPORT_UNSUPPORTED} ({@link McpTransportWiring#dataPrismStdioTransportRefused})
     *       — stdio requested inside a Spring context, which has no stdio wiring.
     *   <li>{@code STANDALONE_HTTP_ONLY} ({@code ServerIntegrationsConfiguration}
     *       in {@code data-prism-server}) — the standalone server only supports
     *       protected HTTP deployments.
     *   <li>{@code MCP_TRANSPORT_UNAVAILABLE} (here) — HTTP requested or defaulted,
     *       but this application is not a servlet web application.
     * </ul>
     * A consumer keying on "no usable MCP transport" must match all four.
     */
    @Bean
    static BeanFactoryPostProcessor dataPrismMcpTransportPreflight(Environment environment) {
        return factory -> {
            String mode = environment.getProperty("dataprism.transport.mode");
            boolean stdio = mode != null
                    && TransportProperties.Mode.STDIO.name().equalsIgnoreCase(mode.trim());
            if (stdio) {
                return;
            }
            if (!factory.containsBeanDefinition("dataPrismHttpTransportValidated")) {
                throw new DataPrismConfigurationException("MCP_TRANSPORT_UNAVAILABLE",
                        "no MCP transport is registered for this application: the HTTP transport requires"
                                + " a servlet web application");
            }
        };
    }
}
