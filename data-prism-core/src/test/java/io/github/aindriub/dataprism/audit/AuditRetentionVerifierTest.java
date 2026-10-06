package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRetentionVerifierTest {

    @TempDir
    Path tempDir;

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    private Path dir;
    private Path checkpoints;
    private List<AuditEvent> events;

    private void writeChain(String instance, String... instants) throws IOException {
        dir = tempDir.resolve("seg");
        Instant[] now = new Instant[1];
        Clock mutable = new Clock() {
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
                return now[0];
            }
        };
        events = new ArrayList<>();
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            AuditRecorder recorder = new AuditRecorder(sink, mutable, instance);
            for (String instant : instants) {
                now[0] = Instant.parse(instant);
                events.add(recorder.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                        Set.of("a:ANSWERED"), Set.of(), "corr"));
            }
        }
        checkpoints = tempDir.resolve("checkpoints.jsonl");
        Files.deleteIfExists(checkpoints);
        Files.createFile(checkpoints);
    }

    private void purgeInto(Path checkpointFile) throws IOException {
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(checkpointFile, tempDir.resolve("unused"))) {
            new AuditRetention(dir, Period.ofMonths(6), sink, NOW).purge();
        }
    }

    private int cli(Path audit, Path cp, ByteArrayOutputStream out) {
        String[] args = cp == null ? new String[] {audit.toString()}
                : new String[] {audit.toString(), "--checkpoints", cp.toString()};
        return AuditChainVerifierCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void directoryModeReadsSegmentsInDateOrderAsOneIntactChain() throws IOException {
        writeChain("w1", "2026-09-08T01:00:00Z", "2026-09-09T01:00:00Z", "2026-09-10T01:00:00Z");

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(dir);

        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isFalse();
        assertThat(report.writers()).singleElement().satisfies(w -> assertThat(w.sequenceCount()).isEqualTo(3));
        assertThat(cli(dir, null, new ByteArrayOutputStream())).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
    }

    @Test
    void aChainStartingRightAfterAnAnchorIsIntactNamingTheAnchor() throws IOException {
        writeChain("w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-09-09T01:00:00Z",
                "2026-09-10T01:00:00Z");
        purgeInto(checkpoints);
        assertThat(dir.toFile().list()).containsExactlyInAnyOrder("audit-2026-09-09.jsonl",
                "audit-2026-09-10.jsonl");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = cli(dir, checkpoints, out);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("retention anchor at sequence 2")
                .contains(events.get(1).eventHash());
    }

    @Test
    void theSameChainWithoutTheAnchorIsReportedAsANonGenesisStart() throws IOException {
        writeChain("w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-09-09T01:00:00Z");
        purgeInto(tempDir.resolve("anchors-we-do-not-supply.jsonl"));

        assertThat(cli(dir, null, new ByteArrayOutputStream())).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(cli(dir, checkpoints, new ByteArrayOutputStream()))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }

    @Test
    void anAnchorForADifferentHashOrSequenceDoesNotExplainTheStart() throws IOException {
        writeChain("w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-09-09T01:00:00Z");
        purgeInto(tempDir.resolve("real-anchors.jsonl"));
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(checkpoints, tempDir.resolve("unused"))) {
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, events.get(0).instanceId(), 2, "f".repeat(64),
                    Instant.parse("2026-09-10T00:00:00Z")));
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, events.get(0).instanceId(), 1,
                    events.get(1).eventHash(), Instant.parse("2026-09-10T00:00:00Z")));
        }

        assertThat(cli(dir, checkpoints, new ByteArrayOutputStream()))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }

    @Test
    void aSegmentDeletedByHandWithoutAnAnchorIsStillABreak() throws IOException {
        writeChain("w1", "2026-09-07T01:00:00Z", "2026-09-08T01:00:00Z", "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve("audit-2026-09-07.jsonl"));

        assertThat(cli(dir, checkpoints, new ByteArrayOutputStream()))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);

        // a gap in the middle is a break too
        writeChainFresh();
    }

    private void writeChainFresh() throws IOException {
        Path again = tempDir.resolve("seg2");
        Files.createDirectories(again);
        for (String name : dir.toFile().list()) {
            Files.copy(dir.resolve(name), again.resolve(name));
        }
        Files.delete(again.resolve("audit-2026-09-08.jsonl"));
        // remaining: only 09-09 whose chain starts at sequence 3 with a non-genesis previousHash
        assertThat(cli(again, checkpoints, new ByteArrayOutputStream()))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }

    @Test
    void aWriterWhoseOnlySegmentWasDeletedByHandIsMissingWhenCheckpointed() throws IOException {
        writeChain("w1", "2026-09-07T01:00:00Z", "2026-09-07T02:00:00Z");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(checkpoints, tempDir.resolve("unused"))) {
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.PERIODIC, events.get(0).instanceId(), 2, events.get(1).eventHash(),
                    Instant.parse("2026-09-07T03:00:00Z")));
        }
        Files.delete(dir.resolve("audit-2026-09-07.jsonl"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(dir, checkpoints, out)).isEqualTo(AuditChainVerifierCli.EXIT_CHECKPOINT_MISMATCH);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("MISSING_WRITER");
    }

    @Test
    void aWriterWhollyPurgedUnderRetentionIsNotReportedMissing() throws IOException {
        writeChain("w1", "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(checkpoints, tempDir.resolve("unused"))) {
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.PERIODIC, events.get(0).instanceId(), 2, events.get(1).eventHash(),
                    Instant.parse("2026-03-01T03:00:00Z")));
        }
        purgeInto(checkpoints);

        assertThat(cli(dir, checkpoints, new ByteArrayOutputStream())).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
    }
}
