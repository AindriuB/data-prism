package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 54: a configured JSON source's {@code DataSourceAdapter} bean (see
 * {@code io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonSourcesInitializer})
 * carries no {@code dataprism.sources.<name>} entry at all, so {@link
 * DataPrismContractValidator#validateIntegrations} must no longer demand
 * one-to-one equality between {@code dataprism.sources} and the supplied
 * adapter beans -- only that every {@code dataprism.sources} entry still
 * resolves to a real bean. {@link FixtureDevelopmentRefusalTest} already
 * covers the STDIO-fixture-development early return this class also observes
 * and is not repeated here.
 */
class DataPrismContractValidatorTest {

    @Test
    void missingSourceAdapterFiresWhenNeitherADataprismSourcesEntryNorAnyAdapterExists() {
        DataPrismProperties properties = new DataPrismProperties();

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties, List.of(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_SOURCE_ADAPTER:");
    }

    /**
     * The collapse this task delivers: a configured JSON source supplies its
     * adapter with no {@code dataprism.sources} entry naming it, and that
     * alone must not trip {@code MISSING_SOURCE_ADAPTER}. Deleting that
     * {@code throw} outright would also turn this green, but only because the
     * first test above would already be red -- both are needed to pin the
     * behaviour down.
     */
    @Test
    void missingSourceAdapterDoesNotFireWhenAConfiguredJsonSourceSuppliesTheOnlyAdapter() {
        DataPrismProperties properties = new DataPrismProperties();

        assertThatCode(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("customer-api")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                availableProvider(event -> { }), availableProvider(PrivacyMetrics.none())))
                .doesNotThrowAnyException();
    }

    @Test
    void unresolvedSourceAdapterFiresWhenADataprismSourcesEntryNamesASourceNoAdapterSupplies() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSources().put("orphan", new DataPrismProperties.Source());

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties, List.of(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("UNRESOLVED_SOURCE_ADAPTER:");
    }

    /**
     * The other half of the collapse: an adapter bean whose name has no
     * {@code dataprism.sources} counterpart at all -- exactly what a
     * configured JSON source now looks like -- must not trip {@code
     * UNRESOLVED_SOURCE_ADAPTER} either. Deleting that {@code throw} outright
     * would also turn this green, but only because the previous test would
     * already be red.
     */
    @Test
    void unresolvedSourceAdapterDoesNotFireWhenASuppliedAdapterHasNoDataprismSourcesEntry() {
        DataPrismProperties properties = new DataPrismProperties();

        assertThatCode(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("customer-api")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                availableProvider(event -> { }), availableProvider(PrivacyMetrics.none())))
                .doesNotThrowAnyException();
    }

    private static DataSourceAdapter<String> fakeAdapter(String name) {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return name; }
            @Override public Class<String> responseType() { return String.class; }
            @Override public String fetch(DataRequest request) { throw new UnsupportedOperationException(); }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return null; }
        };
    }

    private static <T> ObjectProvider<T> availableProvider(T instance) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return instance; }
        };
    }

    private static final class IdentityResolverStub implements IdentityResolver {
        @Override public CanonicalId resolve(SourceRef ref) {
            throw new UnsupportedOperationException();
        }

        @Override public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
            throw new UnsupportedOperationException();
        }
    }
}
