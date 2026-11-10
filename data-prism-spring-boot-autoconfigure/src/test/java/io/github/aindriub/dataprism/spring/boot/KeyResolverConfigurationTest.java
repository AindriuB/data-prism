package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dataprism.key-resolvers.*} and the application {@link KeyResolver} beans: registered as beans only, collected
 * into the registry that {@code follow} uses, refused when two claim one pair or one is malformed, and warned about
 * (count and pair names only) when a pair joins a namespace no source or field uses.
 */
@ExtendWith(OutputCaptureExtension.class)
class KeyResolverConfigurationTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(LinkKeyConfigurationTest.ReviewedIntegrations.class)
            .withPropertyValues(OversightConfigurationTest.base("single-node"))
            .withPropertyValues("dataprism.links.key-id=lk1", "dataprism.links.keys.lk1.environment-variable=LINK_REF",
                    "dataprism.links.enabled=true");

    static KeyResolver resolver(String name, String from, String to) {
        return new KeyResolver() {
            public String name() { return name; }
            public KeyNamespace from() { return from == null ? null : new KeyNamespace(from); }
            public KeyNamespace to() { return to == null ? null : new KeyNamespace(to); }
            public Resolution resolve(String key, ResolutionContext ctx, int max) { return new Resolution(List.of(), false); }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class OnePair {
        @Bean KeyResolver accountToCustomer() { return resolver("account-to-customer", "account-no", "customer-no"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoForOnePair {
        @Bean KeyResolver first() { return resolver("first-one", "account-no", "customer-no"); }
        @Bean KeyResolver second() { return resolver("second-one", "account-no", "customer-no"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class NamesOppositeToOrder {
        @Bean @Order(1) KeyResolver zulu() { return resolver("zulu", "account-no", "customer-no"); }
        @Bean @Order(2) KeyResolver alpha() { return resolver("alpha", "account-no", "branch-no"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoWithOneName {
        @Bean KeyResolver first() { return resolver("same-name", "account-no", "customer-no"); }
        @Bean KeyResolver second() { return resolver("same-name", "customer-no", "account-no"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class IntoItself {
        @Bean KeyResolver loop() { return resolver("loop", "account-no", "account-no"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class BadName {
        @Bean KeyResolver bad() { return resolver("Bad Name!", "account-no", "customer-no"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class Throwing {
        @Bean KeyResolver broken() {
            return new KeyResolver() {
                public String name() { throw new IllegalStateException("secret-resolver-detail"); }
                public KeyNamespace from() { return new KeyNamespace("a"); }
                public KeyNamespace to() { return new KeyNamespace("b"); }
                public Resolution resolve(String key, ResolutionContext ctx, int max) { return null; }
            };
        }
    }

    @Test
    void the_decided_defaults_reach_follow() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            OrchestrationOptions.FollowSettings follow = result.getBean(OrchestrationOptions.class).follow().get();
            assertThat(follow.maxTargets()).isEqualTo(5);
            assertThat(follow.resolverTimeout()).hasSeconds(2);
            assertThat(follow.budgetCost()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @CsvSource({"max-targets, 0", "max-targets, 17", "max-targets, -1", "max-targets, lots", "timeout, PT0S",
            "timeout, -PT1S", "timeout, eventually", "budget-cost, 0", "budget-cost, -2", "budget-cost, much"})
    void an_out_of_range_value_refuses_startup_with_the_invalid_family_code_naming_only_the_property(String key,
                                                                                                    String value) {
        for (WebApplicationContextRunner runner : List.of(context,
                new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                        .withUserConfiguration(LinkKeyConfigurationTest.ReviewedIntegrations.class)
                        .withPropertyValues(OversightConfigurationTest.base("single-node")))) {
            runner.withPropertyValues("dataprism.key-resolvers." + key + "=" + value).run(result -> {
                assertThat(result).hasFailed();
                DataPrismConfigurationException refusal = LinksConfigurationTest.refusal(result.getStartupFailure());
                assertThat(refusal.code()).isEqualTo("INVALID_KEY_RESOLVER_SETTING");
                assertThat(refusal.getMessage()).contains("dataprism.key-resolvers." + key);
                if (value.length() > 3) {
                    assertThat(refusal.getMessage()).doesNotContain(value);
                }
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"max-targets, 1", "max-targets, 16", "timeout, PT0.001S", "budget-cost, 1000"})
    void the_edges_are_accepted(String key, String value) {
        context.withPropertyValues("dataprism.key-resolvers." + key + "=" + value)
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void follow_lists_resolvers_in_name_order_whatever_their_order_annotation() {
        context.withUserConfiguration(NamesOppositeToOrder.class).run(result -> {
            assertThat(result).hasNotFailed();
            OrchestrationOptions.FollowSettings follow = result.getBean(OrchestrationOptions.class).follow().get();
            assertThat(follow.resolvers().from(new KeyNamespace("account-no"))).extracting(KeyResolver::name)
                    .containsExactly("alpha", "zulu");
        });
    }

    @Test
    void two_resolvers_for_one_pair_refuse_startup() {
        refused(context.withUserConfiguration(TwoForOnePair.class), "DUPLICATE_KEY_RESOLVER");
    }

    @Test
    void two_resolvers_with_one_name_refuse_startup() {
        refused(context.withUserConfiguration(TwoWithOneName.class), "DUPLICATE_KEY_RESOLVER");
    }

    @Test
    void a_resolver_into_its_own_namespace_refuses_startup() {
        refused(context.withUserConfiguration(IntoItself.class), "INVALID_KEY_RESOLVER");
    }

    @Test
    void a_resolver_name_outside_the_grammar_refuses_startup() {
        refused(context.withUserConfiguration(BadName.class), "INVALID_KEY_RESOLVER");
    }

    @Test
    void a_resolver_that_throws_when_read_refuses_startup_without_its_detail() {
        refused(context.withUserConfiguration(Throwing.class), "INVALID_KEY_RESOLVER");
    }

    @Test
    void a_valid_resolver_is_collected_and_a_pair_no_source_uses_is_warned_about_by_count_and_name(
            CapturedOutput output) {
        context.withUserConfiguration(OnePair.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(output.getAll()).contains("1 key resolver pair(s)").contains("account-no->customer-no")
                    .doesNotContain("account-to-customer");
        });
    }

    @Test
    void with_links_off_application_resolvers_are_not_collected_and_nothing_is_warned(CapturedOutput output) {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(LinkKeyConfigurationTest.ReviewedIntegrations.class, TwoForOnePair.class)
                .withPropertyValues(OversightConfigurationTest.base("single-node")).run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(output.getAll()).doesNotContain("key resolver pair(s)");
                });
    }

    private static void refused(WebApplicationContextRunner runner, String code) {
        runner.run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = LinksConfigurationTest.refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo(code);
            assertThat(refusal.getMessage()).doesNotContain("secret-resolver-detail").doesNotContain("first-one")
                    .doesNotContain("second-one").doesNotContain("same-name").doesNotContain("account-no")
                    .doesNotContain("Bad Name");
        });
    }
}
