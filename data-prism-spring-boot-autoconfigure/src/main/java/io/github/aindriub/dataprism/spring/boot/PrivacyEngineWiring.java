package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.annotations.UndeclaredFields;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptors;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** The pseudonymisation and scrubbing pipeline: clock, field metadata, vocabulary, keys, policy, scrubber. */
@Configuration(proxyBeanMethods = false)
@Lazy(false)
class PrivacyEngineWiring implements SmartInitializingSingleton {
    private static final String RESOLVER_BEAN = "dataPrismFieldMetadataResolver";

    private final ListableBeanFactory beanFactory;
    private final DataPrismProperties properties;
    /** The resolver {@link #dataPrismFieldMetadataResolver} built from the descriptor file, if it did. */
    private volatile DescriptorFieldMetadataResolver builtResolver;

    PrivacyEngineWiring(ListableBeanFactory beanFactory, DataPrismProperties properties) {
        this.beanFactory = beanFactory;
        this.properties = properties;
    }

    /** The one test for "the descriptor file is set": not null and not blank. */
    private static boolean fileSet(DataPrismProperties properties) {
        String file = properties.getPrivacy().getDescriptorFile();
        return file != null && !file.isBlank();
    }

    /**
     * With the file set, the only resolver and the only engines allowed are the ones built from it, compared
     * by instance once every singleton exists, as {@code SecurityWiring.BypassCheck} does for its beans.
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!fileSet(properties)) {
            return;
        }
        DescriptorFieldMetadataResolver built = builtResolver;
        if (built == null) {
            throw replaced();
        }
        String[] names = beanFactory.getBeanNamesForType(FieldMetadataResolver.class, true, true);
        if (names.length != 1 || !RESOLVER_BEAN.equals(names[0]) || beanFactory.containsBean("&" + names[0])
                || !beanFactory.isSingleton(names[0])
                || unwrap(beanFactory.getBean(names[0])) != built) {
            throw replaced();
        }
        for (JsonTreeScrubbingEngine engine : beanFactory.getBeansOfType(JsonTreeScrubbingEngine.class, true, true)
                .values()) {
            JsonTreeScrubbingEngine target = (JsonTreeScrubbingEngine) unwrap(engine);
            if (unwrap(target.resolver()) != built || !target.descriptors().equals(built.descriptors())) {
                throw replaced();
            }
        }
    }

    private static Object unwrap(Object bean) {
        Object current = bean;
        for (Object target = AopProxyUtils.getSingletonTarget(current); target != null;
             target = AopProxyUtils.getSingletonTarget(current)) {
            current = target;
        }
        return current;
    }

    private static DataPrismConfigurationException replaced() {
        return new DataPrismConfigurationException("MODEL_DESCRIPTOR_RESOLVER_REPLACED",
                "dataprism.privacy.descriptor-file is set but the FieldMetadataResolver bean is not the one built "
                        + "from it, or an engine does not use it");
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
        if (!fileSet(properties)) {
            return defaultResolver;
        }
        DescriptorFieldMetadataResolver built = new DescriptorFieldMetadataResolver(defaultResolver,
                dataPrismModelDescriptors(properties.getPrivacy().getDescriptorFile()));
        builtResolver = built;
        return built;
    }

    /**
     * The reader's own refusal, for the operator to see why the file was refused: a fresh
     * exception with only the message, and only when that message leads with a stable
     * {@code UPPER_SNAKE_CODE: } (the code, the document path and the key name, never a value).
     * Any other failure -- an I/O error, or a message the reader built without a code, which
     * can quote a value -- is not chained, so nothing from the file's content reaches a log.
     */
    private static Throwable descriptorReason(Exception e) {
        String message = e instanceof IllegalArgumentException ? e.getMessage() : null;
        if (message == null || !message.matches("[A-Z][A-Z0-9_]*: [^\\r\\n]*")) {
            return null;
        }
        Throwable reason = new IllegalArgumentException(message);
        reason.setStackTrace(new StackTraceElement[0]);
        return reason;
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
            DataPrismConfigurationException refusal = new DataPrismConfigurationException(
                    "INVALID_MODEL_DESCRIPTOR_FILE", "dataprism.privacy.descriptor-file could not be parsed");
            Throwable reason = descriptorReason(e);
            if (reason != null) {
                refusal.initCause(reason);
            }
            throw refusal;
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
        PrivacyProperties.HmacKey hmac = properties.getPrivacy().getHmacKey();
        return new ConfiguredSecretKeyProvider(hmac.getKeyId(), HmacKeyReferences.reference(hmac),
                HmacKeyReferences.isProviderReference(hmac), resolver);
    }

    /**
     * Not {@code @ConditionalOnMissingBean}: an application bean of this type must
     * never silently replace the profile-backed resolver, so this one is always
     * created and {@link Preflights#dataPrismPrivacyPolicyResolverPreflight()} refuses
     * startup if a competing bean exists rather than letting one win quietly.
     */
    @Bean
    PrivacyPolicyResolver dataPrismPrivacyPolicyResolver(DataPrismProperties properties) {
        PropertiesValidation.validateProfile(properties);
        try (var input = DataPrismAutoConfiguration.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
        } catch (IOException e) { throw new IllegalStateException("default privacy profiles could not be loaded", e); }
    }

    @Bean @ConditionalOnMissingBean
    JsonTreeScrubbingEngine dataPrismScrubber(FieldMetadataResolver metadata, PrivacyPolicyResolver policy,
                                               SyntheticValueSource synthetics, ValueTokenSource tokens) {
        DescriptorFieldMetadataResolver built = builtResolver;
        Map<String, ModelDescriptor> descriptors = fileSet(properties) && built != null ? built.descriptors() : Map.of();
        return new JsonTreeScrubbingEngine(metadata, policy, synthetics, tokens, descriptors);
    }

    /**
     * Not {@code @ConditionalOnMissingBean}: this leak check must always run. An
     * application {@link LlmResponseValidator} bean is additive rather than a
     * replacement, because {@link OrchestrationWiring#dataPrismContextOrchestrator} collects every
     * bean of this type into its validator list instead of taking a single one.
     */
    @Bean
    LlmResponseValidator dataPrismRawValueLeakValidator() { return new RawValueLeakValidator(); }

    /**
     * Not {@code @ConditionalOnMissingBean}, for the same reason as the leak check above: the shape scan must
     * run in every Spring-built orchestrator, and an application {@link LlmResponseValidator} bean adds to the
     * collected list rather than replacing this one.
     */
    @Bean
    LlmResponseValidator dataPrismSensitivePatternValidator() { return new SensitivePatternValidator(); }
}
