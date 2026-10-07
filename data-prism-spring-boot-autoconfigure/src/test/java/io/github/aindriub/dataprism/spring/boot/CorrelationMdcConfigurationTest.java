package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 148: {@code dataprism.correlation.mdc-key} bound, validated and wired. */
class CorrelationMdcConfigurationTest {

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(AuditRetentionConfigurationTest.Integrations.class)
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=slf4j");
    }

    private static WebApplicationContextRunner withHeader(String mdcKey) {
        return runner().withPropertyValues("dataprism.correlation.inbound.header=X-Transaction-Id",
                "dataprism.correlation.mdc-key=" + mdcKey);
    }

    private static void assertRefusedWith(WebApplicationContextRunner r, String code) {
        r.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            while (failure.getCause() != null && !(failure instanceof DataPrismConfigurationException)) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
            assertThat(((DataPrismConfigurationException) failure).code()).isEqualTo(code);
        });
    }

    @Test
    void unset_by_default_and_the_bean_is_off() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DataPrismProperties.class).getCorrelation().getMdcKey()).isNull();
            assertThat(context.getBean(CorrelationMdc.class).enabled()).isFalse();
        });
    }

    @Test
    void a_valid_key_is_bound_and_the_bean_is_on() {
        for (String key : new String[] {"transaction_id", "x_correlation_id"}) {
            withHeader(key).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(DataPrismProperties.class).getCorrelation().getMdcKey()).isEqualTo(key);
                assertThat(context.getBean(CorrelationMdc.class).enabled()).isTrue();
            });
        }
    }

    @Test
    void a_blank_key_is_refused() {
        assertRefusedWith(withHeader(""), "INVALID_CORRELATION_MDC_KEY");
    }

    @Test
    void a_key_with_a_leading_digit_is_refused() {
        assertRefusedWith(withHeader("1txn"), "INVALID_CORRELATION_MDC_KEY");
    }

    @Test
    void a_key_with_a_space_is_refused() {
        assertRefusedWith(withHeader("trans id"), "INVALID_CORRELATION_MDC_KEY");
    }

    @Test
    void an_at_sign_key_is_refused() {
        assertRefusedWith(withHeader("@timestamp"), "INVALID_CORRELATION_MDC_KEY");
    }

    @Test
    void a_sixty_five_character_key_is_refused() {
        assertRefusedWith(withHeader("a".repeat(65)), "INVALID_CORRELATION_MDC_KEY");
    }

    @Test
    void a_reserved_name_is_refused_whatever_its_case() {
        assertRefusedWith(withHeader("traceId"), "CORRELATION_MDC_KEY_RESERVED");
        assertRefusedWith(withHeader("TRACE_ID"), "CORRELATION_MDC_KEY_RESERVED");
        assertRefusedWith(withHeader("message"), "CORRELATION_MDC_KEY_RESERVED");
    }

    @Test
    void a_reserved_prefix_is_refused() {
        assertRefusedWith(withHeader("ecs.version"), "CORRELATION_MDC_KEY_RESERVED");
        assertRefusedWith(withHeader("Service.name"), "CORRELATION_MDC_KEY_RESERVED");
    }

    @Test
    void every_reserved_name_is_refused() {
        for (String name : CorrelationMdc.RESERVED_NAMES) {
            assertRefusedWith(withHeader(name), "CORRELATION_MDC_KEY_RESERVED");
        }
        for (String prefix : CorrelationMdc.RESERVED_PREFIXES) {
            assertRefusedWith(withHeader(prefix + "x"), "CORRELATION_MDC_KEY_RESERVED");
        }
    }

    @Test
    void a_key_without_an_inbound_header_is_refused() {
        assertRefusedWith(runner().withPropertyValues("dataprism.correlation.mdc-key=transaction_id"),
                "CORRELATION_MDC_KEY_WITHOUT_HEADER");
    }
}
