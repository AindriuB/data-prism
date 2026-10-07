package io.github.aindriub.dataprism.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataRequestTest {

    private static ExternalCorrelationId id() {
        return CorrelationIdPolicy.opaque(CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN)
                .validate("a".repeat(32)).orElseThrow();
    }

    @Test
    @DisplayName("the three-argument constructor and of() default to no context")
    void defaults() {
        assertThat(new DataRequest("t", "s", Map.of()).context()).isEqualTo(SourceCallContext.none());
        assertThat(DataRequest.of("t", "s").context()).isEqualTo(SourceCallContext.none());
        assertThat(DataRequest.of("t", "s").context().externalCorrelationId()).isEmpty();
    }

    @Test
    @DisplayName("a null context becomes none")
    void nullContext() {
        assertThat(new DataRequest("t", "s", Map.of(), null).context()).isEqualTo(SourceCallContext.none());
    }

    @Test
    @DisplayName("withContext returns a copy carrying the id, and parameters are unchanged")
    void withContext() {
        DataRequest base = new DataRequest("t", "s", Map.of("k", "v"));
        DataRequest with = base.withContext(SourceCallContext.of(id()));
        assertThat(with).isNotSameAs(base);
        assertThat(base.context()).isEqualTo(SourceCallContext.none());
        assertThat(with.context().externalCorrelationId()).isPresent();
        assertThat(with.parameters()).isEqualTo(Map.of("k", "v"));
        assertThat(with.parameters().toString()).doesNotContain("a".repeat(32));
        assertThat(with.entityType()).isEqualTo("t");
        assertThat(with.subjectId()).isEqualTo("s");
    }
}
