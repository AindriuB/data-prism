package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 54 relaxed {@link DataPrismContractValidator#validateIntegrations} from
 * one-to-one equality between {@code dataprism.sources} and the supplied
 * adapter beans to a subset check, since a configured JSON source's {@code
 * DataSourceAdapter} bean (see {@code
 * io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonSourcesInitializer})
 * carries no {@code dataprism.sources.<name>} entry at all. That subset check
 * also admitted any other {@code DataSourceAdapter} bean on the classpath with
 * no review trail at all. Task 69 restores the allow-list, narrowed to exactly
 * the names the JSON-catalogue mechanism publishes: a plain {@code
 * Set<String>} bean {@code data-prism-connectors-rest} registers, when
 * present, under the name {@code dataPrismConfiguredJsonSourceNames} (see
 * {@code ConfiguredJsonSourcesAutoConfiguration#configuredJsonSourceNames}
 * there for why this module never imports that module's own type). {@link
 * FixtureDevelopmentRefusalTest} already covers the STDIO-fixture-development
 * early return this class also observes and is not repeated here.
 */
class DataPrismContractValidatorTest {

    @Test
    void missingSourceAdapterFiresWhenNeitherADataprismSourcesEntryNorAnyAdapterExists() {
        DataPrismProperties properties = new DataPrismProperties();

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties, List.of(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_SOURCE_ADAPTER:");
    }

    /**
     * The collapse task 54 delivered, still true after task 69's narrowing: a
     * configured JSON source supplies its adapter with no {@code
     * dataprism.sources} entry naming it, and that alone must not trip {@code
     * MISSING_SOURCE_ADAPTER} -- provided the JSON-catalogue mechanism itself
     * vouches for the name via its published {@code Set<String>} bean, which
     * is exactly what task 69 requires instead of admitting the bean unconditionally.
     */
    @Test
    void missingSourceAdapterDoesNotFireWhenAConfiguredJsonSourceSuppliesTheOnlyAdapter() {
        DataPrismProperties properties = new DataPrismProperties();

        assertThatCode(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("customer-api")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                availableProvider(event -> { }), availableProvider(PrivacyMetrics.none()),
                availableProvider(Set.of("customer-api"))))
                .doesNotThrowAnyException();
    }

    @Test
    void unresolvedSourceAdapterFiresWhenADataprismSourcesEntryNamesASourceNoAdapterSupplies() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSources().put("orphan", new DataPrismProperties.Source());

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties, List.of(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("UNRESOLVED_SOURCE_ADAPTER:");
    }

    /**
     * Task 69: the property the plain subset check lost. An adapter bean whose
     * name appears neither under {@code dataprism.sources} nor in the
     * JSON-catalogue mechanism's published names is not a reviewed adapter, and
     * must refuse startup rather than being silently admitted the way any
     * classpath {@code DataSourceAdapter} bean was between task 54 and this one.
     */
    @Test
    void unreviewedSourceAdapterFiresWhenASuppliedAdapterIsNamedByNeitherMechanism() {
        DataPrismProperties properties = new DataPrismProperties();

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("unreviewed-adapter")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                availableProvider(event -> { }), availableProvider(PrivacyMetrics.none()),
                emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("UNREVIEWED_SOURCE_ADAPTER:")
                .hasMessageContaining("unreviewed-adapter");
    }

    /**
     * The other allow-listing route: a plain {@code dataprism.sources} entry,
     * with no JSON-catalogue involvement at all, still vouches for its adapter
     * the same way it always did.
     */
    @Test
    void unreviewedSourceAdapterDoesNotFireWhenADataprismSourcesEntryNamesTheSuppliedAdapter() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSources().put("customer-api", new DataPrismProperties.Source());

        assertThatCode(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("customer-api")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                availableProvider(event -> { }), availableProvider(PrivacyMetrics.none()),
                emptyProvider()))
                .doesNotThrowAnyException();
    }

    /**
     * Task 73: {@code approved-sink} is the deployment-supplied-bean contract, so an
     * absent {@link io.github.aindriub.dataprism.audit.AuditSink} bean under it refuses
     * with a code naming that cause, not the generic {@code MISSING_AUDIT_SINK}.
     */
    @Test
    void approvedAuditSinkWithNoAuditSinkBeanFiresAuditSinkBeanRequired() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getAudit().setSink(DataPrismProperties.APPROVED_SINK);
        properties.getSources().put("customer-api", new DataPrismProperties.Source());

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("customer-api")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                emptyProvider(), availableProvider(PrivacyMetrics.none()), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("AUDIT_SINK_BEAN_REQUIRED:")
                .hasMessageContaining("dataprism.audit.sink=approved-sink");
    }

    /**
     * The sibling of the test above: the same {@code approved-sink} configuration with
     * an {@code AuditSink} bean actually present must not throw at all -- so the test
     * above cannot pass merely because construction fails for an unrelated reason.
     */
    @Test
    void approvedAuditSinkWithAnAuditSinkBeanPresentDoesNotThrow() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getAudit().setSink(DataPrismProperties.APPROVED_SINK);
        properties.getSources().put("customer-api", new DataPrismProperties.Source());

        assertThatCode(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("customer-api")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                availableProvider(event -> { }), availableProvider(PrivacyMetrics.none()), emptyProvider()))
                .doesNotThrowAnyException();
    }

    /**
     * The failure {@code AUDIT_SINK_BEAN_REQUIRED} does not describe: an absent bean
     * under any other configured sink value still raises the generic
     * {@code MISSING_AUDIT_SINK}, so the two remain distinguishable in a dashboard.
     */
    @Test
    void nonApprovedSinkWithNoAuditSinkBeanStillFiresMissingAuditSink() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getAudit().setSink("slf4j");
        properties.getSources().put("customer-api", new DataPrismProperties.Source());

        assertThatThrownBy(() -> DataPrismContractValidator.validateIntegrations(properties,
                List.of(fakeAdapter("customer-api")),
                availableProvider(new IdentityResolverStub()), availableProvider((k, r) -> new byte[0]),
                emptyProvider(), availableProvider(PrivacyMetrics.none()), emptyProvider()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_AUDIT_SINK:");
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
