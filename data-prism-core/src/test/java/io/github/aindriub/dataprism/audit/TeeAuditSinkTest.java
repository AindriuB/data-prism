package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeeAuditSinkTest {

    @TempDir
    Path tempDir;

    private static final class Recording implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void record(AuditEvent event) {
            if (failWith != null) {
                throw failWith;
            }
            events.add(event);
        }
    }

    private static AuditEvent event(long seq) {
        return new AuditEvent("e" + seq, Instant.parse("2026-03-01T00:00:00Z"), "p", "c", "t", "CUSTOMER", "s", "f",
                "DEFAULT", "scope", "purpose", "CASE", "ALLOW", Set.of(), Set.of(), "corr", "inst", seq, "prev",
                "hash");
    }

    @Test
    void writesPrimaryThenProjection() {
        List<String> order = new ArrayList<>();
        TeeAuditSink tee = new TeeAuditSink(e -> order.add("primary"), e -> order.add("projection"));
        tee.record(event(1));
        assertThat(order).containsExactly("primary", "projection");
    }

    @Test
    void aPrimaryFailureSkipsTheProjectionAndDoesNotPoisonTheTee() {
        Recording primary = new Recording();
        Recording projection = new Recording();
        TeeAuditSink tee = new TeeAuditSink(primary, projection);
        primary.failWith = new IllegalStateException("primary down");

        assertThatThrownBy(() -> tee.record(event(1))).hasMessage("primary down");
        assertThat(projection.events).isEmpty();

        primary.failWith = null;
        tee.record(event(1));
        assertThat(primary.events).hasSize(1);
        assertThat(projection.events).hasSize(1);
    }

    @Test
    void aProjectionFailurePoisonsTheTeeWithACodeOnlyMessage() {
        Recording primary = new Recording();
        Recording projection = new Recording();
        TeeAuditSink tee = new TeeAuditSink(primary, projection);
        projection.failWith = new UncheckedIOException(new IOException("disk /secret/path full for subject-77"));

        assertThatThrownBy(() -> tee.record(event(1)))
                .isInstanceOf(TeeAuditSink.ProjectionFailedException.class)
                .hasMessage("AUDIT_PROJECTION_FAILED");
        assertThat(primary.events).hasSize(1);

        projection.failWith = null;
        assertThatThrownBy(() -> tee.record(event(2)))
                .isInstanceOf(TeeAuditSink.ProjectionFailedException.class)
                .hasMessage("AUDIT_PROJECTION_FAILED");
        assertThat(primary.events).as("poisoned before the primary is written").hasSize(1);
        assertThat(projection.events).isEmpty();
    }

    @Test
    void afterAProjectionFailureTheRecorderWritesNoSecondRecordWithTheSameSequence() throws IOException {
        Path log = tempDir.resolve("audit.log");
        Recording projection = new Recording();
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        try (FileAuditSink primary = new FileAuditSink(log)) {
            AuditRecorder recorder = new AuditRecorder(new TeeAuditSink(primary, projection), clock, "w");
            recorder.record(entry());
            projection.failWith = new IllegalStateException("projection down");
            assertThatThrownBy(() -> recorder.record(entry())).isInstanceOf(TeeAuditSink.ProjectionFailedException.class);
            // the second record is on disk; the recorder rolled its sequence back
            projection.failWith = null;
            assertThatThrownBy(() -> recorder.record(entry())).isInstanceOf(TeeAuditSink.ProjectionFailedException.class);
            assertThatThrownBy(() -> recorder.record(entry())).isInstanceOf(TeeAuditSink.ProjectionFailedException.class);
        }

        List<String> lines = Files.readAllLines(log);
        assertThat(lines).hasSize(2);
        List<Long> sequences = lines.stream().map(l -> AuditRecordFormat.parse(l).sequence()).toList();
        assertThat(sequences).doesNotHaveDuplicates().containsExactly(1L, 2L);
    }

    private static AuditEntry entry() {
        return new AuditEntry("p", "c", "t", "CUSTOMER", "s", "f", "DEFAULT", "scope", "purpose", "CASE", "ALLOW",
                Set.of(), Set.of(), "corr", java.util.Map.of(), "", "");
    }
}
