package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java-first engine of the configured-source auto-configuration is built from the auto-configured
 * engine's recorded resolver and descriptors when a descriptor file is set (task 249, after 271).
 */
class ConfiguredJsonJavaFirstEngineTest {

    private static final String FILE = "dataprism.privacy.descriptor-file=classpath:/ignored.yaml";
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-249-java-first-test-key-not-for-any-real-data-32b");
    private static final Vocabulary VOCABULARY = VocabularyRegistry.withBuiltIns().resolve("und");

    @LlmExposedModel
    record Tracked(@InternalIdentifier String ref, @NonSensitive(reason = "state") String state) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    private static PrivacyPolicyResolver policy() {
        try (InputStream input = ConfiguredJsonJavaFirstEngineTest.class
                .getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final SyntheticValueSource SYNTHETICS = new HmacSyntheticGenerator(KEYS, VOCABULARY);
    private static final ValueTokenSource TOKENS = new HmacValueTokenSource(KEYS);

    private static Map<String, ModelDescriptor> chainableRef(String namespace) {
        return Map.of(Tracked.class.getName(), new ModelDescriptor(Tracked.class.getName(), null, null, null,
                Map.of("ref", new ModelDescriptor.FieldDescriptor(List.of(), null, null, null, null, null,
                        true, namespace))));
    }

    private static JsonTreeScrubbingEngine engine(FieldMetadataResolver resolver, Map<String, ModelDescriptor> map) {
        return new JsonTreeScrubbingEngine(resolver, policy(), SYNTHETICS, TOKENS, map);
    }

    private static DescriptorFieldMetadataResolver described(String namespace) {
        return new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(), chainableRef(namespace));
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(VOCABULARY.id()));
    }

    private static JsonTreeScrubbingEngine build(org.springframework.context.ApplicationContext context) {
        return ConfiguredJsonSourcesAutoConfiguration.javaFirst(context.getEnvironment(),
                context.getBeanProvider(JsonTreeScrubbingEngine.class), new DefaultFieldMetadataResolver(),
                policy(), SYNTHETICS, TOKENS);
    }

    @Test
    @DisplayName("with the file set and the resolver behind a JDK proxy, the described keyNamespace is present")
    void proxiedResolverKeepsTheDescriptors() {
        DescriptorFieldMetadataResolver real = described("tracking-ref");
        FieldMetadataResolver proxy = (FieldMetadataResolver) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {FieldMetadataResolver.class},
                (p, method, args) -> method.invoke(real, args));
        runner.withPropertyValues(FILE)
                .withBean("engine", JsonTreeScrubbingEngine.class, () -> engine(proxy, real.descriptors()))
                .run(context -> {
                    JsonTreeScrubbingEngine javaFirst = build(context);
                    assertThat(javaFirst.resolver()).isSameAs(proxy);
                    assertThat(javaFirst.describe(Tracked.class, context(), true).orElseThrow().fields())
                            .contains(new FieldProtection("ref", FieldProtection.Protection.ABSENT, null,
                                    "tracking-ref"));
                });
    }

    @Test
    @DisplayName("with the file set and no engine bean, startup refuses")
    void noEngineRefuses() {
        runner.withPropertyValues(FILE).run(context ->
                assertThatThrownBy(() -> build(context))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("MODEL_DESCRIPTOR_RESOLVER_REPLACED:"));
    }

    @Test
    @DisplayName("with two engine beans and a @Primary, the primary's resolver and map are used")
    void primaryWins() {
        DescriptorFieldMetadataResolver primary = described("primary-ref");
        DescriptorFieldMetadataResolver other = described("other-ref");
        runner.withPropertyValues(FILE)
                .withBean("other", JsonTreeScrubbingEngine.class, () -> engine(other, other.descriptors()))
                .withBean("primary", JsonTreeScrubbingEngine.class, () -> engine(primary, primary.descriptors()),
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    JsonTreeScrubbingEngine javaFirst = build(context);
                    assertThat(javaFirst.resolver()).isSameAs(primary);
                    assertThat(javaFirst.descriptors()).isEqualTo(primary.descriptors());
                    assertThat(javaFirst.describe(Tracked.class, context(), true).orElseThrow().fields())
                            .extracting(FieldProtection::keyNamespace).contains("primary-ref");
                });
    }

    @Test
    @DisplayName("with two engine beans and no primary, startup refuses")
    void ambiguousRefuses() {
        DescriptorFieldMetadataResolver one = described("one-ref");
        runner.withPropertyValues(FILE)
                .withBean("a", JsonTreeScrubbingEngine.class, () -> engine(one, one.descriptors()))
                .withBean("b", JsonTreeScrubbingEngine.class, () -> engine(one, one.descriptors()))
                .run(context -> assertThatThrownBy(() -> build(context))
                        .hasMessageStartingWith("MODEL_DESCRIPTOR_RESOLVER_REPLACED:"));
    }

    @Test
    @DisplayName("a blank descriptor file is unset: the injected resolver and no descriptors, whatever beans exist")
    void blankIsUnset() {
        DescriptorFieldMetadataResolver bean = described("ignored-ref");
        runner.withPropertyValues("dataprism.privacy.descriptor-file= ")
                .withBean("engine", JsonTreeScrubbingEngine.class, () -> engine(bean, bean.descriptors()))
                .run(context -> {
                    JsonTreeScrubbingEngine javaFirst = build(context);
                    assertThat(javaFirst.resolver()).isInstanceOf(DefaultFieldMetadataResolver.class);
                    assertThat(javaFirst.descriptors()).isEmpty();
                });
    }
}
