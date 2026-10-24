package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterLocksDeleteAllTest {

    @Test
    @SuppressWarnings("unchecked")
    void aRefusedLockOnTheFirstKeyStillDeletesTheOthersAndThenThrows() {
        List<Object> deleted = new ArrayList<>();
        IMap<String, Object> map = (IMap<String, Object>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IMap.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "tryLock":
                            if ("a".equals(args[0])) {
                                throw new IllegalArgumentException("lease too long");
                            }
                            return Boolean.TRUE;
                        case "delete":
                            deleted.add(args[0]);
                            return null;
                        default:
                            return null;
                    }
                });

        assertThatThrownBy(() -> ClusterLocks.deleteAll(map, List.of("a", "b", "c"), Duration.ofSeconds(1)))
                .isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(deleted).containsExactly("b", "c");
    }
}
