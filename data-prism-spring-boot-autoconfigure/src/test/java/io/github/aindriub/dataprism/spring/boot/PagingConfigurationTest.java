package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueries;
import io.github.aindriub.dataprism.orchestration.catalog.PagingSettings;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code dataprism.paging.*}, and the rule that an estate larger than one default page needs a link key. */
class PagingConfigurationTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedIntegrations.class)
            .withPropertyValues(valid());

    @Test
    void the_decided_defaults_reach_the_catalog_queries() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            PagingSettings paging = result.getBean(CatalogQueries.class).paging();
            assertThat(paging.defaultPageSize()).isEqualTo(25);
            assertThat(paging.maxPageSize()).isEqualTo(100);
            assertThat(paging.lifetime()).isEqualTo(Duration.ofMinutes(15));
        });
    }

    @Test
    void the_three_keys_bind_and_reach_the_catalog_queries() {
        context.withPropertyValues("dataprism.paging.list-sources.page-size=10",
                "dataprism.paging.list-sources.max-page-size=40", "dataprism.paging.lifetime=PT5M").run(result -> {
                    assertThat(result).hasNotFailed();
                    PagingSettings paging = result.getBean(CatalogQueries.class).paging();
                    assertThat(paging.defaultPageSize()).isEqualTo(10);
                    assertThat(paging.maxPageSize()).isEqualTo(40);
                    assertThat(paging.lifetime()).isEqualTo(Duration.ofMinutes(5));
                });
    }

    @ParameterizedTest
    @CsvSource({"list-sources.page-size, 0", "list-sources.page-size, -3", "list-sources.max-page-size, 0",
            "list-sources.max-page-size, -3", "lifetime, PT0S", "lifetime, -PT3M"})
    void a_non_positive_value_refuses_startup_naming_only_the_property(String key, String value) {
        context.withPropertyValues("dataprism.paging." + key + "=" + value).run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("INVALID_PAGING_SETTING");
            assertThat(refusal.getMessage()).contains("dataprism.paging." + key).doesNotContain("-3");
        });
    }

    @Test
    void a_page_size_above_the_maximum_refuses_startup() {
        context.withPropertyValues("dataprism.paging.list-sources.page-size=101").run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("INVALID_PAGING_SETTING");
            assertThat(refusal.getMessage()).contains("dataprism.paging.list-sources.page-size")
                    .doesNotContain("101");
        });
    }

    @Test
    void a_page_size_equal_to_the_maximum_starts() {
        context.withPropertyValues("dataprism.paging.list-sources.page-size=100")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void an_estate_of_exactly_one_default_page_starts_without_a_link_key() {
        manySources(24).run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void an_estate_of_one_page_plus_one_refuses_startup_without_a_link_key() {
        manySources(25).run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("MISSING_PAGING_KEY");
            assertThat(refusal.getMessage()).contains("dataprism.links.key-id").doesNotContain("src-");
        });
    }

    @Test
    void the_edge_follows_the_configured_page_size() {
        manySources(4).withPropertyValues("dataprism.paging.list-sources.page-size=5")
                .run(result -> assertThat(result).hasNotFailed());
        manySources(5).withPropertyValues("dataprism.paging.list-sources.page-size=5").run(result -> {
            assertThat(result).hasFailed();
            assertThat(refusal(result.getStartupFailure()).code()).isEqualTo("MISSING_PAGING_KEY");
        });
    }

    @Test
    void an_estate_of_one_page_plus_one_starts_with_a_link_key() {
        manySources(25).withPropertyValues("dataprism.links.key-id=lk1",
                "dataprism.links.keys.lk1.environment-variable=LINK_KEY_REF")
                .run(result -> assertThat(result).hasNotFailed());
    }

    /** The reviewed customer adapter plus {@code extra} more, each with the source entry it must have. */
    private WebApplicationContextRunner manySources(int extra) {
        WebApplicationContextRunner runner = context;
        List<String> properties = new ArrayList<>();
        for (int i = 0; i < extra; i++) {
            String name = "src-" + String.format("%02d", i);
            runner = runner.withBean(name, DataSourceAdapter.class, () -> adapter(name));
            properties.add("dataprism.sources." + name + ".base-url=https://" + name + ".example");
            properties.add("dataprism.sources." + name + ".timeout=2s");
        }
        return runner.withPropertyValues(properties.toArray(String[]::new));
    }

    private static DataSourceAdapter<TestPayload> adapter(String name) {
        return new DataSourceAdapter<>() {
            public String sourceName() { return name; }
            public Class<TestPayload> responseType() { return TestPayload.class; }
            public TestPayload fetch(DataRequest request) { return null; }
        };
    }

    private static DataPrismConfigurationException refusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e) {
                return e;
            }
        }
        throw new AssertionError("no DataPrismConfigurationException in the cause chain", failure);
    }

    private static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                "dataprism.security-policy.case-authorisation=token-trusted",
                "dataprism.security-policy.client-allowlist=unrestricted",
                "dataprism.security-policy.purposes[0]=investigation",
                "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                "dataprism.privacy.hmac-key.key-id=v1",
                "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                "dataprism.metrics.sink=micrometer",
                "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    @Configuration(proxyBeanMethods = false)
    static class ReviewedIntegrations {
        @Bean
        DataSourceAdapter<TestPayload> customerAdapter() {
            return adapter("customer");
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }
}
