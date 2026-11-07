package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.CachingSyntheticValueSource;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 286: {@code dataprism.hazelcast.lock-timeout} reaches every cluster-backed store the starter builds and the
 * reidentification index feed, read from the stores themselves and not from timing. The oversight state's timeout is
 * wired and tested by task 228.
 */
class ClusterLockTimeoutPassThroughTest {
    private static final Duration CONFIGURED = Duration.ofSeconds(3);
    private static final String[] OPERATOR = {
            "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
            "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=dataprism.operate"};
    private static final String[] REIDENTIFICATION = {
            "dataprism.hazelcast.reidentification-enabled=true",
            "dataprism.hazelcast.reidentification-controls-reference=REVIEWED_REIDENTIFICATION_CONTROLS",
            "dataprism.reidentification.enabled=true", "dataprism.reidentification.purposes[0]=fraud-review",
            "dataprism.reidentification.roles.requester[0]=REQUEST",
            "dataprism.reidentification.roles.approver[0]=APPROVE"};

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    private static Object lockTimeoutOf(Object store) {
        return ReflectionTestUtils.getField(store, "lockTimeout");
    }

    private static void withLockTimeout(Consumer<AssertableWebApplicationContext> assertions) {
        OversightConfigurationTest.runner("embedded", OPERATOR)
                .withPropertyValues(ClusterConfigurationTest.KEYS)
                .withPropertyValues(REIDENTIFICATION)
                .withPropertyValues("dataprism.hazelcast.lock-timeout=PT3S")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertions.accept(result);
                });
    }

    @Test void the_scope_budget_uses_the_configured_lock_timeout() {
        withLockTimeout(c -> assertThat(lockTimeoutOf(c.getBean(ScopeBudget.class))).isEqualTo(CONFIGURED));
    }

    @Test void the_approval_store_uses_the_configured_lock_timeout() {
        withLockTimeout(c -> assertThat(lockTimeoutOf(c.getBean(ApprovalStore.class))).isEqualTo(CONFIGURED));
    }

    @Test void the_caller_rate_limiter_uses_the_configured_lock_timeout() {
        withLockTimeout(c -> assertThat(lockTimeoutOf(c.getBean(CallerRateLimiter.class))).isEqualTo(CONFIGURED));
    }

    @Test void the_reidentification_index_uses_the_configured_lock_timeout() {
        withLockTimeout(c -> {
            // The service keeps the index only inside its lookup, a method reference that captures it.
            Object lookup = ReflectionTestUtils.getField(c.getBean(ReidentificationService.class), "lookup");
            assertThat(lockTimeoutOf(ReflectionTestUtils.getField(lookup, "arg$1"))).isEqualTo(CONFIGURED);
        });
    }

    @Test void the_reidentification_index_feed_uses_the_configured_lock_timeout() {
        withLockTimeout(c -> {
            SyntheticValueSource source = c.getBean(SyntheticValueSource.class);
            assertThat(source).isInstanceOf(CachingSyntheticValueSource.class);
            assertThat(lockTimeoutOf(source)).isEqualTo(CONFIGURED);
        });
    }
}
