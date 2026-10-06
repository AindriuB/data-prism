package io.github.aindriub.dataprism.oversight;

import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OversightSpisTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    private static ApprovalRequest request(String id, String scope) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, "alice", "client", scope, "tool", "fp", null, null,
                null, null, T0, T0.plusSeconds(60), Status.PENDING, null, null);
    }

    @Test
    void selfApprovalRefused() {
        var store = new InMemoryApprovalStore();
        store.create(request("a", "S"));
        assertThatThrownBy(() -> store.approve("a", "alice", T0))
                .isInstanceOfSatisfying(ApprovalRefusedException.class, e -> assertThat(e.code()).isEqualTo(Code.SELF_APPROVAL));
    }

    @Test
    void approveAfterExpiryRefused() {
        var store = new InMemoryApprovalStore();
        store.create(request("a", "S"));
        assertThatThrownBy(() -> store.approve("a", "bob", T0.plusSeconds(60)))
                .isInstanceOfSatisfying(ApprovalRefusedException.class, e -> assertThat(e.code()).isEqualTo(Code.EXPIRED));
    }

    @Test
    void consumeApprovedSucceedsOnce() {
        var store = new InMemoryApprovalStore();
        store.create(request("a", "S"));
        store.approve("a", "bob", T0);
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "S", "tool", "fp", T0)).isPresent();
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "S", "tool", "fp", T0)).isEmpty();
    }

    @Test
    void consumeNeverReturnsPendingOrRejected() {
        var store = new InMemoryApprovalStore();
        store.create(request("a", "S"));
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "S", "tool", "fp", T0)).isEmpty();
        store.reject("a", "bob", T0);
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "S", "tool", "fp", T0)).isEmpty();
    }

    @Test
    void rateLimiterWindows() {
        var limiter = new InMemoryCallerRateLimiter();
        var w = Duration.ofMinutes(1);
        assertThat(limiter.tryAcquire("p", 2, w, T0)).isTrue();
        assertThat(limiter.tryAcquire("p", 2, w, T0.plusSeconds(1))).isTrue();
        assertThat(limiter.tryAcquire("p", 2, w, T0.plusSeconds(2))).isFalse();
        assertThat(limiter.tryAcquire("p", 2, w, T0.plusSeconds(3))).isFalse();
        assertThat(limiter.tryAcquire("p", 2, w, T0.plusSeconds(60))).isTrue();
    }

    @Test
    void forgetScopeRemovesApprovals() {
        var store = new InMemoryApprovalStore();
        store.create(request("a", "S"));
        store.create(request("b", "S"));
        store.create(request("c", "T"));
        store.forgetScope("S");
        assertThat(store.find("a")).isEmpty();
        assertThat(store.find("b")).isEmpty();
        assertThat(store.find("c")).isPresent();
    }

    @Test
    void pauseFlagsAndSnapshot() {
        var state = new InMemoryOversightState();
        state.pauseTool("t");
        state.pauseScope("S");
        state.pauseAll();
        var snap = state.snapshot();
        assertThat(snap.allPaused()).isTrue();
        assertThat(snap.pausedTools()).containsExactly("t");
        state.resumeAll();
        state.resumeTool("t");
        assertThat(state.allPaused()).isFalse();
        assertThat(state.toolPaused("t")).isFalse();
        assertThat(state.scopePaused("S")).isTrue();
    }
}
