package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.spring.boot.validation.DataPrismPropertiesValidator;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 202: the lock timeout and the approval quorum are configuration, refused when out of range or
 * pointless, and they reach the stores and the map that use them.
 */
class ClusterLockTimeoutConfigurationTest {

    private static final String[] OPERATOR = {
            "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
            "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=dataprism.operate"};
    private static final String TCP_IP = "dataprism.hazelcast.join.mode=tcp-ip";
    /** Approvals in use through a gated tool. */
    private static final String[] TOOL_APPROVALS = {"dataprism.oversight.approval-required-tools[0]=get_entity_context"};
    /** Approvals in use through four-eyes re-identification, with everything else it needs. */
    private static final String[] REIDENTIFICATION = {
            "dataprism.hazelcast.reidentification-enabled=true",
            "dataprism.hazelcast.reidentification-controls-reference=REVIEWED_REIDENTIFICATION_CONTROLS",
            "dataprism.reidentification.enabled=true", "dataprism.reidentification.purposes[0]=fraud-review",
            "dataprism.reidentification.roles.requester[0]=REQUEST",
            "dataprism.reidentification.roles.approver[0]=APPROVE"};

    private static String[] join(String[]... groups) {
        java.util.List<String> all = new java.util.ArrayList<>();
        for (String[] g : groups) {
            all.addAll(java.util.Arrays.asList(g));
        }
        return all.toArray(String[]::new);
    }

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static void refuses(String code, String topology, String... properties) {
        OversightConfigurationTest.runner(topology, properties).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith(code + ":");
        });
    }

    // ---- lock timeout --------------------------------------------------------------------------

    @Test void the_lock_timeout_defaults_to_two_seconds() {
        OversightConfigurationTest.runner("embedded").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(DataPrismProperties.class).getHazelcast().getLockTimeout())
                    .isEqualTo(Duration.ofSeconds(2));
        });
    }
    @Test void refuses_a_lock_timeout_below_the_minimum() {
        refuses("INVALID_LOCK_TIMEOUT", "embedded", "dataprism.hazelcast.lock-timeout=PT0.099S");
    }
    @Test void refuses_a_lock_timeout_above_the_maximum() {
        refuses("INVALID_LOCK_TIMEOUT", "embedded", "dataprism.hazelcast.lock-timeout=PT31S");
    }
    @Test void refuses_a_zero_lock_timeout() {
        refuses("INVALID_LOCK_TIMEOUT", "embedded", "dataprism.hazelcast.lock-timeout=PT0S");
    }
    @Test void accepts_the_limits_of_the_range() {
        for (String edge : new String[] {"PT0.1S", "PT30S"}) {
            OversightConfigurationTest.runner("embedded", "dataprism.hazelcast.lock-timeout=" + edge)
                    .run(result -> assertThat(result).hasNotFailed());
            Hazelcast.shutdownAll();
        }
    }
    @Test void single_node_refuses_a_lock_timeout() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", "dataprism.hazelcast.lock-timeout=PT1S");
    }

    private static <T> void refusesWithinTheTimeoutAndHalfASecond(Supplier<T> call, ExecutorService caller) throws Exception {
        long start = System.nanoTime();
        Future<T> running = caller.submit(call::get);
        assertThatThrownBy(() -> running.get(2, TimeUnit.SECONDS)).satisfies(failure -> {
            assertThat(failure).isNotInstanceOf(TimeoutException.class);
            assertThat(failure.getCause()).isInstanceOf(ClusterLockTimeoutException.class);
        });
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(700);
    }

    private static void holdWhile(IMap<String, Object> map, String key, ThrowingRunnable body) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            map.lock(key);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            body.run();
        } finally {
            map.forceUnlock(key);
            release.countDown();
            holder.join();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test void the_bound_lock_timeout_reaches_all_three_stores() {
        OversightConfigurationTest.runner("embedded", "dataprism.hazelcast.lock-timeout=PT0.2S").run(result -> {
            assertThat(result).hasNotFailed();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                PrivacyCluster cluster = result.getBean(PrivacyCluster.class);
                ApprovalStore approvals = result.getBean(ApprovalStore.class);
                CallerRateLimiter limiter = result.getBean(CallerRateLimiter.class);
                ScopeBudget budget = result.getBean(ScopeBudget.class);
                Instant now = Instant.now();
                ApprovalRequest request = new ApprovalRequest("ctx-1", ApprovalRequest.Kind.TOOL_CALL, "alice", "c",
                        "scope-1", "search", "fp", null, null, "p", "case", now, now.plusSeconds(60),
                        ApprovalRequest.Status.PENDING, null, null);
                holdWhile(cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP), "ctx-1",
                        () -> refusesWithinTheTimeoutAndHalfASecond(() -> approvals.create(request), caller));
                holdWhile(cluster.instance().getMap(PrivacyCluster.CALLER_RATE_MAP), "alice",
                        () -> refusesWithinTheTimeoutAndHalfASecond(
                                () -> limiter.tryAcquire("alice", 5, Duration.ofMinutes(1), now), caller));
                // One read first, so the subject's key exists and can be found without knowing how it is built.
                assertThat(budget.tryRead("scope-1", "subject-1", 5)).isTrue();
                IMap<String, Object> counts = cluster.instance().getMap(PrivacyCluster.BUDGET_MAP);
                assertThat(counts.keySet()).hasSize(1);
                holdWhile(counts, counts.keySet().iterator().next(),
                        () -> refusesWithinTheTimeoutAndHalfASecond(
                                () -> budget.tryRead("scope-1", "subject-1", 5), caller));
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                caller.shutdownNow();
            }
        });
    }

    // ---- approval quorum -----------------------------------------------------------------------

    @Test void refuses_a_minimum_above_the_listed_members() {
        refuses("INVALID_APPROVAL_QUORUM", "embedded", TCP_IP, "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.members[0]=127.0.0.1:5999", "dataprism.hazelcast.join.members[1]=127.0.0.1:6000",
                "dataprism.hazelcast.approval-minimum-members=3");
    }
    @Test void counts_distinct_members_only() {
        refuses("INVALID_APPROVAL_QUORUM", "embedded", TCP_IP, "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.members[0]=127.0.0.1:5999", "dataprism.hazelcast.join.members[1]=127.0.0.1:5999",
                "dataprism.hazelcast.approval-minimum-members=2");
    }
    @Test void counts_a_range_and_a_comma_list_as_their_members() {
        refuses("INVALID_APPROVAL_QUORUM", "embedded", TCP_IP, "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.members[0]=10.0.0.1-3", "dataprism.hazelcast.approval-minimum-members=4");
        refuses("INVALID_APPROVAL_QUORUM", "embedded", TCP_IP, "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.members[0]=10.0.0.1,10.0.0.2,10.0.0.3", "dataprism.hazelcast.approval-minimum-members=4");
        refuses("INVALID_APPROVAL_QUORUM", "embedded", TCP_IP, "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.members[0]=10.0.0.1", "dataprism.hazelcast.join.members[1]=10.0.0.1:5701",
                "dataprism.hazelcast.member.port=5701", "dataprism.hazelcast.approval-minimum-members=2");
    }
    @Test void accepts_a_minimum_of_two_for_a_range_of_three() {
        int port = ClusterConfigurationTest.freePort();
        OversightConfigurationTest.runner("embedded").withPropertyValues(TCP_IP,
                "dataprism.hazelcast.join.members[0]=127.0.0.1-3:" + port,
                "dataprism.hazelcast.approval-minimum-members=2",
                "dataprism.hazelcast.member.port=" + port, "dataprism.hazelcast.member.interface=127.0.0.1")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(minimumOnTheApprovalMap(result)).isEqualTo(2);
                });
    }
    @Test void refuses_an_uncountable_entry_when_approvals_are_in_use() {
        refuses("INVALID_APPROVAL_QUORUM", "embedded", join(OPERATOR, TOOL_APPROVALS, new String[] {TCP_IP,
                "dataprism.hazelcast.cluster-name=quorum-1", "dataprism.hazelcast.join.members[0]=10.0.0.*"}));
        refuses("INVALID_APPROVAL_QUORUM", "embedded", join(OPERATOR, REIDENTIFICATION, new String[] {TCP_IP,
                "dataprism.hazelcast.cluster-name=quorum-1", "dataprism.hazelcast.join.members[0]=10.0.0.*"}));
    }
    @Test void a_hostname_requires_an_explicit_minimum_when_approvals_are_in_use() {
        refuses("MISSING_APPROVAL_QUORUM", "embedded", join(OPERATOR, TOOL_APPROVALS, new String[] {TCP_IP,
                "dataprism.hazelcast.cluster-name=quorum-1", "dataprism.hazelcast.join.members[0]=10.0.0.1",
                "dataprism.hazelcast.join.members[1]=db.internal"}));
        refuses("MISSING_APPROVAL_QUORUM", "embedded", join(OPERATOR, REIDENTIFICATION, new String[] {TCP_IP,
                "dataprism.hazelcast.cluster-name=quorum-1", "dataprism.hazelcast.join.members[0]=10.0.0.1",
                "dataprism.hazelcast.join.members[1]=db.internal"}));
    }
    @Test void hostnames_need_no_quorum_when_approvals_are_not_in_use() {
        int port = ClusterConfigurationTest.freePort();
        for (String member : new String[] {"localhost:" + (port + 1)}) {
            OversightConfigurationTest.runner("embedded").withPropertyValues(TCP_IP,
                    "dataprism.hazelcast.join.members[0]=127.0.0.1:" + port, "dataprism.hazelcast.join.members[1]=" + member,
                    "dataprism.hazelcast.member.port=" + port, "dataprism.hazelcast.member.interface=127.0.0.1")
                    .run(result -> {
                        assertThat(result).hasNotFailed();
                        assertThat(minimumOnTheApprovalMap(result)).isZero();
                    });
            Hazelcast.shutdownAll();
        }
    }
    /** The cluster validation alone, so no member is started (a wildcard would make one try 256 addresses). */
    private static DataPrismPropertiesValidator clusterValidator(boolean approvalsInUse, Integer minimum, String member) {
        DataPrismProperties props = new DataPrismProperties();
        HazelcastProperties h = props.getHazelcast();
        h.setTopology("embedded");
        h.setClusterName("quorum-validator");
        h.getJoin().setMode("tcp-ip");
        h.getJoin().setMembers(java.util.List.of(member));
        h.setApprovalMinimumMembers(minimum);
        if (approvalsInUse) {
            props.getOversight().setApprovalRequiredTools(java.util.List.of("get_entity_context"));
        }
        return new DataPrismPropertiesValidator(props);
    }

    @Test void a_wildcard_member_raises_no_refusal_when_approvals_are_not_in_use() {
        clusterValidator(false, null, "10.0.0.*").validateCluster(false, null, null);
        clusterValidator(false, 1, "10.0.0.*").validateCluster(false, null, null);
    }
    @Test void a_wildcard_member_with_an_explicit_minimum_is_still_refused_when_approvals_are_in_use() {
        assertThatThrownBy(() -> clusterValidator(true, 2, "10.0.0.*").validateCluster(false, null, null))
                .isInstanceOf(DataPrismConfigurationException.class).hasMessageStartingWith("INVALID_APPROVAL_QUORUM");
    }
    @Test void kubernetes_requires_a_minimum_when_reidentification_is_enabled() {
        refuses("MISSING_APPROVAL_QUORUM", "embedded", join(OPERATOR, REIDENTIFICATION, new String[] {
                "dataprism.hazelcast.cluster-name=quorum-1", "dataprism.hazelcast.join.mode=kubernetes",
                "dataprism.hazelcast.join.kubernetes.namespace=ns", "dataprism.hazelcast.join.kubernetes.service-name=svc"}));
    }
    @Test void a_hostname_with_an_explicit_minimum_is_accepted() {
        int port = ClusterConfigurationTest.freePort();
        OversightConfigurationTest.runner("embedded").withPropertyValues(TCP_IP,
                "dataprism.hazelcast.join.members[0]=127.0.0.1:" + port, "dataprism.hazelcast.join.members[1]=localhost:" + (port + 1),
                "dataprism.hazelcast.approval-minimum-members=2",
                "dataprism.hazelcast.member.port=" + port, "dataprism.hazelcast.member.interface=127.0.0.1")
                .run(result -> assertThat(result).hasNotFailed());
    }
    @Test void a_portless_entry_is_the_configured_member_port() {
        refuses("INVALID_APPROVAL_QUORUM", "embedded", TCP_IP, "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.members[0]=10.0.0.1", "dataprism.hazelcast.join.members[1]=10.0.0.1:5702",
                "dataprism.hazelcast.member.port=5702", "dataprism.hazelcast.approval-minimum-members=2");
    }
    @Test void refuses_a_minimum_below_one() {
        refuses("INVALID_APPROVAL_QUORUM", "embedded", TCP_IP, "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.members[0]=127.0.0.1:5999", "dataprism.hazelcast.approval-minimum-members=0");
    }
    @Test void refuses_a_minimum_with_a_single_member() {
        refuses("INVALID_APPROVAL_QUORUM", "embedded", "dataprism.hazelcast.cluster-name=quorum-1",
                "dataprism.hazelcast.join.mode=none", "dataprism.hazelcast.approval-minimum-members=1");
    }
    @Test void kubernetes_requires_a_minimum_when_approval_tools_are_configured() {
        String[] kubernetes = {"dataprism.hazelcast.cluster-name=quorum-1", "dataprism.hazelcast.join.mode=kubernetes",
                "dataprism.hazelcast.join.kubernetes.namespace=ns", "dataprism.hazelcast.join.kubernetes.service-name=svc",
                "dataprism.oversight.approval-required-tools[0]=get_entity_context"};
        String[] all = new String[OPERATOR.length + kubernetes.length];
        System.arraycopy(OPERATOR, 0, all, 0, OPERATOR.length);
        System.arraycopy(kubernetes, 0, all, OPERATOR.length, kubernetes.length);
        refuses("MISSING_APPROVAL_QUORUM", "embedded", all);
    }
    @Test void single_node_refuses_a_minimum() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", "dataprism.hazelcast.approval-minimum-members=2");
    }

    /** The minimum the approval map is protected with, or 0 when it is not protected. */
    private static int minimumOnTheApprovalMap(AssertableWebApplicationContext result) {
        var config = result.getBean(PrivacyCluster.class).instance().getConfig();
        String name = config.getMapConfig(PrivacyCluster.APPROVAL_MAP).getSplitBrainProtectionName();
        return name == null ? 0 : config.getSplitBrainProtectionConfig(name).getMinimumClusterSize();
    }

    @Test void a_single_member_needs_no_protection() {
        OversightConfigurationTest.runner("embedded").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(minimumOnTheApprovalMap(result)).isZero();
        });
    }
    @Test void tcp_ip_defaults_to_a_majority_of_the_listed_members() {
        int port = ClusterConfigurationTest.freePort();
        OversightConfigurationTest.runner("embedded").withPropertyValues(join(OPERATOR, TOOL_APPROVALS)).withPropertyValues(TCP_IP,
                "dataprism.hazelcast.join.members[0]=127.0.0.1:" + port,
                "dataprism.hazelcast.join.members[1]=127.0.0.1:" + (port + 1),
                "dataprism.hazelcast.join.members[2]=127.0.0.1:" + (port + 2),
                "dataprism.hazelcast.member.port=" + port, "dataprism.hazelcast.member.interface=127.0.0.1")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(minimumOnTheApprovalMap(result)).isEqualTo(2);
                });
    }
    @Test void an_explicit_minimum_reaches_the_approval_map() {
        int port = ClusterConfigurationTest.freePort();
        OversightConfigurationTest.runner("embedded").withPropertyValues(TCP_IP,
                "dataprism.hazelcast.join.members[0]=127.0.0.1:" + port,
                "dataprism.hazelcast.join.members[1]=127.0.0.1:" + (port + 1),
                "dataprism.hazelcast.join.members[2]=127.0.0.1:" + (port + 2),
                "dataprism.hazelcast.approval-minimum-members=3",
                "dataprism.hazelcast.member.port=" + port, "dataprism.hazelcast.member.interface=127.0.0.1")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(minimumOnTheApprovalMap(result)).isEqualTo(3);
                });
    }
}
