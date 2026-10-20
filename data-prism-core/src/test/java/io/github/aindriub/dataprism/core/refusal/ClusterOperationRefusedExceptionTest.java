package io.github.aindriub.dataprism.core.refusal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClusterOperationRefusedExceptionTest {

    private static final String SENTINEL = "sentinel-key-x-1";

    @Test
    @DisplayName("the code, path and message are fixed")
    void fixed() {
        var refused = new ClusterOperationRefusedException(new IllegalStateException());
        assertThat(ClusterOperationRefusedException.CODE).isEqualTo("CLUSTER_OPERATION_REFUSED");
        assertThat(refused.code()).isEqualTo("CLUSTER_OPERATION_REFUSED");
        assertThat(refused.path()).isEqualTo("$");
        assertThat(refused.getMessage())
                .isEqualTo("CLUSTER_OPERATION_REFUSED at $: a cluster operation was refused");
    }

    @Test
    @DisplayName("the cause is chained, and its message is not copied into ours")
    void chained() {
        var cause = new IllegalArgumentException(SENTINEL);
        var refused = new ClusterOperationRefusedException(cause);
        assertThat(refused.getCause()).isSameAs(cause);
        assertThat(refused.getMessage()).doesNotContain(SENTINEL);
        assertThat(refused.toString()).doesNotContain(SENTINEL);
    }
}
