package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.FileAuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.EntityCorrelationService;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptors;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

/**
 * Wiring leaf for the reviewed privacy pipeline. It deliberately creates no
 * connector: applications must provide source adapters and identity resolution.
 * The HTTP transport is built only through {@link DataPrismMcpServer}, which
 * owns the single scrubbed MCP mapper.
 */
@AutoConfiguration
@EnableConfigurationProperties(DataPrismProperties.class)
@Import({DataPrismAutoConfiguration.IdentityResolverSelection.class, DataPrismAutoConfiguration.AuditSinkSelection.class})
public class DataPrismAutoConfiguration {
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

    /**
     * Selects the built-in {@link PassThroughIdentityResolver} for an operator
     * with no Java to write, opt-in only. {@code @ConditionalOnMissingBean} is the
     * deliberate choice for acceptance item 4: an application-supplied
     * {@link IdentityResolver} always wins over this one, never producing two.
     * Its own {@code @Bean} method is declared on this nested, imported class —
     * rather than directly on {@link DataPrismAutoConfiguration} — purely for
     * bean-definition ordering: imported ahead of it (see the class-level
     * {@code @Import} above), its conditional bean definition, when the property
     * selects it, is registered before {@code DataPrismAutoConfiguration}'s own
     * {@code @Bean} methods run, so {@code @ConditionalOnMissingBean} here and
     * {@code @ConditionalOnBean(IdentityResolver.class)} on beans declared below
     * it both see the correct, final state rather than racing bean processing
     * order.
     */
    @Configuration(proxyBeanMethods = false)
    static class IdentityResolverSelection {
        @Bean
        @ConditionalOnMissingBean(IdentityResolver.class)
        @ConditionalOnProperty(prefix = "dataprism.identity", name = "resolver", havingValue = "pass-through")
        IdentityResolver dataPrismPassThroughIdentityResolver() {
            return new PassThroughIdentityResolver();
        }
    }

    /**
     * Selects a built-in {@link AuditSink} for an operator with no Java to
     * write, mirroring {@link IdentityResolverSelection} exactly, including
     * why its {@code @Bean} methods live here rather than directly on {@link
     * DataPrismAutoConfiguration}: without the same import-ordering trick,
     * {@link #dataPrismAuditRecorder}'s own {@code
     * @ConditionalOnBean(AuditSink.class)} would be evaluated before either
     * bean below is registered, and would never see the one the configured
     * sink value should have produced.
     */
    @Configuration(proxyBeanMethods = false)
    static class AuditSinkSelection {
        /**
         * Nothing read from a source payload reaches this sink; see {@link
         * Slf4jAuditSink}'s own class Javadoc for why that is what makes it
         * safe to ship to ordinary log infrastructure.
         */
        @Bean
        @ConditionalOnMissingBean(AuditSink.class)
        @ConditionalOnProperty(prefix = "dataprism.audit", name = "sink", havingValue = "slf4j")
        AuditSink dataPrismSlf4jAuditSink() {
            return new Slf4jAuditSink();
        }

