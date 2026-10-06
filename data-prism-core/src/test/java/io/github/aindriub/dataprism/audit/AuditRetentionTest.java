package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRetentionTest {

    @TempDir
    Path tempDir;

    private final List<AuditCheckpoint> anchors = new ArrayList<>();
    private final AuditCheckpointSink anchorSink = anchors::add;
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    /** Writes events for instance at the given instants through a real recorder; returns them. */
    private List<AuditEvent> write(Path dir, String instance, String... instants) throws IOException {
        List<AuditEvent> out = new ArrayList<>();
        if (sink == null) {
            sink = new SegmentedFileAuditSink(dir);
        }
        for (String instant : instants) {
            Clock c = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
            out.add(recorderFor(sink, instance, c).record("p", "c", "t", "E", "ps", "fp", "D", "s", "i",
                    "CASE", "ALLOW", Set.of("a:ANSWERED"), Set.of(), "corr"));
        }
        return out;
    }

    private SegmentedFileAuditSink sink;

    @org.junit.jupiter.api.AfterEach
    void closeSink() throws IOException {
        if (sink != null) {
            sink.close();
        }
    }

    private final java.util.Map<String, AuditRecorder> recorders = new java.util.HashMap<>();
    private final java.util.Map<String, Instant[]> clocks = new java.util.HashMap<>();

    private AuditRecorder recorderFor(AuditSink sink, String instance, Clock c) {
        clocks.computeIfAbsent(instance, k -> new Instant[1])[0] = c.instant();
        return recorders.computeIfAbsent(instance, k -> new AuditRecorder(sink, new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clocks.get(instance)[0];
            }
        }, instance));
    }

    private static Set<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void refusesRetentionBelowSixMonthsWithAStableCode() {
        assertThatThrownBy(() -> new AuditRetention(tempDir, Period.ofDays(30), anchorSink, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_RETENTION_BELOW_MINIMUM");
        assertThatThrownBy(() -> new AuditRetention(tempDir, Period.ofDays(180), anchorSink, NOW, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_RETENTION_BELOW_MINIMUM");
    }

    @Test
    void acceptsRetentionBelowSixMonthsOnlyWithTheExplicitOverride() {
        assertThatCode(() -> new AuditRetention(tempDir, Period.ofDays(30), anchorSink, NOW, true))
                .doesNotThrowAnyException();
    }

    @Test
    void sixMonthsExactlyIsAcceptedEitherWay() {
        assertThatCode(() -> new AuditRetention(tempDir, Period.ofMonths(6), anchorSink, NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AuditRetention(tempDir, Period.ofMonths(6), anchorSink, NOW, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AuditRetention(tempDir, Period.ofMonths(12), anchorSink, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void purgeDeletesOnlySegmentsStrictlyBeforeTheCutoffAndAnchorsEachWritersLastRecord() throws IOException {
        // today 2026-09-10, retention 6 months -> cutoff 2026-03-10; segments before it go.
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> a = write(dir, "writer-a", "2026-03-08T01:00:00Z", "2026-03-08T02:00:00Z",
                "2026-03-09T01:00:00Z", "2026-03-10T01:00:00Z");
        List<AuditEvent> b = write(dir, "writer-b", "2026-03-08T03:00:00Z", "2026-03-11T01:00:00Z");
        write(dir, "writer-a", "2026-09-10T01:00:00Z");

        List<Path> deleted = new AuditRetention(dir, Period.ofMonths(6), anchorSink, NOW).purge();

        assertThat(deleted.stream().map(p -> p.getFileName().toString()))
                .containsExactly("audit-2026-03-08.jsonl", "audit-2026-03-09.jsonl");
        assertThat(names(dir)).containsExactlyInAnyOrder("audit-2026-03-10.jsonl", "audit-2026-03-11.jsonl",
                "audit-2026-09-10.jsonl");
        assertThat(anchors).allMatch(c -> c.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR);
        assertThat(anchors).extracting(AuditCheckpoint::instanceId, AuditCheckpoint::sequence,
                AuditCheckpoint::headHash).containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(a.get(0).instanceId(), 2L, a.get(1).eventHash()),
                        org.assertj.core.groups.Tuple.tuple(b.get(0).instanceId(), 1L, b.get(0).eventHash()),
                        org.assertj.core.groups.Tuple.tuple(a.get(0).instanceId(), 3L, a.get(2).eventHash()));
    }

    @Test
    void purgeNeverDeletesTodaysSegmentEvenWithAShortOverriddenRetention() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "writer-a", "2026-09-09T01:00:00Z", "2026-09-10T01:00:00Z");

        List<Path> deleted = new AuditRetention(dir, Period.ZERO, anchorSink, NOW, true).purge();

        assertThat(deleted.stream().map(p -> p.getFileName().toString())).containsExactly("audit-2026-09-09.jsonl");
        assertThat(names(dir)).containsExactly("audit-2026-09-10.jsonl");
    }

    @Test
    void ifAnAnchorWriteFailsNothingIsDeletedAndPurgeThrows() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "writer-a", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z");
        List<AuditCheckpoint> written = new ArrayList<>();
        AuditCheckpointSink failSecond = cp -> {
            if (!written.isEmpty()) {
                throw new IllegalStateException("anchor store down");
            }
            written.add(cp);
        };

        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), failSecond, NOW).purge())
                .isInstanceOf(AuditRetention.RetentionException.class)
                .hasMessageContaining("nothing was deleted");

        assertThat(names(dir)).containsExactlyInAnyOrder("audit-2026-03-01.jsonl", "audit-2026-03-02.jsonl");
    }

    @Test
    void anchorsAreWrittenBeforeTheFirstDeletion() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "writer-a", "2026-03-01T01:00:00Z");
        AuditCheckpointSink checking = cp -> {
            try {
                assertThat(names(dir)).contains("audit-2026-03-01.jsonl");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };

        new AuditRetention(dir, Period.ofMonths(6), checking, NOW).purge();

        assertThat(names(dir)).isEmpty();
    }
}
