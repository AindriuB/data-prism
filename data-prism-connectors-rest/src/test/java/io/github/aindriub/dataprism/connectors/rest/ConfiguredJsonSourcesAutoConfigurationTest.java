package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Spring wiring itself, not just the classes it assembles: proves that
 * {@link ConfiguredJsonSourcesAutoConfiguration} — the thing an operator
 * actually loads via {@code -Dloader.path} — registers one {@link
 * DataSourceAdapter} bean per configured source and replaces the platform's
 * {@link ContextOrchestrator} with one that knows about them, and that doing
 * nothing (no {@code dataprism.json-sources.config-location}) leaves a
 * deployment exactly as it was.
 */
class ConfiguredJsonSourcesAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // ApplicationContextRunner builds a plain context rather than going
            // through SpringApplication, so it does not read
            // META-INF/spring/org.springframework.boot.ApplicationContextInitializer.imports
            // the way a real deployment does; adding the initializer explicitly
            // exercises the exact same initialize() method a real run invokes via
            // that SPI file.
            .withInitializer(new ConfiguredJsonSourcesInitializer())
            .withUserConfiguration(RequiredCollaborators.class)
            .withConfiguration(AutoConfigurations.of(
                    BaselineContextOrchestratorAutoConfiguration.class,
                    ConfiguredJsonSourcesAutoConfiguration.class));

    @Test
    @DisplayName("with no configured-JSON-sources property, nothing here registers")
    void inertWithoutTheProperty() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DataSourceAdapter.class)).isEmpty();
            // The platform's own default is the only ContextOrchestrator bean.
            assertThat(context.getBeansOfType(ContextOrchestrator.class)).hasSize(1);
            assertThat(context.getBean(ContextOrchestrator.class))
                    .isSameAs(BaselineContextOrchestratorAutoConfiguration.INSTANCE);
        });
    }

    @Test
    @DisplayName("with the property set, one adapter bean per source is registered and the "
            + "orchestrator is replaced, not duplicated")
    void registersAdaptersAndReplacesTheOrchestrator() {
        runner.withPropertyValues(
                        "dataprism.json-sources.config-location=classpath:/task20-json-sources.yaml")
                .run((AssertableApplicationContext context) -> {
                    assertThat(context).hasNotFailed();

                    var adapters = context.getBeansOfType(DataSourceAdapter.class);
                    assertThat(adapters).hasSize(1);
                    assertThat(adapters.values().iterator().next().sourceName()).isEqualTo("customer-api");

                    // Exactly one ContextOrchestrator bean still exists -- replaced, not
                    // added alongside -- so the platform still exposes one MCP tool
                    // backed by one orchestrator, never a second route to this source.
                    assertThat(context.getBeansOfType(ContextOrchestrator.class)).hasSize(1);
                    assertThat(context.getBean(ContextOrchestrator.class))
                            .isNotSameAs(BaselineContextOrchestratorAutoConfiguration.INSTANCE);
                });
    }

    @Test
    @DisplayName("an invalid catalogue at the configured location refuses context startup")
    void invalidCatalogueRefusesStartup() {
        runner.withPropertyValues(
                        "dataprism.json-sources.config-location=classpath:/does-not-exist.yaml")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * The base distribution's contract validator no longer requires a {@code
     * dataprism.sources.<name>} entry for this adapter's name (see {@link
     * #registersAdaptersAndReplacesTheOrchestrator}, which sets none), but
     * nothing forbids an operator from stating one anyway; when they do and it
     * agrees with {@code json-sources}, startup still proceeds.
     */
    @Test
    @DisplayName("a dataprism.sources entry whose base-url agrees with json-sources does not refuse")
    void agreeingDataprismSourcesEntryIsFine() {
        runner.withPropertyValues(
                        "dataprism.json-sources.config-location=classpath:/task20-json-sources.yaml",
                        "dataprism.sources.customer-api.base-url=https://127.0.0.1:1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The hazard this proof exists for: without it, the two declarations could
     * disagree and the validated one — {@code dataprism.sources.<name>.base-url}
     * — would be silently ignored in favour of the one {@code
     * ConfiguredJsonDataSourceAdapter} actually dials.
     */
    @Test
    @DisplayName("a dataprism.sources entry whose base-url disagrees with json-sources refuses startup")
    void disagreeingDataprismSourcesEntryRefusesStartup() {
        runner.withPropertyValues(
                        "dataprism.json-sources.config-location=classpath:/task20-json-sources.yaml",
                        "dataprism.sources.customer-api.base-url=https://somewhere-else.example")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * The deployment idiom this repository actually uses for {@code
     * dataprism.sources.<name>.base-url}, per {@code compose.yaml}'s own
     * documented environment-variable form: hyphens are <em>dropped</em>, not
     * replaced with an underscore ({@code server.ssl.key-store} becomes {@code
     * SERVER_SSL_KEYSTORE}, not {@code SERVER_SSL_KEY_STORE}).
     *
     * <p>This is deliberately not an {@link ApplicationContextRunner} test.
     * {@code SpringApplication}'s own {@code ConfigDataEnvironmentPostProcessor}
     * calls {@code ConfigurationPropertySources.attach(environment)} while
     * preparing the environment, before any {@code ApplicationContextInitializer}
     * runs — confirmed by hand, both against the packaged server process (with
     * {@link #FIXTURE_ENV_VAR} set and no dotted form present anywhere, the
     * disagreement was still caught even with this method's {@code
     * bindString} temporarily reverted to a plain {@code
     * environment.getProperty} call) and against {@link ApplicationContextRunner}
     * itself (its own {@code withPropertyValues} triggers the same {@code
     * attach}). Once that has run, {@code Environment.getProperty} becomes
     * relaxed-binding-aware for <em>every</em> caller, Binder or not, which
     * would make a test built on either harness pass identically whether this
     * class used {@link Binder} or not — proving nothing about which one this
     * method actually calls. Only a context that has never gone through
     * {@code SpringApplication}'s environment preparation, or {@code
     * ApplicationContextRunner}'s own property-value handling, isolates the
     * difference: this test builds a bare {@link AnnotationConfigApplicationContext},
     * sets its property sources directly, and calls {@link
     * ConfiguredJsonSourcesInitializer#initialize} itself, so nothing upstream
     * of the method under test can have already attached relaxed resolution.
     */
    @Test
    @DisplayName("a disagreeing base-url set in the environment-variable form refuses startup, "
            + "in a context nothing else has already made relaxed-binding-aware")
    void disagreeingEnvironmentVariableFormBaseUrlRefusesStartup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(FIXTURE_ENV_VAR, "https://evil.example")));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "dataprism.json-sources.config-location", "classpath:/task20-json-sources-envvar-test.yaml")));

            assertThatThrownBy(() -> new ConfiguredJsonSourcesInitializer().initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disagrees");
        }
    }

    /**
     * The source name in {@code task20-json-sources-envvar-test.yaml} is
     * {@code customerapi}, not this file's usual {@code customer-api},
     * deliberately: a hyphen inside the map key itself ({@code
     * sources.<name>}) makes even {@link Binder}'s relaxed matching ambiguous
     * about where the key ends and the {@code base-url} field begins — checked
     * by hand before writing this test, comparing {@code
     * Binder.get(env).bind(name, String.class)} against {@code
     * env.getProperty(name)} against a {@link StandardEnvironment} whose {@code
     * systemEnvironment} source was replaced with a controlled one, for both
     * the hyphenated and hyphen-free key. With the hyphen-free key, {@code
     * DATAPRISM_SOURCES_CUSTOMERAPI_BASEURL} (hyphen dropped from {@code
     * base-url}, matching {@code compose.yaml}'s idiom) resolves through
     * {@link Binder#bind} and not at all through {@link Environment#getProperty};
     * with the hyphenated key, the equivalent form resolved through neither,
     * which would make a test built on it prove nothing either way.
     */
    private static final String FIXTURE_ENV_VAR = "DATAPRISM_SOURCES_CUSTOMERAPI_BASEURL";

    /**
     * The same relaxed-binding miss {@link
     * #disagreeingEnvironmentVariableFormBaseUrlRefusesStartup} closes for the
     * paired {@code base-url}, applied to {@code
     * dataprism.transport.fixture-development}: {@code
     * DATAPRISM_TRANSPORT_FIXTUREDEVELOPMENT} (hyphen dropped, matching {@code
     * compose.yaml}'s idiom) is invisible to {@link Environment#getProperty}
     * the same way. This one fails closed rather than open — a plain {@code
     * getProperty} call defaults to {@code false} when it cannot see the
     * value, so a fixture setting this env var specifically to enable the
     * loopback exception gets an unexpected refusal instead — but it is the
     * same mistake, and {@link ConfiguredJsonSourcesInitializer#loadConfig} is
     * exactly the pattern the base-url check above copies, so it should not
     * be left uncorrected as a trap for whoever reads one and assumes the
     * other already got this right.
     */
    @Test
    @DisplayName("fixture-development set in the environment-variable form is honoured, "
            + "in a context nothing else has already made relaxed-binding-aware")
    void fixtureDevelopmentEnvironmentVariableFormIsHonoured() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("DATAPRISM_TRANSPORT_FIXTUREDEVELOPMENT", "true")));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "dataprism.json-sources.config-location",
                    "classpath:/task20-json-sources-fixturedev-test.yaml")));

            // The catalogue's base-url is a loopback http:// URL, refused unless
            // fixture-development resolved to true; succeeding here is the proof.
            ConfiguredJsonSourcesConfig config = ConfiguredJsonSourcesInitializer.loadConfig(context.getEnvironment());

            assertThat(config.sources().get("customerapi").transport().baseUrl().toString())
                    .isEqualTo("http://127.0.0.1:1");
        }
    }

    /**
     * Every collaborator {@link
     * ConfiguredJsonSourcesAutoConfiguration#configuredJsonSourcesContextOrchestrator}
     * needs, built the same way the end-to-end test builds them.
     */
    @Configuration(proxyBeanMethods = false)
    static class RequiredCollaborators {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        IdentityResolver identityResolver() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        FieldMetadataResolver fieldMetadataResolver() {
            return new DefaultFieldMetadataResolver();
        }

        @Bean
        LlmResponseValidator rawValueLeakValidator() {
            return new RawValueLeakValidator();
        }

        @Bean
        SecretKeyProvider secretKeyProvider() {
            return StaticSecretKeyProvider.of("task-20-autoconfiguration-test-key-not-for-real-data");
        }

        @Bean
        SyntheticValueSource syntheticValueSource(SecretKeyProvider keys) {
            return new HmacSyntheticGenerator(keys, VocabularyRegistry.withBuiltIns().resolve("und"));
        }

        @Bean
        ValueTokenSource valueTokenSource(SecretKeyProvider keys) {
            return new HmacValueTokenSource(keys);
        }

        @Bean
        PrivacyPolicyResolver privacyPolicyResolver() throws IOException {
            try (var input = RequiredCollaborators.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
                return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
            }
        }

        @Bean
        AuditRecorder auditRecorder(Clock clock) {
            return new AuditRecorder(event -> { }, clock, "test-20-autoconfiguration");
        }

        @Bean
        ScopeBudget scopeBudget() {
            return new InMemoryScopeBudget();
        }

        @Bean
        PrivacyMetrics privacyMetrics() {
            return PrivacyMetrics.none();
        }
    }

    /**
     * Stands in for the platform's own default auto-configured {@code
     * ContextOrchestrator}: a separate {@code @AutoConfiguration}, exactly the
     * relationship {@code DataPrismAutoConfiguration} has to {@link
     * ConfiguredJsonSourcesAutoConfiguration} in production, so {@code
     * @ConditionalOnMissingBean} orders against it the same way here as it does
     * there. A plain {@code @Configuration} bean would win by Spring Boot's own
     * rule that user-supplied beans always precede auto-configured ones,
     * regardless of {@code @AutoConfigureBefore} — which would prove nothing
     * about the real ordering this test exists to check.
     */
    @AutoConfiguration
    @AutoConfigureAfter(ConfiguredJsonSourcesAutoConfiguration.class)
    static class BaselineContextOrchestratorAutoConfiguration {

        static final ContextOrchestrator INSTANCE = (request, context, investigationContext) -> {
            throw new UnsupportedOperationException("not invoked by this test");
        };

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        ContextOrchestrator baselineContextOrchestrator() {
            return INSTANCE;
        }
    }
}