        /**
         * Wires {@code dataprism.audit.sink=hash-chained} to task 64's {@link
         * FileAuditSink}, bound to {@code dataprism.audit.file-path}. A blank
         * or missing path is already refused earlier, at {@link
         * DataPrismProperties#validate()}, with {@code
         * MISSING_AUDIT_FILE_PATH} — this method only ever runs with a
         * non-blank value. A path that cannot actually be opened (a parent
         * directory that does not exist, or one this process cannot write
         * to) is refused here instead, at startup, rather than surfacing on
         * the first audited request: {@link FileAuditSink.OpenFailedException}
         * is caught and re-thrown as a {@link DataPrismConfigurationException}
         * with a stable code, deliberately without repeating the configured
         * path in the message — the same choice {@link
         * #dataPrismModelDescriptors} already makes for {@code
         * dataprism.privacy.descriptor-file} — so a value an operator chose
         * never reaches whatever renders this refusal.
         *
         * <p><b>What this does not close.</b> This bean's own construction
         * failure is a startup refusal: it is never reachable by an MCP
         * client, because the application never finishes starting. A
         * <em>runtime</em> write failure from the sink this method returns is
         * a different matter, and this task does not close it: {@link
         * FileAuditSink}'s own exceptions deliberately name the configured
         * path — its own acceptance list requires that — and the code that
         * turns a thrown {@link AuditSink} exception into an MCP tool
         * failure response propagates a sink's raw exception message
         * verbatim to the client, proven by {@code
         * AuditSinkFailureAbortsResponseTest} in {@code
         * data-prism-integration-tests}. Wiring a real file path behind
         * {@code hash-chained} makes that latent disclosure reachable for
         * the first time in a default deployment; the file that would need
         * to change to close it is the response-mapping code that test
         * pins, which this task does not own.
         */
        @Bean
        @ConditionalOnMissingBean(AuditSink.class)
        @ConditionalOnProperty(prefix = "dataprism.audit", name = "sink", havingValue = "hash-chained")
        AuditSink dataPrismHashChainedAuditSink(DataPrismProperties properties) {
            Path path = Path.of(properties.getAudit().getFilePath());
            try {
                return new FileAuditSink(path);
            } catch (FileAuditSink.OpenFailedException e) {
                throw new DataPrismConfigurationException("AUDIT_SINK_FILE_UNUSABLE",
                        "dataprism.audit.file-path could not be opened for the hash-chained audit sink");
            }
        }
    }
    @Bean
    Object dataPrismPropertiesValidated(DataPrismProperties properties, List<DataSourceAdapter<?>> adapters,
            ObjectProvider<IdentityResolver> identities, ObjectProvider<HmacKeyReferenceResolver> keys,
            ObjectProvider<AuditSink> audit, ObjectProvider<PrivacyMetrics> metrics) {
        properties.validate();
        DataPrismContractValidator.validateIntegrations(properties, adapters, identities, keys, audit, metrics);
        validateProfile(properties);
        validateKey(properties, keys.getIfAvailable());
        return new Object();
    }
    @Bean
    DataPrismContractValidator dataPrismContractValidator(DataPrismProperties properties,
            ObjectProvider<DataSourceAdapter<?>> adapters, ObjectProvider<IdentityResolver> identities,
            ObjectProvider<HmacKeyReferenceResolver> keys, ObjectProvider<AuditSink> audit,
            ObjectProvider<PrivacyMetrics> metrics) {
        return new DataPrismContractValidator(properties, adapters, identities, keys, audit, metrics);
    }

    @Bean @ConditionalOnMissingBean
    Clock dataPrismClock() { return Clock.systemUTC(); }

    /**
     * The descriptor path only ever tightens what {@link DefaultFieldMetadataResolver}
     * would have said, per {@code DescriptorFieldMetadataResolver}'s own contract, and
     * every way the configured file can be wrong refuses startup rather than silently
     * falling back to the default resolver: see {@link #dataPrismModelDescriptors}.
     */
    @Bean @ConditionalOnMissingBean
    FieldMetadataResolver dataPrismFieldMetadataResolver(DataPrismProperties properties) {
        DefaultFieldMetadataResolver defaultResolver = new DefaultFieldMetadataResolver();
        String descriptorFile = properties.getPrivacy().getDescriptorFile();
        if (descriptorFile == null || descriptorFile.isBlank()) {
            return defaultResolver;
        }
        return new DescriptorFieldMetadataResolver(defaultResolver, dataPrismModelDescriptors(descriptorFile));
    }

