package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A source value that never ends is refused with {@code SOURCE_TREE_TOO_LARGE}, and the other source still
 * answers (task 291). All values are invented.
 */
class SourceTreeNodeCapRefusalTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @LlmExposedModel
    public record Endless(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) List<String> items) {
    }

    @LlmExposedModel
    public record Fine(
            @InternalIdentifier String id,
            @NonSensitive(reason = "Enumerated state") String status) {
    }

    private static final class EndlessList extends AbstractList<String> {
        @Override
        public String get(int index) {
            return "item@example.invalid";
        }

        @Override
        public int size() {
            return Integer.MAX_VALUE;
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public String next() {
                    return "item@example.invalid";
                }
            };
        }
    }

    private static final class EndlessAdapter implements DataSourceAdapter<Endless> {
        @Override
        public String sourceName() {
            return "a";
        }

        @Override
        public Class<Endless> responseType() {
            return Endless.class;
        }

        @Override
        public Endless fetch(DataRequest request) {
            return new Endless("id-1", Collections.unmodifiableList(new EndlessList()));
        }
    }

    private static final class FineAdapter implements DataSourceAdapter<Fine> {
        @Override
        public String sourceName() {
            return "b";
        }

        @Override
        public Class<Fine> responseType() {
            return Fine.class;
        }

        @Override
        public Fine fetch(DataRequest request) {
            return new Fine("id-1", "ACTIVE");
        }
    }

    @Test
    @DisplayName("an endless source is REFUSED:SOURCE_TREE_TOO_LARGE and the other source still answers")
    void otherSourceStillAnswers() {
        List<AuditEvent> audited = new ArrayList<>();
        AuditSink sink = audited::add;
        DataPrismAssembly assembly = new DataPrismAssembly(
                List.of(new EndlessAdapter(), new FineAdapter()), FIXED, sink);

        ContextResponse response = assertTimeoutPreemptively(Duration.ofSeconds(60), () ->
                assembly.orchestrator().buildContext(ContextRequest.of("CUSTOMER", "123"),
                        assembly.privacyContext(), assembly.investigationContext()));

        assertThat(response.sources().values().stream().map(r -> r.status()))
                .containsExactlyInAnyOrder("REFUSED", "ANSWERED");
        assertThat(response.sources().values().stream().filter(r -> r.isAnswered()).findFirst().orElseThrow()
                .entity().toString()).contains("ACTIVE");
        assertThat(audited).anySatisfy(event -> assertThat(event.fieldDispositions().values())
                .contains("REFUSED:SOURCE_TREE_TOO_LARGE"));
    }
}
