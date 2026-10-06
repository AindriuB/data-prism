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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditCheckpointTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String GENESIS = "0".repeat(64);

    @TempDir
    Path tempDir;

    private static AuditEvent write(AuditRecorder recorder) {
        return recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
    }

    private static final class Capture implements AuditCheckpointSink {
        final List<AuditCheckpoint> seen = new ArrayList<>();
        boolean fail;

        @Override
        public void record(AuditCheckpoint checkpoint) {
            if (fail) {
                throw new IllegalStateException("checkpoint store down");
            }
            seen.add(checkpoint);
        }
    }

    private int run(ByteArrayOutputStream out, String... args) {
        return AuditChainVerifierCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    private static void dropLastLines(Path file, int n) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        Files.write(file, lines.subList(0, lines.size() - n));
    }

    @Test
    void checkpointJsonLineRoundTrips() {
        AuditCheckpoint cp = new AuditCheckpoint(AuditCheckpoint.Kind.PERIODIC, "w\"1/é", 7, "abc",
                Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(AuditCheckpoint.fromJsonLine(cp.toJsonLine())).isEqualTo(cp);
        assertThat(cp.toJsonLine()).doesNotContain("\n");
    }

    @Test
    void bootCheckpointWrittenAtConstructionWithGenesis() {
        Capture cps = new Capture();
        AuditRecorder r = new AuditRecorder(e -> { }, FIXED, "w", cps);

        assertThat(cps.seen).hasSize(1);
        AuditCheckpoint boot = cps.seen.get(0);
        assertThat(boot.kind()).isEqualTo(AuditCheckpoint.Kind.BOOT);
        assertThat(boot.sequence()).isZero();
        assertThat(boot.headHash()).isEqualTo(GENESIS);
        assertThat(boot.instanceId()).isEqualTo(r.instanceId());
    }

    @Test
    void constructorThrowsWhenBootCheckpointFails() {
        Capture cps = new Capture();
        cps.fail = true;
        assertThatThrownBy(() -> new AuditRecorder(e -> { }, FIXED, "w", cps))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void periodicAndShutdownCheckpointsCarryTheCurrentHead() {
        Capture cps = new Capture();
        AuditRecorder r = new AuditRecorder(e -> { }, FIXED, "w", cps);
        AuditEvent e1 = write(r);
        r.checkpoint();
        AuditEvent e2 = write(r);
        r.close();

        assertThat(cps.seen).extracting(AuditCheckpoint::kind).containsExactly(AuditCheckpoint.Kind.BOOT,
                AuditCheckpoint.Kind.PERIODIC, AuditCheckpoint.Kind.SHUTDOWN);
        assertThat(cps.seen.get(1).sequence()).isEqualTo(1);
        assertThat(cps.seen.get(1).headHash()).isEqualTo(e1.eventHash());
        assertThat(cps.seen.get(2).sequence()).isEqualTo(2);
        assertThat(cps.seen.get(2).headHash()).isEqualTo(e2.eventHash());
    }

    @Test
    void existingConstructorWritesNoCheckpointsAndCloseIsANoOp() {
        AuditRecorder r = new AuditRecorder(e -> { }, FIXED, "w");
        write(r);
        r.close();
    }

    @Test
    void failedCheckpointRefusesRecordsWithoutAdvancingTheChainUntilACheckpointSucceeds() {
        Capture cps = new Capture();
        List<AuditEvent> written = new ArrayList<>();
        AuditRecorder r = new AuditRecorder(written::add, FIXED, "w", cps);
        AuditEvent first = write(r);

        cps.fail = true;
        assertThatThrownBy(r::checkpoint).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> write(r)).isInstanceOf(AuditCheckpointUnavailableException.class);
        assertThatThrownBy(() -> write(r)).isInstanceOf(AuditCheckpointUnavailableException.class);
        assertThat(written).hasSize(1);

        cps.fail = false;
        r.checkpoint();
        AuditEvent second = write(r);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(second.previousHash()).isEqualTo(first.eventHash());
    }

    @Test
    void fileCheckpointSinkRefusesTheAuditFilePath() {
        Path audit = tempDir.resolve("audit.log");
        assertThatThrownBy(() -> new FileAuditCheckpointSink(audit, audit))
                .isInstanceOf(FileAuditCheckpointSink.CheckpointSinkException.class)
                .hasMessageContaining("AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
        assertThatThrownBy(() -> new FileAuditCheckpointSink(tempDir.resolve("x/../audit.log"), audit))
                .hasMessageContaining("AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
    }

    @Test
    void fileCheckpointSinkAppendsOneJsonLinePerCheckpoint() throws IOException {
        Path cpFile = tempDir.resolve("cp.jsonl");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(cpFile, tempDir.resolve("audit.log"))) {
            AuditRecorder r = new AuditRecorder(e -> { }, FIXED, "w", sink);
            write(r);
            r.close();
        }
        List<String> lines = Files.readAllLines(cpFile);
        assertThat(lines).hasSize(2);
        assertThat(AuditCheckpoint.fromJsonLine(lines.get(1)).kind()).isEqualTo(AuditCheckpoint.Kind.SHUTDOWN);
    }

    /** Runs one boot with three records, checkpointing at the end; returns the audit and checkpoint files. */
    private Path[] oneBoot(String name, int records) throws IOException {
        Path audit = tempDir.resolve(name + "-audit.log");
        Path cp = tempDir.resolve(name + "-cp.jsonl");
        try (FileAuditSink sink = new FileAuditSink(audit);
             FileAuditCheckpointSink cpSink = new FileAuditCheckpointSink(cp, audit)) {
            AuditRecorder r = new AuditRecorder(sink, FIXED, "walkthrough-writer-1", cpSink);
            for (int i = 0; i < records; i++) {
                write(r);
            }
            r.close();
        }
        return new Path[] {audit, cp};
    }

    @Test
    void tailTruncationExitsZeroWithoutCheckpointsAndFiveWithThem() throws IOException {
        Path[] f = oneBoot("tail", 3);
        dropLastLines(f[0], 1);

        ByteArrayOutputStream without = new ByteArrayOutputStream();
        assertThat(run(without, f[0].toString())).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);

        ByteArrayOutputStream with = new ByteArrayOutputStream();
        assertThat(run(with, f[0].toString(), "--checkpoints", f[1].toString()))
                .isEqualTo(AuditChainVerifierCli.EXIT_CHECKPOINT_MISMATCH).isEqualTo(5);
        String out = with.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("TRUNCATED_BEFORE_CHECKPOINT").contains("walkthrough-writer-1/");
    }

    @Test
    void wholeBootDeletionExitsZeroWithoutCheckpointsAndFiveWithThem() throws IOException {
        Path audit = tempDir.resolve("audit.log");
        Path cp = tempDir.resolve("cp.jsonl");
        try (FileAuditSink sink = new FileAuditSink(audit);
             FileAuditCheckpointSink cpSink = new FileAuditCheckpointSink(cp, audit)) {
            AuditRecorder boot1 = new AuditRecorder(sink, FIXED, "walkthrough-writer-1", cpSink);
            write(boot1);
            write(boot1);
            boot1.close();
            AuditRecorder boot2 = new AuditRecorder(sink, FIXED, "walkthrough-writer-1", cpSink);
            write(boot2);
            boot2.close();
        }
        dropLastLines(audit, 1);

        assertThat(run(new ByteArrayOutputStream(), audit.toString())).isEqualTo(0);
        ByteArrayOutputStream with = new ByteArrayOutputStream();
        assertThat(run(with, audit.toString(), "--checkpoints", cp.toString())).isEqualTo(5);
        assertThat(with.toString(StandardCharsets.UTF_8)).contains("MISSING_WRITER");
    }

    @Test
    void bootOnlyCheckpointWithNoRecordsVerifiesIntact() throws IOException {
        Path[] f = oneBoot("empty", 0);
        assertThat(run(new ByteArrayOutputStream(), f[0].toString(), "--checkpoints", f[1].toString())).isEqualTo(0);
    }

    @Test
    void intactFileWithCheckpointsExitsZero() throws IOException {
        Path[] f = oneBoot("ok", 3);
        assertThat(run(new ByteArrayOutputStream(), f[0].toString(), "--checkpoints", f[1].toString())).isEqualTo(0);
    }

    @Test
    void recordHashDifferingFromCheckpointedHeadIsABreak() throws IOException {
        Path[] f = oneBoot("swap", 2);
        List<String> cps = Files.readAllLines(f[1]);
        AuditCheckpoint last = AuditCheckpoint.fromJsonLine(cps.get(cps.size() - 1));
        AuditCheckpoint forged = new AuditCheckpoint(last.kind(), last.instanceId(), last.sequence(),
                "f".repeat(64), last.recordedAt());
        cps.set(cps.size() - 1, forged.toJsonLine());
        Files.write(f[1], cps);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(run(out, f[0].toString(), "--checkpoints", f[1].toString()))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }

    @Test
    void malformedCheckpointFileIsUnreadableInputNotSilentlyIgnored() throws IOException {
        Path[] f = oneBoot("bad", 1);
        Files.writeString(f[1], "not json\n");
        assertThat(run(new ByteArrayOutputStream(), f[0].toString(), "--checkpoints", f[1].toString()))
                .isEqualTo(AuditChainVerifierCli.EXIT_UNREADABLE_INPUT);
    }

    @Test
    void limitationStatementKeepsTheThreeCaveatsAndNoOverclaim() throws IOException {
        Path[] f = oneBoot("lim", 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        run(out, f[0].toString(), "--checkpoints", f[1].toString());
        String text = out.toString(StandardCharsets.UTF_8);

        assertThat(text).contains("after a writer's last checkpoint remain undetectable");
        assertThat(text).contains("cannot also edit the checkpoint file");
        assertThat(text).contains("Neither file resists tampering");
        assertThat(text).doesNotContain("immutable").doesNotContain("compliant");
        assertThat(text).doesNotContain("tamper-proof");
    }
}