    /**
     * Loaded and validated eagerly, rather than left to the first {@code resolve()}
     * call, so a bad file refuses startup instead of surfacing on the first request.
     * No line of the descriptor file itself ever reaches an exception message: only
     * the property name and the shape of the problem do.
     */
    private static Map<String, ModelDescriptor> dataPrismModelDescriptors(String descriptorFile) {
        Path path = Path.of(descriptorFile);
        if (!Files.exists(path)) {
            throw new DataPrismConfigurationException("MODEL_DESCRIPTOR_FILE_NOT_FOUND",
                    "dataprism.privacy.descriptor-file does not name a file that exists");
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new DataPrismConfigurationException("MODEL_DESCRIPTOR_FILE_UNREADABLE",
                    "dataprism.privacy.descriptor-file does not name a readable file");
        }
        Map<String, ModelDescriptor> descriptors;
        try (InputStream in = Files.newInputStream(path)) {
            descriptors = ModelDescriptors.fromYaml(in);
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new DataPrismConfigurationException("INVALID_MODEL_DESCRIPTOR_FILE",
                    "dataprism.privacy.descriptor-file could not be parsed");
        }
        for (ModelDescriptor descriptor : descriptors.values()) {
            if (descriptor.undeclaredFields() == UndeclaredFields.NON_SENSITIVE) {
                throw new DataPrismConfigurationException("UNSAFE_MODEL_DESCRIPTOR_UNDECLARED_FIELDS",
                        "dataprism.privacy.descriptor-file sets undeclaredFields: NON_SENSITIVE for a model,"
                                + " which a descriptor may never do");
            }
        }
        return descriptors;
    }

    @Bean @ConditionalOnMissingBean @DependsOn("dataPrismPropertiesValidated")
    Vocabulary dataPrismVocabulary(DataPrismProperties properties) {
        VocabularyRegistry registry = VocabularyRegistry.withBuiltIns();
        String locale = "neutral".equals(properties.getPrivacy().getLocale()) ? "und" : properties.getPrivacy().getLocale();
        if (!registry.locales().contains(locale) && !registry.locales().contains(locale == null ? "" : locale.split("-")[0])) {
            throw new DataPrismConfigurationException("UNSUPPORTED_LOCALE", "dataprism.privacy.locale");
        }
        return registry.resolve(locale);
    }

    @Bean @ConditionalOnMissingBean
    PseudonymisationVersion dataPrismPseudonymisationVersion(DataPrismProperties properties, Vocabulary vocabulary) {
        return PseudonymisationVersion.HMAC_SHA256_V1.withKey(properties.getPrivacy().getHmacKey().getKeyId()).withVocabulary(vocabulary.id());
    }

