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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Record version 3: the external correlation id, inside the hash, with v1 and v2 still verifying. */
class AuditRecordV3Test {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private static AuditEntry entry(String externalCorrelationId) {
        return new AuditEntry("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("crm:ANSWERED"), Set.of(), "corr-1", Map.of(), "", "", externalCorrelationId);
    }

    private static AuditEvent event(int version, String external) {
        return new AuditEvent("id", FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca", "ALLOW",
                Set.of(), Set.of(), "co", "inst", 1, "prev", "hash", version, Map.of(), "", "", external);
    }

    private static int cli(Path path, ByteArrayOutputStream captured) {
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        return AuditChainVerifierCli.run(new String[] {path.toString()}, sink, sink);
    }

    @Test
    void currentVersionIsThreeAndNullBecomesEmpty() {
        assertThat(AuditEvent.CURRENT_VERSION).isEqualTo(3);
        assertThat(event(3, null).externalCorrelationId()).isEmpty();
        assertThat(event(3, "ext-1").externalCorrelationId()).isEqualTo("ext-1");
    }

    @Test
    void valueOutsideTheCeilingIsRejected() {
        assertThatThrownBy(() -> event(3, "has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(3, "a|b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(3, "x".repeat(257))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(3, "café")).isInstanceOf(IllegalArgumentException.class);
        assertThat(event(3, "x".repeat(256)).externalCorrelationId()).hasSize(256);
        assertThat(event(3, "A.b_c:d/e+f=g-h").externalCorrelationId()).isEqualTo("A.b_c:d/e+f=g-h");
    }

    @Test
    void existingShapesKeepTheirMeaning() {
        AuditEvent v2 = new AuditEvent("id", FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca",
                "ALLOW", Set.of(), Set.of(), "co", "inst", 1, "prev", "hash", 2, Map.of(), "", "");
        assertThat(v2.recordVersion()).isEqualTo(2);
        assertThat(v2.externalCorrelationId()).isEmpty();
        AuditEntry seventeen = new AuditEntry("p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca", "ALLOW",
                Set.of(), Set.of(), "co", Map.of(), "", "");
        assertThat(seventeen.externalCorrelationId()).isEmpty();
    }

    @Test
    void recorderWritesVersionThreeCarryingTheExternalId() {
        List<AuditEvent> seen = new ArrayList<>();
        AuditRecorder recorder = new AuditRecorder(seen::add, FIXED, "w");
        AuditEvent written = recorder.record(entry("ext-corr:1"));
        assertThat(written.recordVersion()).isEqualTo(3);
        assertThat(written.externalCorrelationId()).isEqualTo("ext-corr:1");
        assertThat(AuditEventHash.compute(written)).isEqualTo(written.eventHash());
        assertThat(AuditEventHash.compute(recorder.record(entry(""))))
                .isNotEqualTo(written.eventHash());
    }

    @Test
    void externalIdChangesTheV3Hash() {
        assertThat(AuditEventHash.compute(event(3, "a"))).isNotEqualTo(AuditEventHash.compute(event(3, "b")));
        assertThat(AuditEventHash.compute(event(3, "")))
                .isNotEqualTo(AuditEventHash.compute(event(2, "")));
    }

    @Test
    void formatWritesTwentyFiveFieldsAndRoundTripsEmptyAndNonEmpty() {
        for (String value : new String[] {"", "ext-corr:1/abc"}) {
            AuditEvent original = event(3, value);
            String line = AuditRecordFormat.serialize(original);
            assertThat(line.split("\u001f", -1)).hasSize(25);
            assertThat(AuditRecordFormat.parse(line)).isEqualTo(original);
        }
        String v2Line = AuditRecordFormat.serialize(event(2, ""));
        assertThat(v2Line.split("\u001f", -1)).hasSize(24);
        assertThat(AuditRecordFormat.parse(v2Line)).isEqualTo(event(2, ""));
        assertThatThrownBy(() -> AuditRecordFormat.parse(AuditRecordFormat.serialize(event(3, "a")) + "\u001fx"))
                .isInstanceOf(IllegalArgumentException.class);
        String twentyTwo = String.join("\u001f", java.util.Collections.nCopies(22, "x"));
        assertThatThrownBy(() -> AuditRecordFormat.parse(twentyTwo)).isInstanceOf(IllegalArgumentException.class);
    }

    private static AuditEvent chained(String writer, long seq, String prev, int version, String external) {
        AuditEvent draft = new AuditEvent("id-" + seq, FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc",
                "pu", "ca", "ALLOW", Set.of(), Set.of(), "co", writer, seq, prev, "", version, Map.of(), "", "",
                external);
        return new AuditEvent("id-" + seq, FIXED.instant(), "p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca",
                "ALLOW", Set.of(), Set.of(), "co", writer, seq, prev, AuditEventHash.compute(draft), version,
                Map.of(), "", "", external);
    }

    private static List<AuditEvent> chain(int... versions) {
        List<AuditEvent> events = new ArrayList<>();
        String prev = "0".repeat(64);
        for (int i = 0; i < versions.length; i++) {
            AuditEvent e = chained("w/1", i + 1, prev, versions[i], versions[i] >= 3 ? "ext-" + i : "");
            events.add(e);
            prev = e.eventHash();
        }
        return events;
    }

    private static String lines(List<AuditEvent> events) {
        StringBuilder b = new StringBuilder();
        events.forEach(e -> b.append(AuditRecordFormat.serialize(e)).append('\n'));
        return b.toString();
    }

    @Test
    void fileModeAcceptsV2ThenV3OnOneChain() throws IOException {
        Path file = tempDir.resolve("audit.log");
        Files.writeString(file, lines(chain(2, 2, 3, 3)));
        assertThat(cli(file, new ByteArrayOutputStream())).isZero();
    }

    @Test
    void directoryModeAcceptsV2ThenV3OnOneChain() throws IOException {
        List<AuditEvent> events = chain(2, 2, 3, 3);
        Path dir = Files.createDirectory(tempDir.resolve("segments"));
        Files.writeString(dir.resolve("audit-2026-01-01.log"), lines(events.subList(0, 2)));
        Files.writeString(dir.resolve("audit-2026-01-02.log"), lines(events.subList(2, 4)));
        assertThat(cli(dir, new ByteArrayOutputStream())).isZero();
    }

    @Test
    void fileModeReportsVersionRegressionWithNonZeroExit() throws IOException {
        Path file = tempDir.resolve("audit.log");
        Files.writeString(file, lines(chain(2, 3, 2)));
        assertRegression(AuditChainVerifier.verify(file));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(file, out)).isNotZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("VERSION_REGRESSION");
    }

