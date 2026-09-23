package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.DisplayName;
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

/**
 * A per-boot audit chain identity, proved end to end at the level an
 * operator actually sees it: a real file, two {@link AuditRecorder}
 * instances built with the identical {@code writer-id} but never both alive
 * at once (one closed before the next opens, exactly like two process
 * lifetimes of the same deployment), and {@link AuditChainVerifierCli}
 * reading the result back. Without a distinct {@code instanceId} per boot,
 * boot B's first record would collide with boot A's already-used sequence 1
 * and {@code previousHash}, reading as DUPLICATE_SEQUENCE followed by a
 * false CHAIN BREAK. Instead, boot B stamps its own {@code instanceId} and
 * starts a new chain at GENESIS, so the restarted file verifies as two
 * independent, intact writers -- while every one of today's genuine anomaly
 * and break detections, exercised again inside this same restarted file,
 * still fires exactly as before, at the same exit code.
 */
class AuditRecorderRestartTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String WRITER_ID = "restart-writer";

    @TempDir
    Path tempDir;

    private static AuditEvent write(AuditRecorder recorder) {
        return recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
    }

    private static int runCli(Path path, ByteArrayOutputStream outBytes) {
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return AuditChainVerifierCli.run(new String[] {path.toString()}, out, err);
    }

    /** One boot's worth of state, so tests below can refer to what it wrote and its instanceId. */
    private record Boot(String instanceId, List<AuditEvent> events) {
    }

    private static Boot boot(Path path, int recordCount) throws IOException {
        List<AuditEvent> events = new ArrayList<>();
        String instanceId;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, WRITER_ID);
            instanceId = recorder.instanceId();
            for (int i = 0; i < recordCount; i++) {
                events.add(write(recorder));
            }
        }
        return new Boot(instanceId, events);
    }

    @Test
    @DisplayName("a restart under the same writer-id verifies as two independent, intact writers")
    void restartedFileWithTwoBootsUnderTheSameWriterIdVerifiesIntact() throws IOException {
        Path path = tempDir.resolve("audit.log");

        Boot bootA = boot(path, 2);
        Boot bootB = boot(path, 3);

        assertThat(bootA.instanceId()).isNotEqualTo(bootB.instanceId());
        assertThat(bootA.instanceId()).startsWith(WRITER_ID + "/");
        assertThat(bootB.instanceId()).startsWith(WRITER_ID + "/");

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.writers()).hasSize(2);
        assertThat(report.anomalies()).isEmpty();
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isFalse();
        assertThat(report.writers()).allSatisfy(w -> assertThat(w.broken()).isFalse());
        assertThat(report.writers()).allSatisfy(w -> assertThat(w.firstSequence()).isEqualTo(1L));

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = runCli(path, outBytes);
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(out).contains("Writer " + bootA.instanceId());
        assertThat(out).contains("Writer " + bootB.instanceId());
    }

    @Test
    @DisplayName("deleting boot A's first record in the restarted file is a genuine break, exit 2")
    void deletingBootAsFirstRecordIsReportedAsABreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        boot(path, 2);
        boot(path, 3);

        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        lines.remove(0);
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = runCli(path, outBytes);
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(out).contains("CHAIN BREAK");

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);
        assertThat(report.hasBreak()).isTrue();
    }

    @Test
    @DisplayName("editing a field of a record in either boot in the restarted file is a break, exit 2")
    void editingAFieldInEitherBootIsReportedAsABreak() throws IOException {
        Path pathA = tempDir.resolve("audit-a.log");
        Boot bootA = boot(pathA, 2);
        boot(pathA, 3);

        List<String> linesA = new ArrayList<>(Files.readAllLines(pathA, StandardCharsets.UTF_8));
        AuditEvent firstOfA = AuditRecordFormat.parse(linesA.get(0));
        linesA.set(0, AuditRecordFormat.serialize(tamperPrincipal(firstOfA)));
        Files.writeString(pathA, String.join("\n", linesA) + "\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream outBytesA = new ByteArrayOutputStream();
        int codeA = runCli(pathA, outBytesA);

        assertThat(codeA).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(outBytesA.toString(StandardCharsets.UTF_8)).contains("CHAIN BREAK");
        assertThat(bootA.instanceId()).isEqualTo(firstOfA.instanceId());

        Path pathB = tempDir.resolve("audit-b.log");
        boot(pathB, 2);
        Boot bootB = boot(pathB, 3);

        List<String> linesB = new ArrayList<>(Files.readAllLines(pathB, StandardCharsets.UTF_8));
        int lastOfBIndex = linesB.size() - 1;
        AuditEvent lastOfB = AuditRecordFormat.parse(linesB.get(lastOfBIndex));
        linesB.set(lastOfBIndex, AuditRecordFormat.serialize(tamperPrincipal(lastOfB)));
        Files.writeString(pathB, String.join("\n", linesB) + "\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream outBytesB = new ByteArrayOutputStream();
        int codeB = runCli(pathB, outBytesB);

        assertThat(codeB).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(outBytesB.toString(StandardCharsets.UTF_8)).contains("CHAIN BREAK");
        assertThat(bootB.instanceId()).isEqualTo(lastOfB.instanceId());
    }

    private static AuditEvent tamperPrincipal(AuditEvent event) {
        return new AuditEvent(event.eventId(), event.timestamp(), "someone-else",
                event.clientId(), event.tool(), event.entityType(), event.subjectPseudonym(),
                event.parameterFingerprint(), event.privacyProfile(), event.scopeId(),
                event.purpose(), event.caseId(), event.policyDecision(), event.sourceSystems(),
                event.rejectedArguments(), event.correlationId(), event.instanceId(),
                event.sequence(), event.previousHash(), event.eventHash());
    }

    @Test
    @DisplayName("a new writer's first record not chaining from GENESIS in the restarted file is a break, exit 2")
    void aFirstRecordNotChainingFromGenesisIsReportedAsABreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        boot(path, 2);
        boot(path, 3);

        AuditEvent forged = eventWithComputedHash("event-forged", "third-writer", 1, "a".repeat(64));
        try (FileAuditSink sink = new FileAuditSink(path)) {
            sink.record(forged);
        }

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = runCli(path, outBytes);
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(out).contains("CHAIN BREAK");
        assertThat(out).contains("third-writer");
    }

    @Test
    @DisplayName("a duplicate sequence within one boot in the restarted file is a structural anomaly, exit 4")
    void aDuplicateSequenceWithinOneBootIsReportedAsAStructuralAnomaly() throws IOException {
        Path path = tempDir.resolve("audit.log");
        boot(path, 2);
        Boot bootB = boot(path, 3);
        AuditEvent firstOfB = bootB.events().get(0);

        AuditEvent duplicate =
                eventWithComputedHash("event-dup", bootB.instanceId(), firstOfB.sequence(), "f".repeat(64));
        try (FileAuditSink sink = new FileAuditSink(path)) {
            sink.record(duplicate);
        }

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        int code = runCli(path, outBytes);
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_STRUCTURAL_ANOMALY);
        assertThat(out).contains("SINK-CONTRACT VIOLATION, not tampering");
        assertThat(out).doesNotContain("CHAIN BREAK");
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
}