    @Bean @ConditionalOnMissingBean
    SyntheticValueSource dataPrismSyntheticValueSource(SecretKeyProvider keys, Vocabulary vocabulary) { return new HmacSyntheticGenerator(keys, vocabulary); }
    @Bean @ConditionalOnMissingBean
    ValueTokenSource dataPrismValueTokenSource(SecretKeyProvider keys) { return new HmacValueTokenSource(keys); }
    @Bean @Primary @ConditionalOnBean(HmacKeyReferenceResolver.class)
    SecretKeyProvider dataPrismSecretKeyProvider(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        String reference = properties.getPrivacy().getHmacKey().getEnvironmentVariable();
        if (reference == null || reference.isBlank()) reference = properties.getPrivacy().getHmacKey().getProviderReference();
        return new ConfiguredSecretKeyProvider(properties.getPrivacy().getHmacKey().getKeyId(), reference, resolver);
    }
    /**
     * Not {@code @ConditionalOnMissingBean}: an application bean of this type must
     * never silently replace the profile-backed resolver, so this one is always
     * created and {@link #dataPrismPrivacyPolicyResolverPreflight()} refuses
     * startup if a competing bean exists rather than letting one win quietly.
     */
    @Bean
    PrivacyPolicyResolver dataPrismPrivacyPolicyResolver(DataPrismProperties properties) {
        validateProfile(properties);
        try (var input = DataPrismAutoConfiguration.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
        } catch (IOException e) { throw new IllegalStateException("default privacy profiles could not be loaded", e); }
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
    @Bean @ConditionalOnMissingBean
    JsonTreeScrubbingEngine dataPrismScrubber(FieldMetadataResolver metadata, PrivacyPolicyResolver policy,
                                               SyntheticValueSource synthetics, ValueTokenSource tokens) { return new JsonTreeScrubbingEngine(metadata, policy, synthetics, tokens); }
    /**
     * Not {@code @ConditionalOnMissingBean}: this leak check must always run. An
     * application {@link LlmResponseValidator} bean is additive rather than a
     * replacement, because {@link #dataPrismContextOrchestrator} collects every
     * bean of this type into its validator list instead of taking a single one.
     */
    @Bean
    LlmResponseValidator dataPrismRawValueLeakValidator() { return new RawValueLeakValidator(); }
    @Bean @ConditionalOnMissingBean
    SecurityPolicy dataPrismSecurityPolicy(DataPrismProperties properties) {
        return new SecurityPolicy(Set.copyOf(properties.getSecurityPolicy().getPurposes()), properties.getSecurityPolicy().getRoles().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }
    @Bean @ConditionalOnMissingBean
    AuthorizationService dataPrismAuthorizationService(SecurityPolicy policy, DataPrismProperties properties) { return new AuthorizationService(policy, properties.getPrivacy().getProfile(), io.github.aindriub.dataprism.core.PrivacyScopeType.INVESTIGATION); }
    @Bean @ConditionalOnMissingBean
    ScopeResolver dataPrismScopeResolver(PseudonymisationVersion version, DataPrismProperties properties) { return new ScopeResolver(version, properties.getPrivacy().getScopeLifetime(), new PurposeValidator(Set.copyOf(properties.getSecurityPolicy().getPurposes()))); }
    @Bean @ConditionalOnMissingBean @ConditionalOnBean(AuditSink.class)
    AuditRecorder dataPrismAuditRecorder(AuditSink sink, Clock clock, DataPrismProperties properties) { return new AuditRecorder(sink, clock, properties.getAudit().getWriterId()); }
    /**
     * {@code single-node}, or no topology configured at all (fixture-development,
     * where {@link DataPrismProperties#validate()} never requires one): the budget
     * is enforced once per process. {@code matchIfMissing} covers only the
     * unvalidated dev case — a protected deployment with no topology configured
     * never reaches bean creation, because {@link #dataPrismPropertiesValidated}
     * refuses it first with {@code MISSING_CLUSTER_TOPOLOGY}.
     */
    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean({IdentityResolver.class, SecretKeyProvider.class, AuditSink.class, PrivacyMetrics.class, DataSourceAdapter.class})
    @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "single-node", matchIfMissing = true)
    ScopeBudget dataPrismScopeBudget() { return new InMemoryScopeBudget(); }
    /**
     * {@code embedded}: the budget is enforced once across the cluster, not once
     * per process. {@code @ConditionalOnClass} is what lets this method — and
     * {@link ClusterScopeBudgetConfiguration}, which is the only other class in
     * this package that names a {@code com.hazelcast} type — go unresolved on a
     * {@code single-node} consumer that never put {@code data-prism-hazelcast} on
     * its classpath. When the topology is {@code embedded} and that dependency is
     * absent, no {@link ScopeBudget} bean is created here at all, and
     * {@link #dataPrismSharedBudgetPreflight()} is what turns that silence into a
     * refusal instead of a missing-bean startup failure with no stable code.
     */
    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean({IdentityResolver.class, SecretKeyProvider.class, AuditSink.class, PrivacyMetrics.class, DataSourceAdapter.class})
    @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "embedded")
    @ConditionalOnClass(HazelcastInstance.class)
    ScopeBudget dataPrismClusterScopeBudget(DataPrismProperties properties) {
        return ClusterScopeBudgetConfiguration.build(properties.getHazelcast());
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
    @Bean @ConditionalOnMissingBean
    ContextOrchestrator dataPrismContextOrchestrator(List<DataSourceAdapter<?>> adapters, IdentityResolver identities,
            JsonTreeScrubbingEngine scrubber, FieldMetadataResolver metadata, List<LlmResponseValidator> validators,
            SyntheticValueSource synthetics, ValueTokenSource tokens, SecretKeyProvider keys, AuditRecorder audit,
            ScopeBudget budget, PrivacyMetrics metrics, Clock clock) {
        return new DefaultContextOrchestrator(adapters, scrubber, metadata, List.copyOf(validators), synthetics,
                new ParameterFingerprinter(keys), audit, identities,
                new SourceFanOut(SourceCircuitBreaker.disabled(), clock, metrics), budget, RequestLimits.DEFAULT,
                new NamespaceCorrelationService(metadata), new SourceAliasing(tokens), metrics);
    }
    /**
     * The Spring auto-configuration has no stdio transport of its own: every
     * {@code @Bean} below this point is gated on {@code mode=HTTP}, and nothing
     * here ever calls {@code DataPrismMcpServer.stdio()} — that stays the hand-built
     * {@code data-prism-integration-tests} entry point's job. Without this refusal,
     * {@code dataprism.transport.mode=stdio} with {@code fixture-development=true}
     * passes {@link DataPrismProperties#validate()} and the context would start
     * successfully while serving no MCP transport at all — fail-open. Unconditional,
     * like {@link #dataPrismPropertiesValidated}, so every consumer of the starter
     * inherits it rather than only the standalone server's own
     * {@code standaloneTransportValidated} bean.
     */
    @Bean
    Object dataPrismStdioTransportRefused(DataPrismProperties properties) {
        if (properties.getTransport().getMode() == DataPrismProperties.Transport.Mode.STDIO) {
            throw new DataPrismConfigurationException("STDIO_TRANSPORT_UNSUPPORTED",
                    "the stdio transport has no Spring auto-configuration; dataprism.transport.mode=stdio is refused here");
        }
        return new Object();
    }

    /**
     * Resolve this before singleton creation, same reasoning as
     * {@link #dataPrismIdentityResolverPreflight()}. Checks whether the {@link
     * #dataPrismHttpTransportValidated} bean definition exists after conditions are
     * evaluated, rather than re-deriving the servlet/property check directly, so it
     * also catches a WebFlux application and a missing servlet dependency, not only
     * {@code spring.main.web-application-type=none}. Not a check for {@link
     * McpSyncServer} itself: that bean is additionally gated on an {@link
     * McpTransportContextExtractor}, and a servlet application missing only that
     * already gets the more specific {@code MISSING_CALLER_CONTEXT_EXTRACTOR} from
     * {@link #dataPrismHttpTransportValidated} instead — this preflight must not
     * shadow that. Excludes {@code dataprism.transport.mode=stdio}, which {@link
     * #dataPrismStdioTransportRefused} already refuses with a more specific message.
     *
     * <p>Four codes now mean "this deployment has no usable MCP transport", each at
     * a different layer with different remediation advice:
     * <ul>
     *   <li>{@code STDIO_DEVELOPMENT_ONLY} ({@link DataPrismProperties#validate()})
     *       — stdio requested without {@code fixture-development=true}.
     *   <li>{@code STDIO_TRANSPORT_UNSUPPORTED} ({@link #dataPrismStdioTransportRefused})
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
                    && DataPrismProperties.Transport.Mode.STDIO.name().equalsIgnoreCase(mode.trim());
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
            PrivacyMetrics metrics, AuditRecorder audit, Clock clock, DataPrismProperties properties) {
        return DataPrismMcpServer.streamableHttp(orchestrator, authorization, scopeResolver, extractor,
                properties.getTransport().getHttp().getPath(), metrics, audit, clock);
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

    private static void validateProfile(DataPrismProperties properties) {
        try (var input = DataPrismAutoConfiguration.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            if (!PrivacyProfiles.fromYaml(input).containsKey(properties.getPrivacy().getProfile())) {
                throw new DataPrismConfigurationException("UNKNOWN_PRIVACY_PROFILE", properties.getPrivacy().getProfile());
            }
        } catch (IOException e) { throw new IllegalStateException("default privacy profiles could not be loaded", e); }
    }

    private static void validateKey(DataPrismProperties properties, HmacKeyReferenceResolver provider) {
        if (provider == null) throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER", "provide an HmacKeyReferenceResolver bean for the configured reference");
        String reference = properties.getPrivacy().getHmacKey().getEnvironmentVariable();
        if (reference == null || reference.isBlank()) reference = properties.getPrivacy().getHmacKey().getProviderReference();
        try {
            byte[] key = provider.resolve(properties.getPrivacy().getHmacKey().getKeyId(), reference);
            if (key == null || key.length < 32) throw new DataPrismConfigurationException("HMAC_KEY_WEAK", "configured key material is shorter than 32 bytes");
            Arrays.fill(key, (byte) 0);
        } catch (DataPrismConfigurationException e) { throw e;
        } catch (RuntimeException e) { throw new DataPrismConfigurationException("HMAC_KEY_UNRESOLVED", "configured HMAC key reference could not be resolved"); }
    }
}
