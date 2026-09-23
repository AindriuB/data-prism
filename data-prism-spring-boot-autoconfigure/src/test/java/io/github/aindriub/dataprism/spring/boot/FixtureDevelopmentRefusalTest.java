package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
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

    /**
     * Task 73: {@code MISSING_AUDIT_SINK} names the case {@code validate()} alone can
     * see -- the property itself absent or blank -- which is a different failure from
     * {@code AUDIT_SINK_BEAN_REQUIRED} (an absent bean under a configured {@code
     * approved-sink}, seen only by {@link DataPrismContractValidator}). Pinned
     * separately so the two stay distinguishable in a dashboard.
     */
    @Test void blankAuditSinkRefusesWithMissingAuditSink() {
        DataPrismProperties properties = validProperties();
        properties.getAudit().setSink("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_AUDIT_SINK:");
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
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("UNRESOLVED_SOURCE_ADAPTER:");
    }

    @Test void contractValidatorEarlyReturnAppliesOnlyToStdioFixtureDevelopment() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getTransport().setMode(DataPrismProperties.Transport.Mode.STDIO);
        properties.getTransport().setFixtureDevelopment(true);

        assertThatCode(() -> DataPrismContractValidator.validateIntegrations(properties, List.of(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .doesNotThrowAnyException();
    }

    /**
     * {@link #validProperties()} configures {@code dataprism.audit.sink=approved-sink}
     * but this file never previously drove that configuration far enough into {@link
     * DataPrismContractValidator} to notice whether an absent {@code AuditSink} bean
     * still refuses -- every other test here either fails earlier (at {@code validate()}
     * or {@code UNRESOLVED_SOURCE_ADAPTER}) or takes the stdio-fixture early return.
     * Task 73: pin the outcome explicitly rather than leave that gap.
     */
    @Test void approvedAuditSinkConfiguredHereStillRefusesAtTheContractValidatorWithNoBean() {
        DataPrismProperties properties = validProperties();

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeCustomerAdapter()),
                availableProvider(new PassThroughIdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                emptyProvider(), availableProvider(PrivacyMetrics.none()), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("AUDIT_SINK_BEAN_REQUIRED:")
                .hasMessageContaining("dataprism.audit.sink=approved-sink");
    }

    private static DataSourceAdapter<String> fakeCustomerAdapter() {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return "customer"; }
            @Override public Class<String> responseType() { return String.class; }
            @Override public String fetch(DataRequest request) { throw new UnsupportedOperationException(); }
        };
    }

    private static <T> ObjectProvider<T> availableProvider(T instance) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return instance; }
        };
    }

    private static final class PassThroughIdentityResolverStub implements IdentityResolver {
        @Override public CanonicalId resolve(SourceRef ref) {
            throw new UnsupportedOperationException();
        }

        @Override public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
            throw new UnsupportedOperationException();
        }
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
        properties.getHazelcast().setTopology("single-node");
        DataPrismProperties.Source source = new DataPrismProperties.Source();
        source.setBaseUrl("https://customer.example");
        source.setTimeout(Duration.ofSeconds(2));
        properties.getSources().put("customer", source);
        return properties;
    }
}
