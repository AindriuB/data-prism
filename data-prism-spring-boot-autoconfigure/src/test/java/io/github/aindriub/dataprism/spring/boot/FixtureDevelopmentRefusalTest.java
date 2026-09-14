package io.github.aindriub.dataprism.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture development is a stdio-only concession. It must not silently switch
 * off the protected-deployment contract, or the source-adapter and identity
 * cross-checks, for any HTTP consumer.
 */
class FixtureDevelopmentRefusalTest {

    @Test void httpFixtureDevelopmentRefusesEvenWhenOtherwiseValid() {
        DataPrismProperties properties = validProperties();
        properties.getTransport().setMode(DataPrismProperties.Transport.Mode.HTTP);
        properties.getTransport().setFixtureDevelopment(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("FIXTURE_DEVELOPMENT_STDIO_ONLY:");
    }

    @Test void httpFixtureDevelopmentWithABlankIssuerRefusesWithMissingJwtIssuer() {
        DataPrismProperties properties = validProperties();
        properties.getTransport().setMode(DataPrismProperties.Transport.Mode.HTTP);
        properties.getTransport().setFixtureDevelopment(true);
        properties.getSecurity().getJwt().setIssuer("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_JWT_ISSUER:");
    }

    @Test void stdioFixtureDevelopmentIsUnaffected() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getTransport().setMode(DataPrismProperties.Transport.Mode.STDIO);
        properties.getTransport().setFixtureDevelopment(true);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test void contractValidatorEarlyReturnDoesNotApplyToHttpFixtureDevelopment() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getTransport().setMode(DataPrismProperties.Transport.Mode.HTTP);
        properties.getTransport().setFixtureDevelopment(true);
        properties.getSources().put("customer", new DataPrismProperties.Source());

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties, List.of(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("UNRESOLVED_SOURCE_ADAPTER:");
    }

    @Test void contractValidatorEarlyReturnAppliesOnlyToStdioFixtureDevelopment() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getTransport().setMode(DataPrismProperties.Transport.Mode.STDIO);
        properties.getTransport().setFixtureDevelopment(true);

        assertThatCode(() -> DataPrismContractValidator.validateIntegrations(properties, List.of(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .doesNotThrowAnyException();
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return null; }
        };
    }

    private static DataPrismProperties validProperties() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSecurity().getJwt().setIssuer("https://issuer.example");
        properties.getSecurity().getJwt().setAudience("mcp");
        properties.getSecurity().getJwt().setJwkSetUri("https://issuer.example/jwks");
        properties.getSecurity().getCallerClaims().setPrincipal("sub");
        properties.getSecurity().getCallerClaims().setRoles("roles");
        properties.getSecurity().getCallerClaims().setInvestigation("case_id");
        properties.getSecurityPolicy().setPurposes(List.of("investigation"));
        properties.getSecurityPolicy().setRoles(Map.of("investigator", List.of("GET_ENTITY_CONTEXT")));
        properties.getPrivacy().setProfile("DEFAULT");
        properties.getPrivacy().setScopeLifetime(Duration.ofHours(8));
        properties.getPrivacy().getHmacKey().setKeyId("v1");
        properties.getPrivacy().getHmacKey().setEnvironmentVariable("DATAPRISM_HMAC_KEY_REF");
        properties.getAudit().setSink("approved-sink");
        properties.getAudit().setWriterId("test");
        properties.getMetrics().setSink("micrometer");
        DataPrismProperties.Source source = new DataPrismProperties.Source();
        source.setBaseUrl("https://customer.example");
        source.setTimeout(Duration.ofSeconds(2));
        properties.getSources().put("customer", source);
        return properties;
    }
}