    @Test
    void directoryModeReportsVersionRegressionWithNonZeroExit() throws IOException {
        List<AuditEvent> events = chain(2, 3, 3, 2);
        Path dir = Files.createDirectory(tempDir.resolve("segments"));
        Files.writeString(dir.resolve("audit-2026-01-01.log"), lines(events.subList(0, 2)));
        Files.writeString(dir.resolve("audit-2026-01-02.log"), lines(events.subList(2, 4)));
        assertRegression(AuditChainVerifier.verify(dir));
        assertThat(cli(dir, new ByteArrayOutputStream())).isNotZero();
    }

    private static void assertRegression(AuditChainVerifier.VerificationReport report) {
        assertThat(report.anomalies()).anyMatch(a -> a.type() == AuditChainVerifier.AnomalyType.VERSION_REGRESSION);
        assertThat(report.hasBreak()).isTrue();
    }

    @Test
    void editingTheExternalIdInOneLineBreaksTheChainAtThatLine() throws IOException {
        List<AuditEvent> events = chain(3, 3, 3);
        String[] ls = lines(events).split("\n");
        String[] parts = ls[1].split("\u001f", -1);
        assertThat(parts).hasSize(25);
        parts[24] = "ext-X";
        ls[1] = String.join("\u001f", parts);
        assertThat(ls[1]).isNotEqualTo(AuditRecordFormat.serialize(events.get(1)));
        Path file = tempDir.resolve("audit.log");
        Files.writeString(file, String.join("\n", ls) + "\n");
        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);
        assertThat(report.hasBreak()).isTrue();
        assertThat(report.writers().get(0).firstBreak().orElseThrow().sequence()).isEqualTo(2);
        assertThat(cli(file, new ByteArrayOutputStream())).isNotZero();
    }

