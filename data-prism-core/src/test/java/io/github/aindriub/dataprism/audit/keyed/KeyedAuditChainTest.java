package io.github.aindriub.dataprism.audit.keyed;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditChainMode;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditEventHash;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.checkpoint.FileAuditCheckpointSink;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.retention.AuditRetention;
import io.github.aindriub.dataprism.audit.retention.AuditRetention.RetentionException;
import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier.VerificationReport;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyedAuditChainTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    private static final byte[] MATERIAL_A = bytes(1);
    private static final byte[] MATERIAL_B = bytes(2);
    private static final AuditChainKey KEY_A = new AuditChainKey("key-a", MATERIAL_A);
    private static final AuditChainKey KEY_B = new AuditChainKey("key-b", MATERIAL_B);

    @TempDir
    Path tempDir;

    private static byte[] bytes(int seed) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) seed);
        return b;
    }

    private static AuditEvent write(AuditRecorder recorder) {
        return recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
    }

    private Path keyedFile(String name, AuditChainMode mode, int records) throws IOException {
        Path path = tempDir.resolve(name);
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "w", null, null, mode);
            for (int i = 0; i < records; i++) {
                write(recorder);
            }
        }
        return path;
    }

    private static VerificationReport verify(Path path, List<AuditChainKey> keys, boolean allowUnkeyed)
            throws IOException {
        return AuditChainVerifier.verify(path, null, AuditChainVerifier.DEFAULT_MINIMUM_RETENTION, keys,
                allowUnkeyed);
    }

    // -- key and mode ---------------------------------------------------------

    @Test
    void keyToStringHasTheIdAndNoKeyBytes() {
        String text = KEY_A.toString();
        assertThat(text).contains("key-a");
        assertThat(text).doesNotContain(HexFormat.of().formatHex(MATERIAL_A))
                .doesNotContain(Base64.getEncoder().encodeToString(MATERIAL_A))
                .doesNotContain(Arrays.toString(MATERIAL_A));
        assertThat(AuditChainMode.keyed(KEY_A).toString()).doesNotContain(Base64.getEncoder().encodeToString(MATERIAL_A));
    }

    @Test
    void keyRefusesShortMaterialAndCopiesWhatItIsGiven() {
        assertThatThrownBy(() -> new AuditChainKey("k", new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditChainKey(" ", bytes(1))).isInstanceOf(IllegalArgumentException.class);
        byte[] material = bytes(1);
        AuditChainKey key = new AuditChainKey("k", material);
        AuditEvent event = new AuditEvent("e", FIXED.instant(), "p", "c", "t", "E", "s", "f", "D", "sc", "i", "C",
                "ALLOW", Set.of(), Set.of(), "co", "w/1", 1, "0".repeat(64), "x");
        String before = AuditEventHash.compute(event, key);
        Arrays.fill(material, (byte) 9);
        assertThat(AuditEventHash.compute(event, key)).isEqualTo(before);
    }

    @Test
    void modeIsUnkeyedOrKeyedAndRecorderRefusesNull() {
        assertThat(AuditChainMode.unkeyed().isKeyed()).isFalse();
        assertThat(AuditChainMode.keyed(KEY_A).key()).containsSame(KEY_A);
        assertThatThrownBy(() -> AuditChainMode.keyed(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditRecorder(new FileAuditSink(tempDir.resolve("n.log")), FIXED, "w", null,
                null, null)).isInstanceOf(NullPointerException.class);
    }

    // -- the hash -------------------------------------------------------------

    @Test
    void keyedHashIsHmacSha256OverTheBytesTheUnkeyedHashHashes() throws Exception {
        AuditEvent event = new AuditEvent("e-1", FIXED.instant(), "p", "c", "t", "E", "s", "f", "D", "sc", "i", "C",
                "ALLOW", Set.of("a:ANSWERED"), Set.of(), "co", "w/1", 1, "0".repeat(64), "x", 3,
                java.util.Map.of("name", "REFUSED"), "", "", "ext-1");
        // Independent re-derivation of the version 3 body from the documented layout.
        StringBuilder b = new StringBuilder();
        enc(b, "3");
        for (String v : new String[] {"e-1", FIXED.instant().toString(), "w/1", "1", "p", "c", "t", "E", "s", "f",
                "D", "sc", "i", "C", "ALLOW", "co"}) {
            enc(b, v);
        }
        enc(b, "1");
        enc(b, "a:ANSWERED");
        enc(b, "0");
        enc(b, "0".repeat(64));
        enc(b, "1");
        enc(b, "name");
        enc(b, "REFUSED");
        enc(b, "");
        enc(b, "");
        enc(b, "ext-1");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(MATERIAL_A, "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(b.toString().getBytes(StandardCharsets.UTF_8)));

        assertThat(AuditEventHash.compute(event, KEY_A)).isEqualTo(expected);
        assertThat(AuditEventHash.compute(event, KEY_A)).isNotEqualTo(AuditEventHash.compute(event));
        assertThat(AuditEventHash.compute(event, KEY_B)).isNotEqualTo(expected);
    }

    private static void enc(StringBuilder b, String s) {
        b.append(s.getBytes(StandardCharsets.UTF_8).length).append(':').append(s);
    }

    @Test
    void unkeyedModeWritesTheUnkeyedHashAndKeyedModeWritesTheKeyedOne() throws IOException {
        AuditEvent plain;
        AuditEvent keyed;
        try (FileAuditSink s1 = new FileAuditSink(tempDir.resolve("p.log"));
             FileAuditSink s2 = new FileAuditSink(tempDir.resolve("k.log"))) {
            plain = write(new AuditRecorder(s1, FIXED, "w", null, null, AuditChainMode.unkeyed()));
            keyed = write(new AuditRecorder(s2, FIXED, "w", null, null, AuditChainMode.keyed(KEY_A)));
        }
        assertThat(plain.eventHash()).isEqualTo(AuditEventHash.compute(plain));
        assertThat(keyed.eventHash()).isEqualTo(AuditEventHash.compute(keyed, KEY_A))
                .isNotEqualTo(AuditEventHash.compute(keyed));
    }

    @Test
    void keyedCheckpointHeadsAreTheKeyedHashes() throws IOException {
        Path audit = tempDir.resolve("a.log");
        Path cp = tempDir.resolve("cp.jsonl");
        AuditEvent last;
        try (FileAuditSink sink = new FileAuditSink(audit);
             FileAuditCheckpointSink cps = new FileAuditCheckpointSink(cp, tempDir.resolve("unused"))) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "w", cps, null, AuditChainMode.keyed(KEY_A));
            write(recorder);
            last = write(recorder);
            recorder.checkpoint();
        }
        List<String> lines = Files.readAllLines(cp);
        AuditCheckpoint periodic = AuditCheckpoint.fromJsonLine(lines.get(lines.size() - 1));
        assertThat(periodic.headHash()).isEqualTo(last.eventHash())
                .isEqualTo(AuditEventHash.compute(last, KEY_A));
        assertThat(AuditChainVerifier.verify(audit, cp, Period.ofMonths(6), List.of(KEY_A), false).hasBreak())
                .isFalse();
    }

    // -- the verifier ---------------------------------------------------------

    @Test
    void keyedFilePassesWithItsKeyAndNamesTheKeyId() throws IOException {
        Path path = keyedFile("a.log", AuditChainMode.keyed(KEY_A), 3);
        VerificationReport report = verify(path, List.of(KEY_B, KEY_A), false);
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.writers()).singleElement().satisfies(w -> {
            assertThat(w.keyId()).contains("key-a");
            assertThat(w.unkeyedAllowed()).isFalse();
        });
    }

    @Test
    void keyedFileFailsWithTheWrongKey() throws IOException {
        Path path = keyedFile("a.log", AuditChainMode.keyed(KEY_A), 3);
        VerificationReport report = verify(path, List.of(KEY_B), false);
        assertThat(report.hasBreak()).isTrue();
        assertThat(report.writers().get(0).firstBreak().orElseThrow().reason()).contains("none of the supplied keys");
    }

    @Test
    void keyedFileFailsWithNoKeyRatherThanPassing() throws IOException {
        Path path = keyedFile("a.log", AuditChainMode.keyed(KEY_A), 3);
        assertThat(verify(path, List.of(), false).hasBreak()).isTrue();
        assertThat(AuditChainVerifier.verify(path).hasBreak()).isTrue();
    }

    @Test
    void unkeyedFileFailsWhenAKeyIsRequiredAndPassesOnlyWhenAllowedAndCounted() throws IOException {
        Path path = keyedFile("u.log", AuditChainMode.unkeyed(), 3);
        assertThat(verify(path, List.of(KEY_A), false).hasBreak()).isTrue();

        VerificationReport allowed = verify(path, List.of(KEY_A), true);
        assertThat(allowed.hasBreak()).isFalse();
        assertThat(allowed.unkeyedWriterCount()).isEqualTo(1);
        assertThat(allowed.writers().get(0).unkeyedAllowed()).isTrue();
        assertThat(allowed.writers().get(0).keyId()).isEmpty();
    }

    @Test
    void aKeyedFileEditedWithEveryLaterHashRecomputedUnkeyedFails() throws IOException {
        Path path = keyedFile("a.log", AuditChainMode.keyed(KEY_A), 4);
        List<String> lines = Files.readAllLines(path);
        List<String> forged = new ArrayList<>();
        forged.add(lines.get(0));
        String previous = AuditRecordFormat.parse(lines.get(0)).eventHash();
        for (int i = 1; i < lines.size(); i++) {
            AuditEvent e = AuditRecordFormat.parse(lines.get(i));
            String principal = i == 1 ? "someone-else" : e.principalId();
            AuditEvent draft = copy(e, principal, previous, "x");
            AuditEvent done = copy(e, principal, previous, AuditEventHash.compute(draft));
            forged.add(AuditRecordFormat.serialize(done));
            previous = done.eventHash();
        }
        Path forgedFile = tempDir.resolve("forged.log");
        Files.write(forgedFile, (String.join("\n", forged) + "\n").getBytes(StandardCharsets.UTF_8));

        assertThat(verify(forgedFile, List.of(KEY_A), false).hasBreak()).isTrue();
        assertThat(verify(forgedFile, List.of(KEY_A), true).hasBreak()).isTrue();
    }

    private static AuditEvent copy(AuditEvent e, String principal, String previous, String hash) {
        return new AuditEvent(e.eventId(), e.timestamp(), principal, e.clientId(), e.tool(), e.entityType(),
                e.subjectPseudonym(), e.parameterFingerprint(), e.privacyProfile(), e.scopeId(), e.purpose(),
                e.caseId(), e.policyDecision(), e.sourceSystems(), e.rejectedArguments(), e.correlationId(),
                e.instanceId(), e.sequence(), previous, hash, e.recordVersion(), e.fieldDispositions(),
                e.approvalId(), e.approverId(), e.externalCorrelationId());
    }

    @Test
    void aDirectoryWithRetiredKeyAWritersAndKeyBWritersPassesWithBothKeys() throws IOException {
        Path dir = tempDir.resolve("seg");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            write(new AuditRecorder(sink, FIXED, "old", null, null, AuditChainMode.keyed(KEY_A)));
            write(new AuditRecorder(sink, FIXED, "new", null, null, AuditChainMode.keyed(KEY_B)));
        }
        VerificationReport both = verify(dir, List.of(KEY_A, KEY_B), false);
        assertThat(both.hasBreak()).isFalse();
        assertThat(both.writers()).extracting(w -> w.keyId().orElse("")).containsExactly("key-a", "key-b");
        assertThat(verify(dir, List.of(KEY_B), false).hasBreak()).isTrue();
    }

    @Test
    void anEmptyKeyListKeepsTheUnkeyedBehaviourOfTheExistingOverloads() throws IOException {
        Path path = keyedFile("u.log", AuditChainMode.unkeyed(), 3);
        assertThat(verify(path, List.of(), false).hasBreak()).isFalse();
        assertThat(verify(path, List.of(), false).writers().get(0).keyId()).isEmpty();
    }

    // -- the CLI --------------------------------------------------------------

    private Path keyFile(String name, String id, byte[] material) throws IOException {
        Path f = tempDir.resolve(name);
        Files.writeString(f, id + "\n" + Base64.getEncoder().encodeToString(material) + "\n");
        return f;
    }

    private int cli(ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
        return AuditChainVerifierCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void cliPrintsTheThreeLinesAndNeverTheKey() throws IOException {
        Path keyed = keyedFile("a.log", AuditChainMode.keyed(KEY_A), 2);
        Path kf = keyFile("a.key", "key-a", MATERIAL_A);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = cli(out, new ByteArrayOutputStream(), keyed.toString(), "--hmac-key-file", kf.toString());
        String text = out.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(text).contains("LIMITATION: this verifier detects an edit or a deletion of a record already written");
        assertThat(text).doesNotContain("keyed MAC").doesNotContain("does not build")
                .doesNotContain("unkeyed SHA-256");
        assertThat(text).contains("a keyed chain resists a writer who lacks the key, not anyone who holds it, "
                + "including the operator and the process that writes the file");
        assertThat(text).contains("verified under key id: key-a");
        assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(MATERIAL_A));

        Path unkeyed = keyedFile("u.log", AuditChainMode.unkeyed(), 2);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        cli(plain, new ByteArrayOutputStream(), unkeyed.toString());
        assertThat(plain.toString(StandardCharsets.UTF_8))
                .contains("resisting that needs a keyed MAC")
                .doesNotContain("KEYED CHAIN: a keyed chain resists");
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        int code2 = cli(out2, new ByteArrayOutputStream(), unkeyed.toString(), "--hmac-key-file", kf.toString(),
                "--allow-unkeyed-writers");
        assertThat(code2).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(out2.toString(StandardCharsets.UTF_8)).contains("UNKEYED WRITERS ALLOWED: 1 writer(s)");

        ByteArrayOutputStream out3 = new ByteArrayOutputStream();
        assertThat(cli(out3, new ByteArrayOutputStream(), unkeyed.toString(), "--hmac-key-file", kf.toString()))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }

    @Test
    void cliMalformedOrMissingKeyFileExitsUnreadableNamingTheFlagNotTheContents() throws IOException {
        Path audit = keyedFile("a.log", AuditChainMode.keyed(KEY_A), 1);
        Path bad = tempDir.resolve("bad.key");
        Files.writeString(bad, "key-a\nSECRETISH-NOT-BASE64!!\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(out, err, audit.toString(), "--hmac-key-file", bad.toString()))
                .isEqualTo(AuditChainVerifierCli.EXIT_UNREADABLE_INPUT);
        String e = err.toString(StandardCharsets.UTF_8);
        assertThat(e).contains("--hmac-key-file").doesNotContain("SECRETISH");

        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        assertThat(cli(new ByteArrayOutputStream(), err2, audit.toString(), "--hmac-key-file",
                tempDir.resolve("missing").toString())).isEqualTo(AuditChainVerifierCli.EXIT_UNREADABLE_INPUT);
        assertThat(err2.toString(StandardCharsets.UTF_8)).contains("--hmac-key-file");

        Path shortKey = keyFile("short.key", "k", new byte[8]);
        assertThat(cli(new ByteArrayOutputStream(), new ByteArrayOutputStream(), audit.toString(),
                "--hmac-key-file", shortKey.toString())).isEqualTo(AuditChainVerifierCli.EXIT_UNREADABLE_INPUT);
    }

    @Test
    void cliHelpStatesTheKeyFileFormat() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli(out, new ByteArrayOutputStream(), "--help");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("--hmac-key-file")
                .contains("--allow-unkeyed-writers").contains("base64");
    }

    // -- review round fixes ---------------------------------------------------

    @Test
    void allowFlagWithoutAKeyCountsEveryUnkeyedWriter() throws IOException {
        Path path = tempDir.resolve("three.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            for (String w : new String[] {"w1", "w2", "w3"}) {
                write(new AuditRecorder(sink, FIXED, w, null, null, AuditChainMode.unkeyed()));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = cli(out, new ByteArrayOutputStream(), path.toString(), "--allow-unkeyed-writers");
        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("UNKEYED WRITERS ALLOWED: 3 writer(s)");
    }

    @Test
    void allowFlagWithoutAKeyDoesNotCountABrokenWriterAsAllowed() throws IOException {
        Path path = tempDir.resolve("broken.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder w1 = new AuditRecorder(sink, FIXED, "w1", null, null, AuditChainMode.unkeyed());
            for (int i = 0; i < 3; i++) {
                write(w1);
            }
            AuditRecorder w2 = new AuditRecorder(sink, FIXED, "w2", null, null, AuditChainMode.unkeyed());
            write(w2);
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        lines.remove(1);
        Files.write(path, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));

        VerificationReport report = verify(path, List.of(), true);

        assertThat(report.hasBreak()).isTrue();
        assertThat(report.writers()).filteredOn(AuditChainVerifier.WriterResult::broken)
                .allSatisfy(w -> assertThat(w.unkeyedAllowed()).isFalse());
        assertThat(report.writers()).filteredOn(w -> !w.broken())
                .allSatisfy(w -> assertThat(w.unkeyedAllowed()).isTrue());
    }

    @Test
    void aWriterMissingARecordUnderKeyBNamesTheDeletionNotTheFirstRecord() throws IOException {
        Path path = keyedFile("b.log", AuditChainMode.keyed(KEY_B), 5);
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        lines.remove(2);
        Files.write(path, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));

        VerificationReport report = verify(path, List.of(KEY_A, KEY_B), false);

        assertThat(report.hasBreak()).isTrue();
        assertThat(report.writers().get(0).firstBreak().orElseThrow().sequence()).isEqualTo(4);
    }

    @Test
    void aKeyBWriterWithABadCheckpointIsReportedFromTheKeyBReplayAndLabelledKeyB() throws IOException {
        Path audit = tempDir.resolve("b-cp.log");
        Path cp = tempDir.resolve("b-cp.jsonl");
        try (FileAuditSink sink = new FileAuditSink(audit);
             FileAuditCheckpointSink cps = new FileAuditCheckpointSink(cp, tempDir.resolve("unused"))) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "w", cps, null, AuditChainMode.keyed(KEY_B));
            for (int i = 0; i < 5; i++) {
                write(recorder);
            }
            recorder.checkpoint();
        }
        // Replace the last record with one that is a valid keyed record, so only the checkpoint disagrees.
        List<String> lines = new ArrayList<>(Files.readAllLines(audit));
        AuditEvent last = AuditRecordFormat.parse(lines.get(4));
        AuditEvent draft = copy(last, "someone-else", last.previousHash(), "x");
        lines.set(4, AuditRecordFormat.serialize(copy(last, "someone-else", last.previousHash(),
                AuditEventHash.compute(draft, KEY_B))));
        Files.write(audit, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));

        VerificationReport report = AuditChainVerifier.verify(audit, cp, Period.ofMonths(6),
                List.of(KEY_A, KEY_B), false);

        assertThat(report.hasBreak()).isTrue();
        assertThat(report.writers()).singleElement().satisfies(w -> {
            assertThat(w.broken()).isTrue();
            assertThat(w.firstBreak().orElseThrow().sequence()).isEqualTo(5);
            assertThat(w.keyId()).contains("key-b");
        });
    }

    @Test
    void aLoneNonGenesisWriterIsNotLabelledAsVerifiedUnderAKey() throws IOException {
        Path path = tempDir.resolve("lone.log");
        AuditEvent draft = new AuditEvent("e-1", FIXED.instant(), "p", "c", "t", "E", "s", "f", "D", "sc", "i", "C",
                "ALLOW", Set.of(), Set.of(), "co", "forged/1", 1, "f".repeat(64), "x");
        AuditEvent forged = copy(draft, "p", "f".repeat(64), AuditEventHash.compute(draft, KEY_A));
        Files.write(path, ("torn fragment\r\n" + AuditRecordFormat.serialize(forged) + "\n")
                .getBytes(StandardCharsets.UTF_8));

        VerificationReport report = verify(path, List.of(KEY_A), false);

        assertThat(report.writers()).singleElement().satisfies(w -> {
            assertThat(w.nonGenesisStart()).isPresent();
            assertThat(w.keyId()).isEmpty();
        });
    }

    // -- retention ------------------------------------------------------------

    @Test
    void anExpiringKeyedSegmentWhoseKeyIsNotSuppliedIsNotDeleted() throws IOException {
        Path dir = tempDir.resolve("ret");
        Instant[] now = {Instant.parse("2026-03-01T01:00:00Z")};
        Clock moving = new Clock() {
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
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            AuditRecorder recorder = new AuditRecorder(sink, moving, "w", null, null, AuditChainMode.keyed(KEY_A));
            write(recorder);
            now[0] = Instant.parse("2026-09-09T01:00:00Z");
            write(recorder);
        }
        Path anchors = tempDir.resolve("anchors.jsonl");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(anchors, tempDir.resolve("unused"))) {
            assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), sink, NOW, false, List.of(KEY_B),
                    false).purge()).isInstanceOf(RetentionException.class);
            assertThat(dir.resolve("audit-2026-03-01.log")).exists();

            assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), sink, NOW).purge())
                    .isInstanceOf(RetentionException.class);
            assertThat(dir.resolve("audit-2026-03-01.log")).exists();

            List<Path> deleted = new AuditRetention(dir, Period.ofMonths(6), sink, NOW, false, List.of(KEY_A), false)
                    .purge();
            assertThat(deleted).hasSize(1);
            assertThat(dir.resolve("audit-2026-03-01.log")).doesNotExist();
        }
    }
}
