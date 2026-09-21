package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@link DataPrismConfigurationFailureAnalyzer} is actually reached — registered via
 * {@code META-INF/spring.factories} and invoked by {@link org.springframework.boot.SpringApplication}
 * itself, not exercised by calling the analyzer directly — for two of the three refusal
 * families the task requires: the {@code BeanFactoryPostProcessor} family ({@code
 * MISSING_IDENTITY_RESOLVER}) and {@link DataPrismProperties#validate()}. The third family, the
 * contract validator's {@code MISSING_SOURCE_ADAPTER}, needs a real servlet web application (this
 * module carries no embedded container on its test classpath) and is proven instead in {@code
 * data-prism-server}'s {@code ConfigurationRefusalMessageIT}, against the packaged distribution.
 *
 * <p>Every test here runs {@code dataprism.transport.mode=stdio} with {@code
 * fixture-development=true}: that combination lets {@link org.springframework.boot.WebApplicationType#NONE}
 * pass {@code dataPrismMcpTransportPreflight} (stdio is exempt) without ever registering a
 * usable transport — a state {@code dataPrismStdioTransportRefused} always refuses on its own,
 * but only once bean creation reaches it, which is after the refusal this test wants to observe.
 * It also lets {@link DataPrismProperties#validate()} skip every {@code protectedDeployment()}
 * check (JWT, caller claims, privacy, audit, metrics, Hazelcast), so a minimal property set is
 * enough to reach {@code validateSources()} — the one {@code validate()} check that still runs
 * unconditionally.
 */
@ExtendWith(OutputCaptureExtension.class)
class DataPrismConfigurationFailureAnalyzerTest {

    @Test
    void missingIdentityResolverPrintsAnOperatorFacingBlockWithNoStackFrame(CapturedOutput output) {
        assertThrows(Throwable.class, () -> run(NoIdentityResolver.class));

        assertOperatorFacingBlock(output.getOut(), "MISSING_IDENTITY_RESOLVER");
    }

    @Test
    void aDataPrismPropertiesValidateRefusalGetsTheSameTreatment(CapturedOutput output) {
        assertThrows(Throwable.class, () -> run(ReviewedFixtureIntegrations.class,
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=0s"));

        assertOperatorFacingBlock(output.getOut(), "INVALID_SOURCE_TIMEOUT");
    }

    /**
     * {@code refuse("INVALID_SOURCE_TIMEOUT", name)} passes the configured source's own map key
     * as the detail text — exactly the shape of a configured value the analyzer must never
     * repeat. This plants a distinctive one and asserts it never reaches the rendered block,
     * only the stable code does.
     */
    @Test
    void aDistinctiveConfiguredValueNeverReachesTheRenderedBlock(CapturedOutput output) {
        assertThrows(Throwable.class, () -> run(ReviewedFixtureIntegrations.class,
                "--dataprism.sources.DISTINCTIVE_MARKER_NOT_AN_APPROVED_VALUE.base-url=https://customer.example",
                "--dataprism.sources.DISTINCTIVE_MARKER_NOT_AN_APPROVED_VALUE.timeout=0s"));

        String printed = output.getOut();
        assertOperatorFacingBlock(printed, "INVALID_SOURCE_TIMEOUT");
        assertThat(printed).doesNotContain("DISTINCTIVE_MARKER_NOT_AN_APPROVED_VALUE");
    }

    private static void assertOperatorFacingBlock(String printed, String code) {
        assertThat(printed).as(printed).contains("APPLICATION FAILED TO START");
        assertThat(printed).as(printed).contains("DataPrismConfigurationException: " + code);
        assertThat(printed).as(printed).contains("docs/configuration.md");
        assertThat(printed).as(printed).contains("docs/quickstart.md");
        assertThat(printed).as(printed).doesNotContain("\tat io.github.aindriub.dataprism");
        assertThat(printed).as(printed).doesNotContain("\tat org.springframework");
    }

    private static ConfigurableApplicationContext run(Class<?> userConfiguration, String... extraArgs) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(DataPrismAutoConfiguration.class,
                userConfiguration)
                .web(WebApplicationType.NONE);
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--spring.main.banner-mode=off",
                "--dataprism.transport.mode=stdio",
                "--dataprism.transport.fixture-development=true"));
        args.addAll(java.util.List.of(extraArgs));
        return builder.run(args.toArray(String[]::new));
    }

    @Configuration(proxyBeanMethods = false)
    static class ReviewedFixtureIntegrations {
        @Bean
        IdentityResolver identities() {
            return new PassThroughIdentityResolver();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NoIdentityResolver {
        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(DataRequest request) { return null; }
            };
        }
    }
}
