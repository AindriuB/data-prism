package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A process that dies mid-write leaves an unterminated fragment; the restarted writer must not
 * append its first record onto that same physical line, or the verifier sees an over-count line and
 * reports a crash as tampering. Everything here goes through the real sinks and the real recorder,
 * and reads the result back through the verifier and the CLI.
 */
class InterruptedAppendRestartTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String EXTERNAL_ID = "ext-correlation-12345";
    private static final char SEP = '\u001f';

    @TempDir
    Path tempDir;

    /** Which production open path is under test. */
    enum Kind { FILE, SEGMENTED }

    // -- helpers -----------------------------------------------------------

    private interface Body {
        void run(AuditRecorder recorder);
    }

    /** One boot: opens the sink for {@code kind} on {@code target}, runs {@code body}, closes it. */
    private static void boot(Kind kind, Path target, Body body) throws IOException {
        AuditSink sink = kind == Kind.FILE ? new FileAuditSink(target) : new SegmentedFileAuditSink(target);
        try {
            body.run(new AuditRecorder(sink, FIXED, "restart-writer"));
        } finally {
            ((java.io.Closeable) sink).close();
        }
    }

    private static AuditEntry entry() {
        return new AuditEntry("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1", Map.of(), "", "", EXTERNAL_ID);
    }

    /** The file the sink writes: the target itself, or the one day's segment inside it. */
    private static Path logFile(Kind kind, Path target) {
        return kind == Kind.FILE ? target : target.resolve(SegmentedFileAuditSink.segmentName(LocalDate.of(2026, 1, 1)));
    }

    private static Path target(Kind kind, Path dir) {
        return kind == Kind.FILE ? dir.resolve("audit.log") : dir.resolve("segments");
    }

    /** Index of the first character of field {@code k} (0-based) in a serialized line. */
    private static int fieldStart(String line, int k) {
        int seen = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == SEP && ++seen == k) {
                return i + 1;
            }
        }
        throw new AssertionError("line has fewer than " + k + " separators");
    }

    private static int cli(Path path, ByteArrayOutputStream out) {
        return AuditChainVerifierCli.run(new String[] {path.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    /** Writes three v3 records, then cuts the file to its first two lines plus {@code cutIn} of the third. */
    private byte[] tearThirdRecord(Kind kind, Path target, java.util.function.ToIntFunction<String> cutIn)
            throws IOException {
        boot(kind, target, r -> {
            r.record(entry());
            r.record(entry());
            r.record(entry());
        });
        Path file = logFile(kind, target);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        assertThat(lines).hasSize(3);
        String third = lines[2];
        int keep = cutIn.applyAsInt(third);
        String torn = lines[0] + "\n" + lines[1] + "\n" + third.substring(0, keep);
        byte[] bytes = torn.getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        return bytes;
    }

    private void assertRestartIsAnInterruptedWriteNotABreak(Kind kind, Path target, byte[] tornBytes)
            throws IOException {
        boot(kind, target, r -> r.record(entry()));

        Path file = logFile(kind, target);
        byte[] after = Files.readAllBytes(file);
        assertThat(Arrays.copyOf(after, tornBytes.length)).as("existing bytes are never rewritten")
                .isEqualTo(tornBytes);
        assertThat(after[tornBytes.length]).isEqualTo((byte) '\r');
        assertThat(after[tornBytes.length + 1]).isEqualTo((byte) '\n');

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(target);
        assertThat(report.anomalies()).extracting(AuditChainVerifier.StructuralAnomaly::type)
                .containsExactly(AuditChainVerifier.AnomalyType.INTERRUPTED_WRITE_FRAGMENT);
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.tail()).isEmpty();
        assertThat(report.writers()).hasSize(2);
        assertThat(report.writers()).allSatisfy(w -> assertThat(w.broken()).isFalse());
        AuditChainVerifier.WriterResult resumed = report.writers().get(1);
        assertThat(resumed.sequenceCount()).isEqualTo(1);
        assertThat(resumed.firstSequence()).isEqualTo(1);
        assertThat(resumed.nonGenesisStart()).isEmpty();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(target, out)).isEqualTo(AuditChainVerifierCli.EXIT_STRUCTURAL_ANOMALY).isEqualTo(4);
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("INTERRUPTED WRITE, not tampering")
                .doesNotContain("CHAIN BREAK").doesNotContain("possible tampering")
                .doesNotContain("tampering is possible");
    }

    // -- the review's scenario, and every tear point -----------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void tornInsideFields20To22IsAnInterruptedWriteAfterARestart(Kind kind) throws IOException {
        Path target = target(kind, tempDir);
        byte[] torn = tearThirdRecord(kind, target, line -> fieldStart(line, 21) + 3);
        assertRestartIsAnInterruptedWriteNotABreak(kind, target, torn);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void tornInsideTheEventHashFieldIsAnInterruptedWriteAfterARestart(Kind kind) throws IOException {
        Path target = target(kind, tempDir);
        byte[] torn = tearThirdRecord(kind, target, line -> fieldStart(line, 19) + 10);
        assertRestartIsAnInterruptedWriteNotABreak(kind, target, torn);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void tornAtTheApproverFieldIsAnInterruptedWriteAfterARestart(Kind kind) throws IOException {
        Path target = target(kind, tempDir);
        byte[] torn = tearThirdRecord(kind, target, line -> fieldStart(line, 23));
        assertRestartIsAnInterruptedWriteNotABreak(kind, target, torn);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void tornInsideANonEmptyExternalCorrelationIdIsAnInterruptedWriteAfterARestart(Kind kind)
            throws IOException {
        Path target = target(kind, tempDir);
        byte[] torn = tearThirdRecord(kind, target, line -> fieldStart(line, 24) + 4);
        assertRestartIsAnInterruptedWriteNotABreak(kind, target, torn);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void completeExceptForTheNewlineIsAnInterruptedWriteAfterARestart(Kind kind) throws IOException {
        Path target = target(kind, tempDir);
        byte[] torn = tearThirdRecord(kind, target, String::length);
        assertRestartIsAnInterruptedWriteNotABreak(kind, target, torn);
    }

    // -- writer behaviour --------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aFileThatIsAbsentEmptyOrNewlineTerminatedIsLeftByteIdentical(Kind kind) throws IOException {
        Path target = target(kind, tempDir);
        boot(kind, target, r -> { });
        Path file = logFile(kind, target);
        // absent: opening and closing without a record creates nothing to terminate
        assertThat(Files.notExists(file) || Files.size(file) == 0).isTrue();

        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);
        boot(kind, target, r -> { });
        assertThat(Files.readAllBytes(file)).isEmpty();

        byte[] terminated = "a complete line\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, terminated);
        boot(kind, target, r -> { });
        assertThat(Files.readAllBytes(file)).isEqualTo(terminated);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anUnreadableTailFailsTheOpenClosedAndAppendsNothing(Kind kind) throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path target = target(kind, tempDir);
        Path file = logFile(kind, target);
        Files.createDirectories(file.getParent());
        byte[] torn = "unterminated".getBytes(StandardCharsets.UTF_8);
        Files.write(file, torn);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"));
        assumeTrue(!Files.isReadable(file));
        try {
            assertThatThrownBy(() -> boot(kind, target, r -> r.record(entry())))
                    .satisfiesAnyOf(
                            e -> assertThat(e).isInstanceOf(FileAuditSink.OpenFailedException.class),
                            e -> assertThat(e.getCause()).isInstanceOf(FileAuditSink.OpenFailedException.class));
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(torn);
    }

    @Test
    void aFailingTerminatorWriteFailsTheSegmentOpenWithOpenFailedAndAppendsNothing() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("segments"));
        Path file = dir.resolve(SegmentedFileAuditSink.segmentName(LocalDate.of(2026, 1, 1)));
        byte[] torn = "unterminated".getBytes(StandardCharsets.UTF_8);
        Files.write(file, torn);
        SegmentedFileAuditSink.ChannelOpener closedOnArrival = segment -> {
            FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            channel.close();
            return channel;
        };
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, closedOnArrival)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "restart-writer");
            assertThatThrownBy(() -> recorder.record(entry()))
                    .isInstanceOf(FileAuditSink.OpenFailedException.class)
                    .extracting(e -> ((FileAuditSink.OpenFailedException) e).code())
                    .isEqualTo("AUDIT_SINK_OPEN_FAILED");
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(torn);
    }

    // -- the rule the fix must not weaken ----------------------------------

    private List<String> cleanLines(int version, int count) throws IOException {
        Path scratch = tempDir.resolve("scratch-" + version + "-" + count + ".log");
        boot(Kind.FILE, scratch, r -> {
            for (int i = 0; i < count; i++) {
                r.record(entry());
            }
        });
        return new ArrayList<>(Arrays.asList(Files.readString(scratch, StandardCharsets.UTF_8).split("\n")));
    }

    private static List<String> types(AuditChainVerifier.VerificationReport report) {
        return report.anomalies().stream().map(a -> a.type().name()).toList();
    }

    @Test
    void anUnhashedFieldAppendedToAV3LineMidFileAndOnTheLastLineIsStillABreak() throws IOException {
        for (int target : new int[] {0, 2}) {
            List<String> lines = cleanLines(3, 3);
            lines.set(target, lines.get(target) + SEP + "forged");
            Path file = tempDir.resolve("v3-extra-" + target + ".log");
            Files.writeString(file, String.join("\n", lines) + "\n");
            AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);
            assertThat(types(report)).as("line %d", target).containsExactly("FIELD_COUNT_MISMATCH");
            assertThat(report.hasBreak()).isTrue();
            assertThat(cli(file, new ByteArrayOutputStream())).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        }
    }

    @Test
    void aV3LineRelabelledV1OrV2IsStillABreak() throws IOException {
        for (String label : new String[] {"1", "2"}) {
            List<String> lines = cleanLines(3, 3);
            String line = lines.get(1);
            int start = fieldStart(line, 20);
            lines.set(1, line.substring(0, start) + label + line.substring(start + 1));
            Path file = tempDir.resolve("relabel-" + label + ".log");
            Files.writeString(file, String.join("\n", lines) + "\n");
            AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);
            assertThat(report.hasBreak()).as("relabelled v%s", label).isTrue();
            assertThat(cli(file, new ByteArrayOutputStream())).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        }
    }

    // -- the CR marker's tamper analysis -----------------------------------

    @Test
    void aCarriageReturnAddedToAMidChainRecordIsABreak() throws IOException {
        List<String> lines = cleanLines(3, 3);
        lines.set(1, lines.get(1) + "\r");
        Path file = tempDir.resolve("cr-mid.log");
        Files.writeString(file, String.join("\n", lines) + "\n");

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);

        assertThat(report.hasBreak()).isTrue();
        assertThat(cli(file, new ByteArrayOutputStream())).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }

    @Test
    void aCarriageReturnAddedToTheLastRecordIsTailTruncationCaughtByCheckpoints() throws IOException {
        Path audit = tempDir.resolve("audit.log");
        Path checkpoints = tempDir.resolve("cp.jsonl");
        try (FileAuditSink sink = new FileAuditSink(audit);
                FileAuditCheckpointSink cpSink = new FileAuditCheckpointSink(checkpoints, audit)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "restart-writer", cpSink);
            recorder.record(entry());
            recorder.record(entry());
            recorder.record(entry());
            recorder.close();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(Files.readString(audit).split("\n")));
        lines.set(2, lines.get(2) + "\r");
        Files.writeString(audit, String.join("\n", lines) + "\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = AuditChainVerifierCli.run(new String[] {audit.toString(), "--checkpoints", checkpoints.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_CHECKPOINT_MISMATCH).isEqualTo(5);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("TRUNCATED_BEFORE_CHECKPOINT");
    }

    // -- legacy logs (decision B1) -----------------------------------------

    @Test
    void aLegacyFragmentFusedWithTheNextRecordIsStillABreakAndNamesTheLegacyCause() throws IOException {
        List<String> lines = cleanLines(3, 3);
        String fragment = lines.get(2).substring(0, fieldStart(lines.get(2), 21) + 3);
        Path restarted = tempDir.resolve("restarted.log");
        boot(Kind.FILE, restarted, r -> r.record(entry()));
        String firstOfRestart = Files.readString(restarted).split("\n")[0];
        Path file = tempDir.resolve("legacy.log");
        Files.writeString(file, lines.get(0) + "\n" + lines.get(1) + "\n" + fragment + firstOfRestart + "\n");

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);

        assertThat(types(report)).containsExactly("FIELD_COUNT_MISMATCH");
        assertThat(report.hasBreak()).isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(file, out)).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("FIELD_COUNT_MISMATCH")
                .contains("before 0.5.0").contains("interrupted write followed by a restart");
    }

    // -- retention ---------------------------------------------------------

    @Test
    void aSegmentWhoseOnlyAnomalyIsAWriterTerminatedFragmentIsStillPurgeable() throws IOException {
        Path dir = tempDir.resolve("segments");
        Instant day1 = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock1 = Clock.fixed(day1, ZoneOffset.UTC);
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            AuditRecorder recorder = new AuditRecorder(sink, clock1, "retained-writer");
            recorder.record(entry());
            recorder.record(entry());
        }
        Path segment = dir.resolve(SegmentedFileAuditSink.segmentName(LocalDate.of(2026, 1, 1)));
        Files.writeString(segment, Files.readString(segment) + "torn-fragment-without-newline");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            new AuditRecorder(sink, clock1, "retained-writer").record(entry());
        }
        assertThat(Files.readString(segment)).contains("torn-fragment-without-newline\r\n");

        // a later day's segment so the first one is the oldest and expired
        Clock later = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir);
                FileAuditCheckpointSink cp = new FileAuditCheckpointSink(tempDir.resolve("cp.jsonl"),
                        tempDir.resolve("unused.log"))) {
            new AuditRecorder(sink, later, "later-writer").record(entry());
            AuditRetention retention = new AuditRetention(dir, java.time.Period.ofMonths(6), cp, later);
            retention.purge();
        }

        assertThat(Files.notExists(segment)).isTrue();
    }
}