    @Test
    void aV2LineWithAnAppendedFieldIsAFieldCountMismatchBreak() throws IOException {
        List<AuditEvent> events = chain(2, 2, 2);
        for (int target : new int[] {1, 2}) { // 2 is the LAST line of the file
            String[] ls = lines(events).split("\n");
            ls[target] = ls[target] + "\u001fforged-id";
            assertThatThrownBy(() -> AuditRecordFormat.parse(ls[target]))
                    .isInstanceOf(IllegalArgumentException.class);
            Path file = tempDir.resolve("audit" + target + ".log");
            Files.writeString(file, String.join("\n", ls) + "\n");
            AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);
            assertThat(report.anomalies()).extracting(a -> a.type().name()).containsExactly("FIELD_COUNT_MISMATCH");
            assertThat(report.hasBreak()).isTrue();
            assertThat(report.hasStructuralAnomaly()).isFalse();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(cli(file, out)).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("FIELD_COUNT_MISMATCH")
                    .contains("tampering is possible").doesNotContain("not tampering");
        }
    }

    @Test
    void fieldCountMustMatchTheDeclaredVersionExactly() {
        String v3 = AuditRecordFormat.serialize(event(3, "a"));
        String v2 = AuditRecordFormat.serialize(event(2, ""));
        // v3 line (25 fields) relabelled as v2 or v1, and a v2 line (24 fields) relabelled as v3.
        assertThatThrownBy(() -> AuditRecordFormat.parse(v3.replace("\u001f3\u001f", "\u001f2\u001f")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecordFormat.parse(v3.replace("\u001f3\u001f", "\u001f1\u001f")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecordFormat.parse(v2.replace("\u001f2\u001f", "\u001f3\u001f")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecordFormat.parse(v2.replace("\u001f2\u001f", "\u001f1\u001f")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonEmptyExternalIdIsRejectedBelowVersionThree() {
        assertThatThrownBy(() -> event(2, "ext-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(1, "ext-1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(event(2, "").externalCorrelationId()).isEmpty();
        assertThat(event(2, null).externalCorrelationId()).isEmpty();
    }

    @Test
    void auditEventAndCorrelationIdPolicyAgreeOnTheCeiling() {
        io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy permissive =
                io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.opaque(".*");
        List<String> candidates = new ArrayList<>(List.of("x".repeat(256), "x".repeat(257), "x".repeat(255),
                "a b", "a|b", "a\u001fb", "caf\u00e9", "a\\b", "a,b", "a~b", "a@b", "a#b", "a%b", "a\nb"));
        for (char c : "._:/+=-".toCharArray()) {
            candidates.add("a" + c + "b");
        }
        for (char c : "ABZaz09".toCharArray()) {
            candidates.add("q" + c);
        }
        for (String candidate : candidates) {
            boolean policy = permissive.validate(candidate).isPresent();
            boolean constructed;
            try {
                event(3, candidate);
                constructed = true;
            } catch (IllegalArgumentException e) {
                constructed = false;
            }
            assertThat(constructed).as("candidate of length %d: %s", candidate.length(), candidate)
                    .isEqualTo(policy);
        }
    }

    @Test
    void aV3RecordDowngradedToV2IsReportedAsABreak() throws IOException {
        List<AuditEvent> events = chain(3, 3, 3);
        String[] ls = lines(events).split("\n");
        String[] fields = ls[1].split("\u001f", -1);
        fields[20] = "2";
        ls[1] = String.join("\u001f", java.util.Arrays.copyOf(fields, 24));
        Path file = tempDir.resolve("audit.log");
        Files.writeString(file, String.join("\n", ls) + "\n");
        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(file);
        assertThat(report.hasBreak()).isTrue();
        assertThat(cli(file, new ByteArrayOutputStream())).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        String downgraded = ls[1];
        assertThat(AuditRecordFormat.parse(downgraded).recordVersion()).isEqualTo(2);
        assertThat(AuditEventHash.compute(AuditRecordFormat.parse(downgraded)))
                .isNotEqualTo(events.get(1).eventHash());
    }
}
