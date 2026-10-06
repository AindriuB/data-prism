package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Record version 2: per-field dispositions and approval identity, with version 1 files still verifying. */
class AuditRecordVersionTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private static AuditEntry entry(Map<String, String> dispositions) {
        return new AuditEntry("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("crm:ANSWERED"), Set.of(), "corr-1", dispositions, "appr-1", "approver-2");
    }

    private static int verify(Path path) {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return AuditChainVerifierCli.run(new String[] {path.toString()}, sink, sink);
    }

    @Test
    void twentyArgumentConstructorYieldsVersionOneWithEmptyNewFields() {
        AuditEvent e = new AuditEvent("id", FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca",
                "ALLOW", Set.of(), Set.of(), "co", "inst", 1, "prev", "hash");
        assertThat(e.recordVersion()).isEqualTo(1);
        assertThat(e.fieldDispositions()).isEmpty();
        assertThat(e.approvalId()).isEmpty();
        assertThat(e.approverId()).isEmpty();
    }

    @Test
    void dispositionValueThatIsNotAPrivacyActionOrRefusedIsRejected() {
        assertThatThrownBy(() -> new AuditEvent("id", FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc",
                "pu", "ca", "ALLOW", Set.of(), Set.of(), "co", "inst", 1, "prev", "hash", 2,
                Map.of("crm:/email", "jane@example.com"), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        AuditEvent ok = new AuditEvent("id", FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc",
                "pu", "ca", "ALLOW", Set.of(), Set.of(), "co", "inst", 1, "prev", "hash", 2,
                Map.of("crm:/b", "REFUSED", "crm:/a", "REDACT"), "", "");
        assertThat(ok.fieldDispositions().keySet()).containsExactly("crm:/a", "crm:/b");
    }

    @Test
    void recorderWritesVersionTwoAndItRoundTrips() {
        AuditRecorder recorder = new AuditRecorder(event -> { }, FIXED, "w");
        AuditEvent event = recorder.record(entry(Map.of("crm:/contacts/*/email", "REDACT")));
        assertThat(event.recordVersion()).isEqualTo(2);
        assertThat(AuditRecordFormat.parse(AuditRecordFormat.serialize(event))).isEqualTo(event);
    }

    @Test
    void fourteenArgumentRecordDelegatesWithEmptyDispositions() {
        AuditRecorder recorder = new AuditRecorder(event -> { }, FIXED, "w");
        AuditEvent event = recorder.record("p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca", "ALLOW",
                Set.of(), Set.of(), "co");
        assertThat(event.fieldDispositions()).isEmpty();
        assertThat(event.approvalId()).isEmpty();
        assertThat(AuditEventHash.compute(event)).isEqualTo(event.eventHash());
    }

    @Test
    void invalidDispositionDoesNotConsumeASequenceNumber() {
        AuditRecorder recorder = new AuditRecorder(event -> { }, FIXED, "w");
        assertThatThrownBy(() -> recorder.record(entry(Map.of("crm:/x", "bogus"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(recorder.record(entry(Map.of())).sequence()).isEqualTo(1);
    }

    @Test
    void versionOneHashCoversExactlyTheNineteenOriginalFields() {
        AuditEvent v1 = new AuditEvent("id", FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca",
                "ALLOW", Set.of("a"), Set.of(), "co", "inst", 1, "prev", "x");
        assertThat(AuditEventHash.compute(v1)).isEqualTo(AuditEventHash.compute("id", FIXED.instant(), "inst", 1,
                "p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca", "ALLOW", "co", Set.of("a"), Set.of(),
                "prev"));
    }

    @Test
    void lineWithoutRecordVersionParsesAsVersionOne() throws IOException {
        String line = Files.readAllLines(fixture()).get(0);
        assertThat(AuditRecordFormat.parse(line).recordVersion()).isEqualTo(1);
    }

    @Test
    void committedVersionOneChainVerifiesIntact() {
        assertThat(verify(fixture())).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
    }

    @Test
    void versionTwoChainWithEditedDispositionsIsABreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "w");
            recorder.record(entry(Map.of("crm:/email", "REDACT")));
            recorder.record(entry(Map.of("crm:/email", "REDACT")));
        }
        assertThat(verify(path)).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);

        String content = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(content).contains("crm:/email=REDACT");
        Files.writeString(path, content.replaceFirst("crm:/email=REDACT", "crm:/email=HASH"),
                StandardCharsets.UTF_8);
        assertThat(verify(path)).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }

    private Path fixture() {
        try {
            Path copy = tempDir.resolve("v1-chain.jsonl");
            try (var in = getClass().getResourceAsStream("/audit/v1-chain.jsonl")) {
                Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            return copy;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
