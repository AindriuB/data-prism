package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;
import io.github.aindriub.dataprism.audit.AuditCheckpointSink;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditRetention;
import io.github.aindriub.dataprism.audit.FileAuditCheckpointSink;
import io.github.aindriub.dataprism.audit.SegmentedFileAuditSink;
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
import io.github.aindriub.dataprism.hazelcast.ClusterMembership;
import io.github.aindriub.dataprism.hazelcast.HazelcastApprovalStore;
import io.github.aindriub.dataprism.hazelcast.HazelcastCallerRateLimiter;
import io.github.aindriub.dataprism.hazelcast.HazelcastOversightState;
import io.github.aindriub.dataprism.hazelcast.HazelcastScopeBudget;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal;
import io.github.aindriub.dataprism.hazelcast.CachingSyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.ScopeIdentityIndex;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.reidentification.Permission;
import io.github.aindriub.dataprism.reidentification.ReidentificationPolicy;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.util.ClassUtils;
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
import java.util.Optional;
import java.util.OptionalInt;
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
@Import({DataPrismAutoConfiguration.IdentityResolverSelection.class, DataPrismAutoConfiguration.AuditSinkSelection.class,
        DataPrismAutoConfiguration.AuditIntegrityHealth.class})
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
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuditSinkSelection.class);

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
         * a different matter: {@link FileAuditSink}'s own exceptions
         * deliberately name the configured path — its own acceptance list
         * requires that — but task 74 closed the disclosure this javadoc used
         * to describe here. {@code GetEntityContextTool} and {@code
         * CompareEntitySourcesTool} now catch a thrown {@link AuditSink}
         * exception at the {@code deny}/{@code denyUnauthenticated} call
         * sites and return a fixed, path-free message, logging the caught
         * exception server-side instead — see {@code
         * AuditSinkFailureAbortsResponseTest} in {@code
         * data-prism-integration-tests}, which now asserts the sink's
         * message and the configured path are both absent from what the
         * client receives, and {@code docs/conventions.md}'s "no `catch`
         * block logs the object it caught" exception for why that server-side
         * log line is sanctioned rather than a leak. What wiring a real file
         * path behind {@code hash-chained} does not close is a narrower gap:
         * {@link io.github.aindriub.dataprism.audit.AuditEventHash} is
         * unkeyed SHA-256, so this chain resists an outside forger but not
         * the operator running this process — anyone with write access to
         * the configured file can recompute every hash in it and produce a
         * chain that still verifies. That gap is recorded and deliberately
         * deprioritised by the owner, not closed by this task; nothing this
         * bean does makes the audit file tamper-proof, immutable, or usable
         * as evidence against the operator.
         */
        @Bean
        @ConditionalOnMissingBean(AuditSink.class)
        @ConditionalOnProperty(prefix = "dataprism.audit", name = "sink", havingValue = "hash-chained")
        AuditSink dataPrismHashChainedAuditSink(DataPrismProperties properties) {
            String directory = properties.getAudit().getDirectory();
            Path path = Path.of(directory == null || directory.isBlank()
                    ? properties.getAudit().getFilePath() : directory);
            try {
                return directory == null || directory.isBlank() ? new FileAuditSink(path)
                        : new SegmentedFileAuditSink(path);
            } catch (FileAuditSink.OpenFailedException e) {
                // Logging the caught exception object here is the same sanctioned
                // exception docs/conventions.md records for GetEntityContextTool and
                // CompareEntitySourcesTool: the cause can name a server filesystem
                // path, so it stays server-side only, never repeated in the
                // DataPrismConfigurationException message thrown below.
                LOG.error("dataprism.audit.file-path or directory could not be opened for the hash-chained audit sink", e);
                throw new DataPrismConfigurationException("AUDIT_SINK_FILE_UNUSABLE",
                        "dataprism.audit.file-path or directory could not be opened for the hash-chained audit sink");
            }
        }
    }
    /**
     * Task 69: {@code configuredJsonSourceNames} is deliberately {@code
     * ObjectProvider<Set<String>>}, not a type declared by {@code
     * data-prism-connectors-rest}. That module registers this bean, when
     * present, under the literal name {@link
     * #CONFIGURED_JSON_SOURCE_NAMES_BEAN} — see {@code
     * ConfiguredJsonSourcesAutoConfiguration#configuredJsonSourceNames} for
     * why a plain JDK type, matched by bean name through {@code @Qualifier}
     * rather than by importing that module's own class, is what keeps this
     * unconditional {@code @Bean} method safe to run on a classpath that
     * genuinely never includes that module at all (the base standalone
     * server's own {@code pom.xml} declares it test-scope only).
     */
    @Bean
    Object dataPrismPropertiesValidated(DataPrismProperties properties, List<DataSourceAdapter<?>> adapters,
            ObjectProvider<IdentityResolver> identities, ObjectProvider<HmacKeyReferenceResolver> keys,
            ObjectProvider<AuditSink> audit, ObjectProvider<PrivacyMetrics> metrics,
            @Qualifier(CONFIGURED_JSON_SOURCE_NAMES_BEAN) ObjectProvider<Set<String>> configuredJsonSourceNames,
            Environment environment, ConfigurableListableBeanFactory beanFactory) {
        properties.validate();
        Integer serverPort = environment.getProperty("server.port", Integer.class, 8080);
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        properties.validateOperatorPort(serverPort, managementPort);
        properties.validateCluster(applicationSuppliesCluster(beanFactory), serverPort, managementPort);
        DataPrismContractValidator.validateIntegrations(properties, adapters, identities, keys, audit, metrics,
                configuredJsonSourceNames);
        validateProfile(properties);
        validateKey(properties, keys.getIfAvailable());
        return new Object();
    }
    private static final String CLUSTER_TYPE = "io.github.aindriub.dataprism.hazelcast.PrivacyCluster";

    /**
     * Ownership is the bean definition's origin, never its name: this auto-configuration's cluster is
     * the one produced by {@link ClusterBackedState}, so an application bean that happens to share
     * the default name is still the application's.
     */
    private static boolean isFrameworkCluster(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!beanFactory.containsBeanDefinition(name)) {
            return false;
        }
        String factoryBean = beanFactory.getBeanDefinition(name).getFactoryBeanName();
        if (factoryBean == null) {
            return false;
        }
        if (beanFactory.containsBeanDefinition(factoryBean)) {
            String factoryClass = beanFactory.getBeanDefinition(factoryBean).getBeanClassName();
            return ClusterBackedState.class.getName().equals(factoryClass);
        }
        return false;
    }

    /**
     * Whether a {@code PrivacyCluster} other than this auto-configuration's own is defined. Looked up
     * by type name so a {@code single-node} consumer without {@code data-prism-hazelcast} never
     * resolves the optional class.
     */
    private static boolean applicationSuppliesCluster(ConfigurableListableBeanFactory beanFactory) {
        if (!ClassUtils.isPresent(CLUSTER_TYPE, beanFactory.getBeanClassLoader())) {
            return false;
        }
        try {
            Class<?> type = ClassUtils.forName(CLUSTER_TYPE, beanFactory.getBeanClassLoader());
            return Arrays.stream(beanFactory.getBeanNamesForType(type, true, false))
                    .anyMatch(name -> !isFrameworkCluster(beanFactory, name));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
    /**
     * Matches {@code data-prism-connectors-rest}'s {@code
     * ConfiguredJsonSourcesAutoConfiguration.CONFIGURED_JSON_SOURCE_NAMES_BEAN}
     * by literal value; see {@link #dataPrismPropertiesValidated}'s Javadoc
     * for why this class never imports that module's own constant or type.
     */
    private static final String CONFIGURED_JSON_SOURCE_NAMES_BEAN = "dataPrismConfiguredJsonSourceNames";
    @Bean
    DataPrismContractValidator dataPrismContractValidator(DataPrismProperties properties,
            ObjectProvider<DataSourceAdapter<?>> adapters, ObjectProvider<IdentityResolver> identities,
            ObjectProvider<HmacKeyReferenceResolver> keys, ObjectProvider<AuditSink> audit,
            ObjectProvider<PrivacyMetrics> metrics,
            @Qualifier(CONFIGURED_JSON_SOURCE_NAMES_BEAN) ObjectProvider<Set<String>> configuredJsonSourceNames) {
        return new DataPrismContractValidator(properties, adapters, identities, keys, audit, metrics,
                configuredJsonSourceNames);
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
    AuditRecorder dataPrismAuditRecorder(AuditSink sink, Clock clock, DataPrismProperties properties,
            ObjectProvider<AuditCheckpointSink> checkpoints) {
        AuditCheckpointSink checkpoint = checkpoints.getIfAvailable();
        return checkpoint == null ? new AuditRecorder(sink, clock, properties.getAudit().getWriterId())
                : new AuditRecorder(sink, clock, properties.getAudit().getWriterId(), checkpoint);
    }
    private static final org.slf4j.Logger AUDIT_LOG = org.slf4j.LoggerFactory.getLogger(DataPrismAutoConfiguration.class);
    /**
     * The checkpoint file, kept apart from the audit file. A path that cannot be opened is logged
     * server-side only; the client-visible message never repeats it.
     */
    @Bean @ConditionalOnMissingBean(AuditCheckpointSink.class)
    @ConditionalOnProperty(prefix = "dataprism.audit.checkpoint", name = "file-path")
    FileAuditCheckpointSink dataPrismAuditCheckpointSink(DataPrismProperties properties,
            ObjectProvider<AuditSink> auditSink) {
        // Resolving the audit sink first makes it create the audit directory. Only then can the
        // containment check compare real paths: on a case-insensitive filesystem a not-yet-created
        // FRESH directory and a checkpoint under fresh/ look unrelated until the directory exists.
        auditSink.getIfAvailable();
        DataPrismProperties.Audit audit = properties.getAudit();
        String auditLocation = audit.getDirectory() != null && !audit.getDirectory().isBlank()
                ? audit.getDirectory() : audit.getFilePath();
        if (auditLocation != null && !auditLocation.isBlank()
                && DataPrismProperties.sameOrInside(audit.getCheckpoint().getFilePath(), auditLocation)) {
            throw new DataPrismConfigurationException(FileAuditCheckpointSink.SAME_AS_AUDIT_FILE,
                    "dataprism.audit.checkpoint.file-path must not be the audit file");
        }
        try {
            return new FileAuditCheckpointSink(Path.of(audit.getCheckpoint().getFilePath()),
                    Path.of(auditLocation == null || auditLocation.isBlank() ? "." : auditLocation));
        } catch (FileAuditCheckpointSink.CheckpointSinkException e) {
            if (FileAuditCheckpointSink.SAME_AS_AUDIT_FILE.equals(e.code())) {
                throw new DataPrismConfigurationException(FileAuditCheckpointSink.SAME_AS_AUDIT_FILE,
                        "dataprism.audit.checkpoint.file-path must not be the audit file");
            }
            AUDIT_LOG.error("dataprism.audit.checkpoint.file-path could not be opened", e);
            throw new DataPrismConfigurationException("AUDIT_CHECKPOINT_FILE_UNUSABLE",
                    "dataprism.audit.checkpoint.file-path could not be opened");
        }
    }
    /** Daily purge of expired segments; present only when {@code dataprism.audit.directory} is set. */
    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dataprism.audit", name = "directory")
    AuditRetention dataPrismAuditRetention(DataPrismProperties properties, Clock clock,
            ObjectProvider<AuditCheckpointSink> checkpoints) {
        DataPrismProperties.Audit audit = properties.getAudit();
        return new AuditRetention(Path.of(audit.getDirectory()), audit.getRetention(),
                checkpoints.getObject(), clock, audit.isRetentionOverride());
    }
    /**
     * Runs the purge once now and then every 24h, and a PERIODIC checkpoint every {@code
     * checkpoint.interval}, the first soon after boot. When it checkpoints, it is registered as
     * depending on the recorder, so on context close it stops (and its last PERIODIC is written)
     * before the recorder writes its SHUTDOWN checkpoint.
     */
    @Bean(destroyMethod = "close")
    AuditMaintenance dataPrismAuditMaintenance(DataPrismProperties properties, ObjectProvider<AuditRecorder> recorder,
            ObjectProvider<AuditRetention> retention, ObjectProvider<PrivacyMetrics> metrics,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        String checkpointPath = properties.getAudit().getCheckpoint().getFilePath();
        AuditRecorder checkpointing = checkpointPath == null || checkpointPath.isBlank() ? null
                : recorder.getIfAvailable();
        if (checkpointing != null) {
            // by type, so an application AuditRecorder under any other name is ordered too
            for (String name : beanFactory.getBeanNamesForType(AuditRecorder.class)) {
                beanFactory.registerDependentBean(name, "dataPrismAuditMaintenance");
            }
        }
        return new AuditMaintenance(checkpointing,
                retention.getIfAvailable(), properties.getAudit().getCheckpoint().getInterval(),
                metrics.getIfAvailable(PrivacyMetrics::none));
    }
    /** The {@code auditIntegrity} health contributor; present only when Spring Boot Actuator is. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.boot.health.contributor.HealthIndicator.class)
    static class AuditIntegrityHealth {
        /** The bean name minus {@code HealthIndicator} is the contributor name: {@code auditIntegrity}. */
        @Bean
        org.springframework.boot.health.contributor.HealthIndicator auditIntegrityHealthIndicator(
                AuditMaintenance maintenance) {
            return () -> {
                String code = maintenance.failureCode();
                if (code == null) {
                    return org.springframework.boot.health.contributor.Health.up().build();
                }
                org.springframework.boot.health.contributor.Health.Builder down =
                        org.springframework.boot.health.contributor.Health.down().withDetail("code", code);
                if (maintenance.failureSegmentDate() != null) {
                    down.withDetail("segmentDate", maintenance.failureSegmentDate());
                }
                return down.build();
            };
        }
    }
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
     * {@code embedded}: the budget is enforced once across the cluster, not once per process, over
     * the same {@link PrivacyCluster} member as the oversight state ({@link ClusterBackedState}).
     * Declared here, not on that nested class, so its {@code @ConditionalOnBean} is evaluated after
     * {@link #dataPrismSecretKeyProvider} is registered, exactly as before. The cluster arrives as an
     * {@link ObjectProvider} so this signature names no optional type: a {@code single-node} consumer
     * without {@code data-prism-hazelcast} never loads {@link ClusterBackedState}.
     */
    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean({IdentityResolver.class, SecretKeyProvider.class, AuditSink.class, PrivacyMetrics.class, DataSourceAdapter.class})
    @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "embedded")
    @ConditionalOnClass(HazelcastInstance.class)
    ScopeBudget dataPrismClusterScopeBudget(ObjectProvider<PrivacyCluster> cluster,
            ConfigurableListableBeanFactory beanFactory) {
        return ClusterBackedState.budgetOver(cluster, beanFactory, "dataPrismClusterScopeBudget");
    }

    /**
     * {@code embedded}: the one {@link PrivacyCluster} member, and everything built on it, so the
     * budget (see {@link #dataPrismClusterScopeBudget}), the oversight state, the approval store and the caller rate limiter share a single
     * member rather than each starting its own. This nested class is {@code @ConditionalOnClass}
     * guarded so a {@code single-node} consumer that never put {@code data-prism-hazelcast} on its
     * classpath never loads it; its beans are registered ahead of the outer class's, so the
     * in-memory fall-backs below see them. When the topology is {@code embedded} and that
     * dependency is absent, no {@link ScopeBudget} bean is created here at all, and
     * {@link #dataPrismSharedBudgetPreflight} turns that silence into {@code MISSING_SHARED_BUDGET}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HazelcastInstance.class)
    @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "embedded")
    static class ClusterBackedState {
        /**
         * Built from the explicit {@code dataprism.hazelcast} cluster settings, never from a default
         * {@code Config}. Waits for {@link #dataPrismPropertiesValidated} so a refused configuration
         * never starts a member.
         */
        @Bean @ConditionalOnMissingBean @DependsOn("dataPrismPropertiesValidated")
        PrivacyCluster dataPrismPrivacyCluster(DataPrismProperties properties) {
            DataPrismProperties.Hazelcast h = properties.getHazelcast();
            try {
                ClusterMembership membership = new ClusterMembership(h.getClusterName().strip(), join(h.getJoin()),
                        h.getMember().getPort() == null ? ClusterMembership.DEFAULT_PORT : h.getMember().getPort(),
                        Optional.ofNullable(h.getMember().getInterface()));
                return PrivacyCluster.embedded(membership, h.isReidentificationEnabled());
            } catch (PrivacyClusterRefusal refusal) {
                String code = refusal.code().name();
                throw new DataPrismConfigurationException(code, refusal.getMessage().substring(code.length() + 2));
            }
        }

        private static ClusterMembership.Join join(DataPrismProperties.Hazelcast.Join join) {
            DataPrismProperties.Hazelcast.Kubernetes k = join.getKubernetes();
            return switch (join.getMode().strip()) {
                case "tcp-ip" -> new ClusterMembership.TcpIp(join.getMembers());
                case "kubernetes" -> new ClusterMembership.Kubernetes(k.getNamespace(), k.getServiceName(),
                        k.getServiceDns());
                case "none" -> new ClusterMembership.None();
                default -> throw new DataPrismConfigurationException("UNSUPPORTED_CLUSTER_JOIN",
                        "dataprism.hazelcast.join.mode is not a supported mode");
            };
        }

        /**
         * Called by {@link #dataPrismClusterScopeBudget}; this class is never loaded unless that runs.
         * Registers the budget as dependent on whichever cluster bean it is built over, so the
         * budget is destroyed before the member it uses is shut down.
         */
        static ScopeBudget budgetOver(ObjectProvider<PrivacyCluster> cluster,
                ConfigurableListableBeanFactory beanFactory, String budgetBeanName) {
            PrivacyCluster resolved = cluster.getObject();
            for (String name : beanFactory.getBeanNamesForType(PrivacyCluster.class, true, false)) {
                beanFactory.registerDependentBean(name, budgetBeanName);
            }
            return new HazelcastScopeBudget(resolved);
        }

        @Bean @ConditionalOnMissingBean
        OversightState dataPrismClusterOversightState(PrivacyCluster cluster) {
            return new HazelcastOversightState(cluster);
        }

        @Bean @ConditionalOnMissingBean
        ApprovalStore dataPrismClusterApprovalStore(PrivacyCluster cluster) {
            return new HazelcastApprovalStore(cluster);
        }

        @Bean @ConditionalOnMissingBean
        CallerRateLimiter dataPrismClusterCallerRateLimiter(PrivacyCluster cluster) {
            return new HazelcastCallerRateLimiter(cluster);
        }
    }

    /**
     * Oversight state when no cluster supplied it: per process, honestly. Declared on the outer
     * class, so it is registered after {@link ClusterBackedState}'s and yields to it.
     */
    @Bean @ConditionalOnMissingBean
    OversightState dataPrismOversightState() { return new InMemoryOversightState(); }
    @Bean @ConditionalOnMissingBean
    ApprovalStore dataPrismApprovalStore() { return new InMemoryApprovalStore(); }
    @Bean @ConditionalOnMissingBean
    CallerRateLimiter dataPrismCallerRateLimiter() { return new InMemoryCallerRateLimiter(); }
    @Bean
    OversightPolicy dataPrismOversightPolicy(DataPrismProperties properties) {
        DataPrismProperties.Oversight o = properties.getOversight();
        Integer requests = o.getCallerRateLimit().getRequests();
        return new OversightPolicy(Set.copyOf(o.getApprovalRequiredTools()),
                requests == null ? OptionalInt.empty() : OptionalInt.of(requests),
                o.getCallerRateLimit().getWindow(), o.getApprovalTtl(), o.getMaxPendingPerRequester());
    }
    /**
     * Always built, never optional: the MCP server is given this admission unconditionally, so a
     * pause or an approval requirement can never be silently skipped by a missing bean.
     */
    @Bean
    ToolAdmission dataPrismToolAdmission(OversightState state, ApprovalStore approvals, CallerRateLimiter limiter,
            OversightPolicy policy, Clock clock) {
        return new ToolAdmission(state, approvals, limiter, policy, clock);
    }
    @Bean @ConditionalOnMissingBean
    ParameterFingerprinter dataPrismParameterFingerprinter(SecretKeyProvider keys) {
        return new ParameterFingerprinter(keys);
    }

    /**
     * The re-identification service, only when {@code dataprism.reidentification.enabled=true}.
     * Nothing here, or anywhere in this module, registers it as an MCP tool: the only transport
     * wiring is {@link #dataPrismHttpTransport}, which takes no re-identification type at all.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ReidentificationService.class, HazelcastInstance.class})
    @ConditionalOnProperty(prefix = "dataprism.reidentification", name = "enabled", havingValue = "true")
    static class ReidentificationWiring {
        @Bean
        ReidentificationPolicy dataPrismReidentificationPolicy(DataPrismProperties properties) {
            DataPrismProperties.Reidentification r = properties.getReidentification();
            Map<String, Set<Permission>> roles = new java.util.HashMap<>();
            r.getRoles().forEach((role, permissions) -> roles.put(role, permissions.stream()
                    .map(p -> Permission.valueOf(p.name())).collect(java.util.stream.Collectors.toSet())));
            return new ReidentificationPolicy(Set.copyOf(r.getPurposes()), roles, r.isFourEyes(),
                    r.getApprovalTtl(), r.getMaxPendingPerRequester());
        }

        /**
         * Feeds the reverse index: every {@link SyntheticValueSource} in the context, the default or
         * the application's own, is wrapped in {@link CachingSyntheticValueSource} over the shared
         * {@link PrivacyCluster}, so each pseudonym handed out is entered where
         * {@link ScopeIdentityIndex} reads it. A decorator rather than a competing bean, so the
         * default's {@code @ConditionalOnMissingBean} still backs off for an application source. An
         * already-caching source is left alone, and a cache failure falls back to the wrapped source.
         * Only {@code embedded}; the cluster is resolved lazily to keep this post-processor early-safe.
         */
        @Bean @ConditionalOnProperty(prefix = "dataprism.hazelcast", name = "topology", havingValue = "embedded")
        static BeanPostProcessor dataPrismReidentificationIndexFeed(ObjectProvider<PrivacyCluster> cluster,
                ObjectProvider<PrivacyMetrics> metrics) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof SyntheticValueSource source && !(bean instanceof CachingSyntheticValueSource)) {
                        return new CachingSyntheticValueSource(source, cluster.getObject(),
                                metrics.getIfAvailable(PrivacyMetrics::none));
                    }
                    return bean;
                }
            };
        }

        @Bean
        ReidentificationService dataPrismReidentificationService(PrivacyCluster cluster, ApprovalStore approvals,
                AuditRecorder audit, ReidentificationPolicy policy, PrivacyMetrics metrics, Clock clock) {
            return new ReidentificationService(new ScopeIdentityIndex(cluster, metrics), approvals, audit, policy,
                    clock);
        }
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
    @Bean @ConditionalOnMissingBean
    ContextOrchestrator dataPrismContextOrchestrator(List<DataSourceAdapter<?>> adapters, IdentityResolver identities,
            JsonTreeScrubbingEngine scrubber, FieldMetadataResolver metadata, List<LlmResponseValidator> validators,
            SyntheticValueSource synthetics, ValueTokenSource tokens, SecretKeyProvider keys, AuditRecorder audit,
            ScopeBudget budget, PrivacyMetrics metrics, Clock clock, ParameterFingerprinter fingerprinter) {
        return new DefaultContextOrchestrator(adapters, scrubber, metadata, List.copyOf(validators), synthetics,
                fingerprinter, audit, identities,
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
            PrivacyMetrics metrics, AuditRecorder audit, Clock clock, DataPrismProperties properties,
            ToolAdmission admission, ParameterFingerprinter fingerprinter) {
        return DataPrismMcpServer.streamableHttp(orchestrator, authorization, scopeResolver, extractor,
                properties.getTransport().getHttp().getPath(), metrics, audit, clock, admission, fingerprinter);
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
