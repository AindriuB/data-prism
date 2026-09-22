package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI is the only surface an operator or a compliance reader sees; a
 * reader has no source access, so every distinct outcome's wording has to
 * stand on its own. Asserted here on the CLI's actual stdout strings and
 * exit codes, never in prose.
 */
class AuditChainVerifierCliTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private static AuditEvent write(AuditRecorder recorder) {
        return recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
    }

    private static int run(Path path, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        String[] args = path == null ? new String[0] : new String[] {path.toString()};
        return AuditChainVerifierCli.run(args, out, err);
    }

    @Test
    void intactChainExitsZeroAndPrintsTheLimitationOnACleanRun() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
        }

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = run(path, outBytes, new ByteArrayOutputStream());
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(out).contains("intact");
        assertThat(out).contains("cannot detect truncation");
        assertThat(out).doesNotContain("tamper-proof");
        assertThat(out).doesNotContain("immutable");
    }

    @Test
    void unreadableInputExitsWithItsOwnCode() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = run(tempDir.resolve("does-not-exist.log"), outBytes, errBytes);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_UNREADABLE_INPUT);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("UNREADABLE INPUT");
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("cannot detect truncation");
    }

    @Test
    void helpDocumentsEveryExitCodeAndTheLimitation() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = AuditChainVerifierCli.run(new String[] {"--help"},
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(out).contains("0  intact");
        assertThat(out).contains("1  unreadable input");
        assertThat(out).contains("2  break detected");
        assertThat(out).contains("3  possibly-in-flight tail");
        assertThat(out).contains("4  structural non-tampering anomaly");
        assertThat(out).contains("never returned together with exit code 2");
        assertThat(out).contains("cannot detect truncation");
    }

    @Test
    void aBreakExitsWithItsOwnCodeAndDistinctWordingFromTheFourOrdinaryOutcomes() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
        }
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        AuditEvent second = AuditRecordFormat.parse(lines.get(1));
        AuditEvent tampered = new AuditEvent(second.eventId(), second.timestamp(), second.principalId(),
                second.clientId(), second.tool(), second.entityType(), second.subjectPseudonym(),
                second.parameterFingerprint(), second.privacyProfile(), second.scopeId(), second.purpose(),
                second.caseId(), second.policyDecision(), second.sourceSystems(), second.rejectedArguments(),
                second.correlationId(), second.instanceId(), second.sequence(), second.previousHash(),
                "f".repeat(64));
        lines.set(1, AuditRecordFormat.serialize(tampered));
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = run(path, outBytes, new ByteArrayOutputStream());
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(out).contains("CHAIN BREAK");
        assertThat(out).contains("edited or deleted after being written");
        assertThat(out).doesNotContain("INTERRUPTED WRITE");
        assertThat(out).doesNotContain("SINK-CONTRACT VIOLATION");
        assertThat(out).doesNotContain("POSSIBLY IN FLIGHT");
        assertThat(out).contains("cannot detect truncation");
    }

    @Test
    void tornTrailingRecordReadsAsPossiblyInFlightNotABreakOnTheCli() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
        }
        try (FileChannel real = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                ShortWriteChannel faulty = new ShortWriteChannel(real, 10)) {
            FileAuditSink torn = new FileAuditSink(path, faulty);
            AuditEvent unfinished = eventWithComputedHash("event-torn", "instance-1", 2, "irrelevant-prior");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> torn.record(unfinished))
                    .isInstanceOf(java.io.UncheckedIOException.class);
        }

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = run(path, outBytes, new ByteArrayOutputStream());
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_POSSIBLY_IN_FLIGHT);
        assertThat(out).contains("POSSIBLY IN FLIGHT");
        assertThat(out).contains("no terminating newline");
        assertThat(out).doesNotContain("CHAIN BREAK");
        assertThat(out).contains("cannot detect truncation");
    }

    @Test
    void midFileFragmentReadsAsInterruptedWriteNotABreakOnTheCli() throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent before;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            before = write(recorder);
        }
        try (FileChannel real = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                ShortWriteChannel faulty = new ShortWriteChannel(real, 60)) {
            FileAuditSink torn = new FileAuditSink(path, faulty);
            AuditEvent unfinished = eventWithComputedHash("event-torn", "instance-1", 2, before.eventHash());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> torn.record(unfinished))
                    .isInstanceOf(java.io.UncheckedIOException.class);
        }
        try (FileAuditSink restarted = new FileAuditSink(path)) {
            write(new AuditRecorder(restarted, FIXED, "instance-2"));
        }

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = run(path, outBytes, new ByteArrayOutputStream());
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_STRUCTURAL_ANOMALY);
        assertThat(out).contains("INTERRUPTED WRITE, not tampering");
        assertThat(out).contains("field count");
        assertThat(out).doesNotContain("CHAIN BREAK");
        assertThat(out).doesNotContain("SINK-CONTRACT VIOLATION");
        assertThat(out).doesNotContain("POSSIBLY IN FLIGHT");
        assertThat(out).contains("cannot detect truncation");
    }

    @Test
    void duplicateSequenceReadsAsASinkContractViolationNotABreakOnTheCli() throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent first;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            first = write(recorder);
        }
        AuditEvent duplicate = eventWithComputedHash("event-dup", "instance-1", first.sequence(), "f".repeat(64));
        try (FileAuditSink sink = new FileAuditSink(path)) {
            sink.record(duplicate);
        }

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = run(path, outBytes, new ByteArrayOutputStream());
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_STRUCTURAL_ANOMALY);
        assertThat(out).contains("SINK-CONTRACT VIOLATION, not tampering");
        assertThat(out).contains("instance-1");
        assertThat(out).doesNotContain("CHAIN BREAK");
        assertThat(out).doesNotContain("INTERRUPTED WRITE");
        assertThat(out).doesNotContain("POSSIBLY IN FLIGHT");
        assertThat(out).contains("cannot detect truncation");
    }

    private static AuditEvent eventWithComputedHash(String eventId, String instanceId, long sequence,
                                                       String previousHash) {
        AuditEvent draft = new AuditEvent(eventId, FIXED.instant(), "investigator-1", "client-1",
                "get_entity_context", "CUSTOMER", "pseudo-1", "fp-1", "DEFAULT", "scope-1", "investigation",
                "CASE-1", "ALLOW", Set.of("customer-api:ANSWERED"), Set.of(), "corr-1", instanceId, sequence,
                previousHash, "placeholder");
        String hash = AuditEventHash.compute(draft);
        return new AuditEvent(eventId, FIXED.instant(), "investigator-1", "client-1", "get_entity_context",
                "CUSTOMER", "pseudo-1", "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1", instanceId, sequence, previousHash, hash);
    }

    /** A minimal short-write-then-throw {@link FileChannel}, producing a genuine torn fragment on disk. */
    private static final class ShortWriteChannel extends FileChannel {

        private final FileChannel delegate;
        private final int bytesBeforeFailure;

        ShortWriteChannel(FileChannel delegate, int bytesBeforeFailure) {
            this.delegate = delegate;
            this.bytesBeforeFailure = bytesBeforeFailure;
        }

        @Override
        public int write(java.nio.ByteBuffer src) throws IOException {
            java.nio.ByteBuffer limited = src.duplicate();
            limited.limit(Math.min(src.limit(), src.position() + bytesBeforeFailure));
            int written = delegate.write(limited);
            src.position(src.position() + written);
            throw new IOException("simulated short write failure");
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }

        @Override
        public int read(java.nio.ByteBuffer dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long read(java.nio.ByteBuffer[] dsts, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long write(java.nio.ByteBuffer[] srcs, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel position(long newPosition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(java.nio.channels.ReadableByteChannel src, long position, long count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(java.nio.ByteBuffer dst, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(java.nio.ByteBuffer src, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.nio.channels.FileLock lock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Runs the CLI as a real, separate process — {@code java -cp <this test JVM's own classpath>
     * ...AuditChainVerifierCli <path>} — against a file produced by an actual {@link AuditRecorder}
     * + {@link FileAuditSink} run, not against an in-process call or a hand-written fixture.
     */
    @Test
    void runsAsARealProcessAgainstARealFile() throws IOException, InterruptedException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
            write(recorder);
        }

        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(javaBin, "-cp", classpath,
                "io.github.aindriub.dataprism.audit.AuditChainVerifierCli", path.toString())
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(output).contains("sequence count: 3");
        assertThat(output).contains("intact");
        assertThat(output).contains("cannot detect truncation");
    }
}
