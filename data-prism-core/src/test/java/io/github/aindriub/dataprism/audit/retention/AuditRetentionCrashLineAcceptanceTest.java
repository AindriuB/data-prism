package io.github.aindriub.dataprism.audit.retention;

import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCliAcceptCrashLineTest.CrashLab;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCliAcceptCrashLineTest.Hash;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCliAcceptCrashLineTest.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Purge after {@code accept-crash-line} (task 293): retention tolerates the accepted line and nothing else. Each
 * scenario runs in plain and in keyed mode. Every fixture gives the line's instance a BOOT in the store (OD-293-8
 * (a)) unless the test says otherwise.
 */
class AuditRetentionCrashLineAcceptanceTest {

    static final String D1 = "audit-2026-03-01.log";
    static final String D2 = "audit-2026-03-02.log";
    static final String D3 = "audit-2026-03-03.log";
    static final String D9 = "audit-2026-09-09.log";
    static final String SEP = "\u001f";
    static final String GENESIS = "0".repeat(64);

    @TempDir
    Path tempDir;

    private static void refusedAt(RuntimeException thrown, String segment) {
        assertThat(thrown).as("the purge should have been refused").isNotNull();
        assertThat(thrown.getMessage()).contains("AUDIT_RETENTION_CHAIN_UNVERIFIED").contains(segment);
    }

    private static List<byte[]> bytesOf(CrashLab lab) throws IOException {
        List<byte[]> out = new ArrayList<>();
        for (String n : lab.names()) {
            out.add(Files.readAllBytes(lab.file(n)));
        }
        return out;
    }

    /**
     * The reviewer's probe: writer a writes four records on 03-01 and its last has its final newline removed; a
     * second writer b then reopens the segment, which adds CR LF, and writes on 03-01, 03-02 and 09-09.
     */
    private record Probe(CrashLab lab, List<AuditEvent> a, long offset) {
    }

    private Probe probe(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder a = lab.writer("a", true);
        List<AuditEvent> events = lab.rec(a, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z",
                "2026-03-01T03:00:00Z", "2026-03-01T04:00:00Z");
        lab.closeSink();
        byte[] bytes = Files.readAllBytes(lab.file(D1));
        Files.write(lab.file(D1), Arrays.copyOf(bytes, bytes.length - 1));
        long offset = lab.lastLineOffset(D1);
        lab.reopen();
        AuditRecorder b = lab.writer("b", false);
        lab.rec(b, "2026-03-01T05:00:00Z", "2026-03-02T01:00:00Z", "2026-09-09T01:00:00Z");
        lab.closeSink();
        return new Probe(lab, events, offset);
    }

    // -- recovery -------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryAfterParsingCrashLine(boolean keyed) throws IOException {
        Probe p = probe(keyed);
        CrashLab lab = p.lab();
        refusedAt(lab.purgeThrown(lab.retention()), D1);
        assertThat(lab.names()).contains(D1, D2);
        assertThat(lab.cps.retentionAnchors()).isEmpty();

        Run run = lab.accept(D1, p.offset());

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        List<AuditCheckpoint> accepted = lab.cps.crashLinesAccepted();
        assertThat(accepted).hasSize(1);
        assertThat(accepted.get(0).bootWaived()).isFalse();
        assertThat(Files.readAllLines(lab.store).get(Files.readAllLines(lab.store).size() - 1))
                .contains("CRASH_LINE_ACCEPTED").doesNotContain("bootWaived");
        String retainedBefore = lab.read(D9);

        assertThat(lab.purgeThrown(lab.retention())).isNull();

        assertThat(lab.names()).containsExactly(D9);
        assertThat(lab.read(D9)).isEqualTo(retainedBefore);
        // writer a's anchor is its last hashed record (its third), never the accepted fourth
        AuditCheckpoint aAnchor = lab.cps.retentionAnchors().stream()
                .filter(x -> x.instanceId().equals(p.a().get(0).instanceId())).findFirst().orElseThrow();
        assertThat(aAnchor.sequence()).isEqualTo(3);
        assertThat(aAnchor.headHash()).isEqualTo(p.a().get(2).eventHash());
        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(lab.dir, lab.store,
                Period.ofMonths(6), lab.keys, false);
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.checkpointFindings()).isEmpty();
        Run verify = lab.verifyMode();
        assertThat(verify.out()).doesNotContain("NOTICE: crash line accepted");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCrashOnTheFirstRecordIsAcceptedAndBothDaysPurge(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder c = lab.writer("c", true);
        AuditEvent e = lab.rec(c, "2026-03-01T01:00:00Z");
        lab.rec(lab.writer("o", false), "2026-03-02T01:00:00Z", "2026-03-02T02:00:00Z");
        lab.rec(lab.writer("o", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.replaceLastLine(D1, lab.line(e) + "\r\n");
        refusedAt(lab.purgeThrown(lab.retention()), D1);

        Run run = lab.accept(D1, 0);

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(run.out()).contains(keyed ? "KEYED_MATCH 1" : "UNKEYED_MATCH");
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
        assertThat(lab.cps.retentionAnchors()).extracting(AuditCheckpoint::instanceId)
                .doesNotContain(e.instanceId());
    }

    @Test
    void aKeyedFirstLineIsReportedUnderTheSecondOfTwoKeys() throws IOException {
        CrashLab lab = new CrashLab(tempDir, true);
        AuditEvent e = lab.rec(lab.writer("c", true), "2026-03-01T01:00:00Z");
        lab.rec(lab.writer("o", false), "2026-03-02T01:00:00Z");
        lab.closeSink();
        lab.replaceLastLine(D1, lab.line(e) + "\r\n");

        Run run = lab.acceptRaw(D1, 0, lab.sha(D1, 0), List.of("--hmac-key-file", lab.otherKeyFile.toString(),
                "--hmac-key-file", lab.keyFile.toString()));

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(run.out()).contains("KEYED_MATCH 2");
    }

    @Test
    void aKeyedFirstLineRunWithNoKeyIsAcceptedAndReportedAsNoUnkeyedMatch() throws IOException {
        CrashLab lab = new CrashLab(tempDir, true);
        AuditEvent e = lab.rec(lab.writer("c", true), "2026-03-01T01:00:00Z");
        lab.rec(lab.writer("o", false), "2026-03-02T01:00:00Z");
        lab.closeSink();
        lab.replaceLastLine(D1, lab.line(e) + "\r\n");

        Run run = lab.acceptRaw(D1, 0, lab.sha(D1, 0), List.of());

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(run.out()).contains("NO_UNKEYED_MATCH")
                .contains("the line looks cut if this deployment is unkeyed; a keyed line was not checked because "
                        + "no key was given.");
    }

    // -- crash after a purge --------------------------------------------------------------------------

    private record AfterPurge(CrashLab lab, List<AuditEvent> w, String instance) {
    }

    /** w writes 1-3 on D1 and 4 (a crash line) as D2's only line; D1 is purged first, anchoring {w, 3}. */
    private AfterPurge afterPurge(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder w = lab.writer("w", true);
        List<AuditEvent> events = lab.rec(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z",
                "2026-03-01T03:00:00Z", "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("u", false), "2026-03-03T01:00:00Z");
        lab.rec(lab.writer("v", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.write(D2, lab.line(events.get(3)) + "\r\n");
        Clock firstPurge = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
        assertThat(lab.purgeThrown(lab.retention(firstPurge, lab.keys))).isNull();
        assertThat(lab.names()).contains(D2);
        assertThat(lab.names()).doesNotContain(D1);
        return new AfterPurge(lab, events, events.get(0).instanceId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCrashLineAfterAPurgeIsAcceptedOnTheAnchorBranchAndItsDayPurges(boolean keyed) throws IOException {
        AfterPurge p = afterPurge(keyed);
        CrashLab lab = p.lab();
        assertThat(lab.cps.retentionAnchors()).anyMatch(a -> a.instanceId().equals(p.instance())
                && a.sequence() == 3);
        refusedAt(lab.purgeThrown(lab.retention()), D2);
        int anchorsBefore = lab.cps.retentionAnchors().size();

        Run run = lab.accept(D2, 0);

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
        assertThat(lab.cps.retentionAnchors().stream().filter(a -> a.instanceId().equals(p.instance())).toList())
                .hasSize(1);
        assertThat(lab.cps.retentionAnchors().size()).isGreaterThan(anchorsBefore); // u's anchor, not w's
    }

    /**
     * w's 1-4 were anchored at 4 on D1 and a failed run left only 1-2 in D1; w's record 5 is the crash line,
     * first on D2. The command refuses at first (the remainder does not reach the anchor), purge finishes D1 and
     * refuses at D2, then the command accepts on the anchor branch and purge deletes D2.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCrashLineAfterAPartlyDeletedAnchoredDayIsAcceptedOnceTheDayIsFinished(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder w = lab.writer("w", true);
        List<AuditEvent> ev = new ArrayList<>(lab.rec(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z",
                "2026-03-01T03:00:00Z", "2026-03-01T04:00:00Z"));
        ev.add(lab.rec(w, "2026-03-02T00:30:00Z"));
        lab.rec(lab.writer("o", false), "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("o2", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.write(D1, lab.line(ev.get(0)) + "\n" + lab.line(ev.get(1)) + "\n");
        lab.replaceLine(D2, 0, lab.line(ev.get(4)) + "\r\n");
        lab.anchor(ev.get(0).instanceId(), 4, ev.get(3).eventHash(), "2026-03-01", "2026-09-02T00:00:00Z");

        Run early = lab.accept(D2, 0);
        assertThat(early.code()).as(early.all()).isNotEqualTo(0);
        assertThat(lab.cps.crashLinesAccepted()).isEmpty();

        refusedAt(lab.purgeThrown(lab.retention()), D2);
        assertThat(lab.names()).doesNotContain(D1).contains(D2);

        Run run = lab.accept(D2, 0);
        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
    }

    // -- cut crash lines ------------------------------------------------------------------------------

    private static String cutInsideField(AuditEvent e, int index) {
        String[] fields = AuditRecordFormat.serialize(e).split(SEP, -1);
        String partial = fields[index].isEmpty() ? ""
                : fields[index].substring(0, Math.max(1, fields[index].length() / 2));
        return String.join(SEP, Arrays.copyOf(fields, index)) + SEP + partial;
    }

    private static boolean parses(String line) {
        try {
            AuditRecordFormat.parse(line);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCutInsideTheEventHashIsAcceptedAsASoleFirstLineAndAsALineAfterAnAnchor(boolean keyed) throws IOException {
        assertCutAccepted(keyed, 19, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCutInsideANonEmptyExternalCorrelationIdIsAcceptedOnBothBranches(boolean keyed) throws IOException {
        assertCutAccepted(keyed, 24, true);
    }

    private void assertCutAccepted(boolean keyed, int field, boolean ext) throws IOException {
        // branch (ii): the instance's sole GENESIS line at sequence 1
        {
            CrashLab lab = new CrashLab(tempDir.resolve("ii"), keyed);
            AuditRecorder c = lab.writer("c", true);
            AuditEvent e = ext ? lab.recExt(c, "2026-03-01T01:00:00Z") : lab.rec(c, "2026-03-01T01:00:00Z");
            lab.rec(lab.writer("o", false), "2026-03-02T01:00:00Z");
            lab.rec(lab.writer("o2", false), "2026-09-09T01:00:00Z");
            lab.closeSink();
            String cut = cutInsideField(e, field);
            assertThat(parses(cut)).isTrue();
            lab.replaceLastLine(D1, cut + "\r\n");
            refusedAt(lab.purgeThrown(lab.retention()), D1);

            Run run = lab.accept(D1, 0);

            assertThat(run.code()).as(run.all()).isEqualTo(0);
            assertThat(run.out()).contains(keyed ? "NO_MATCH_LOOKS_CUT" : "NO_UNKEYED_MATCH");
            assertThat(lab.purgeThrown(lab.retention())).isNull();
            assertThat(lab.names()).containsExactly(D9);
        }
        // branch (iii): seq 4 after the anchor {w, 3}
        {
            Path root = tempDir.resolve("iii");
            CrashLab lab = new CrashLab(root, keyed);
            AuditRecorder w = lab.writer("w", true);
            lab.rec(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
            AuditEvent e4 = ext ? lab.recExt(w, "2026-03-02T01:00:00Z") : lab.rec(w, "2026-03-02T01:00:00Z");
            lab.rec(lab.writer("v", false), "2026-09-09T01:00:00Z");
            lab.closeSink();
            String cut = cutInsideField(e4, field);
            assertThat(parses(cut)).isTrue();
            lab.write(D2, cut + "\r\n");
            Clock firstPurge = Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
            assertThat(lab.purgeThrown(lab.retention(firstPurge, lab.keys))).isNull();
            refusedAt(lab.purgeThrown(lab.retention()), D2);

            Run run = lab.accept(D2, 0);

            assertThat(run.code()).as(run.all()).isEqualTo(0);
            assertThat(run.out()).contains(keyed ? "NO_MATCH_LOOKS_CUT" : "NO_UNKEYED_MATCH");
            assertThat(lab.purgeThrown(lab.retention())).isNull();
            assertThat(lab.names()).containsExactly(D9);
        }
    }

    // -- crash after a checkpoint, and rule 6 ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCrashLineAfterAPeriodicCheckpointIsAcceptedAndItsDayPurges(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder w = lab.writer("w", true);
        List<AuditEvent> events = lab.rec(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        w.checkpoint(); // PERIODIC {w, 3}
        events = new ArrayList<>(events);
        events.add(lab.rec(w, "2026-03-01T04:00:00Z"));
        lab.rec(lab.writer("o", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.replaceLastLine(D1, lab.line(events.get(3)) + "\r\n");
        // after the crash, a shutdown that knows only the last committed record
        lab.checkpoint(AuditCheckpoint.Kind.SHUTDOWN, events.get(0).instanceId(), 3, events.get(2).eventHash());
        refusedAt(lab.purgeThrown(lab.retention()), D1);

        Run run = lab.accept(D1, lab.lastLineOffset(D1));

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
    }

    /**
     * w's 1-3 on D1 and a crash line 4 as w's last line on D1 (or the first of D2), SHUTDOWN {w, 3}. Unclosed u
     * writes on D1 as well, with its next record in the first retained segment, so the boundary scan opens it.
     */
    private record Rule6(CrashLab lab, List<AuditEvent> w, long offset, String segment) {
    }

    private Rule6 rule6(boolean keyed, boolean crashInSecondDay) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder w = lab.writer("w", true);
        List<AuditEvent> events = new ArrayList<>(lab.rec(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z",
                "2026-03-01T03:00:00Z"));
        AuditRecorder u = lab.writer("u", false);
        lab.rec(u, "2026-03-01T03:30:00Z");
        events.add(lab.rec(w, crashInSecondDay ? "2026-03-02T00:30:00Z" : "2026-03-01T04:00:00Z"));
        lab.rec(lab.writer("x", false), "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("y", false), "2026-03-03T01:00:00Z");
        lab.rec(u, "2026-09-09T01:00:00Z");
        lab.closeSink();
        String crash = lab.line(events.get(3)) + "\r\n";
        String segment = crashInSecondDay ? D2 : D1;
        long offset;
        if (crashInSecondDay) {
            // w's record 4 is the first line of D2, ahead of x's record
            lab.replaceLine(D2, 0, crash);
            offset = 0;
        } else {
            lab.replaceLine(D1, 4, crash);
            offset = lab.lineOffset(D1, 4);
        }
        lab.checkpoint(AuditCheckpoint.Kind.SHUTDOWN, events.get(0).instanceId(), 3, events.get(2).eventHash());
        return new Rule6(lab, events, offset, segment);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAcceptedLastLineOfTheDayDoesNotStopRule6AndThreeDaysPurge(boolean keyed) throws IOException {
        Rule6 r = rule6(keyed, false);
        CrashLab lab = r.lab();
        refusedAt(lab.purgeThrown(lab.retention()), D1);
        assertThat(lab.names()).contains(D1, D2, D3);

        Run run = lab.accept(r.segment(), r.offset());

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCutTailAfterAcceptanceStillStopsRule6(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder w = lab.writer("w", true);
        List<AuditEvent> ev = new ArrayList<>(lab.rec(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z",
                "2026-03-01T03:00:00Z"));
        ev.add(lab.rec(w, "2026-03-02T00:30:00Z"));
        lab.rec(lab.writer("x", false), "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("o", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.replaceLine(D2, 0, lab.line(ev.get(3)) + "\r\n");
        Run run = lab.accept(D2, 0);
        assertThat(run.code()).as(run.all()).isEqualTo(0);
        // w's records 2 and 3 are cut from D1 after the acceptance; the crash line is still first on D2
        lab.write(D1, lab.line(ev.get(0)) + "\n");
        Map<String, String> before = lab.snapshot();

        RuntimeException thrown = lab.purgeThrown(lab.retention());

        refusedAt(thrown, D1);
        assertThat(lab.names()).contains(D1, D2);
        assertThat(lab.snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRecordOffTheChainBeyondTheAcceptedLineStillStopsRule6(boolean keyed) throws IOException {
        Rule6 r = rule6(keyed, true);
        CrashLab lab = r.lab();
        Run run = lab.accept(r.segment(), r.offset());
        assertThat(run.code()).as(run.all()).isEqualTo(0);
        String id = r.w().get(0).instanceId();
        lab.append(D9, lab.forge(r.w().get(3), id, 5, r.w().get(2).eventHash(), Hash.MODE) + "\n");
        lab.append(D9, lab.forge(r.w().get(3), id, 7, "ab".repeat(32), Hash.MODE) + "\n");
        Map<String, String> before = lab.snapshot();

        RuntimeException thrown = lab.purgeThrown(lab.retention());

        refusedAt(thrown, D9);
        assertThat(thrown.getMessage()).contains("SHUTDOWN checkpoint at sequence 3");
        assertThat(lab.names()).contains(D1, D2, D3);
        assertThat(lab.snapshot()).isEqualTo(before);
    }

    // -- the waiver -----------------------------------------------------------------------------------

    private record NoBoot(CrashLab lab, AuditEvent event) {
    }

    private NoBoot noBootLab(boolean keyed, boolean boot) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditEvent e = lab.rec(lab.writer("c", boot), "2026-03-01T01:00:00Z");
        lab.rec(lab.writer("o", false), "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("o2", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.replaceLastLine(D1, lab.line(e) + "\r\n");
        return new NoBoot(lab, e);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withNoBootTheFlagAcceptsRecordsTheWaiverAndPrintsTheNoticeOnVerify(boolean keyed) throws IOException {
        NoBoot n = noBootLab(keyed, false);
        CrashLab lab = n.lab();
        String id = n.event().instanceId();
        // a baseline verify run with an empty store file takes the same code path
        Path empty = tempDir.resolve("empty.jsonl");
        Files.writeString(empty, "");
        List<String> baselineArgs = new ArrayList<>(List.of(lab.dir.toString(), "--checkpoints", empty.toString()));
        baselineArgs.addAll(lab.keyFlags());
        int baseline = lab.cli(baselineArgs).code();
        refusedAt(lab.purgeThrown(lab.retention()), D1);

        Run run = lab.accept(D1, 0, "--no-boot-checkpoint");

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(run.out()).contains("condition 7 (a) was not applied for that instance " + id);
        assertThat(lab.cps.crashLinesAccepted()).hasSize(1);
        assertThat(lab.cps.crashLinesAccepted().get(0).bootWaived()).isTrue();
        assertThat(Files.readString(lab.store)).contains("\"bootWaived\":true");
        Run verify = lab.verifyMode();
        assertThat(verify.out()).contains("NOTICE: crash line accepted with the BOOT check waived: instance " + id
                + ", segment " + D1 + ", offset 0");
        assertThat(verify.code()).isEqualTo(baseline);
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
    }

    @Test
    void aTornStoreTailStillGivesExitFourAndTheNoticeForTheEarlierAcceptance() throws IOException {
        NoBoot n = noBootLab(false, false);
        CrashLab lab = n.lab();
        assertThat(lab.accept(D1, 0, "--no-boot-checkpoint").code()).isEqualTo(0);
        lab.appendStore("{\"kind\":\"PERIO");

        Run verify = lab.verifyMode();

        assertThat(verify.code()).isEqualTo(AuditChainVerifierCli.EXIT_STRUCTURAL_ANOMALY);
        assertThat(verify.out()).contains("TORN CHECKPOINT LINE").contains("NOTICE: crash line accepted with the "
                + "BOOT check waived: instance " + n.event().instanceId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theFlagForAnInstanceThatHasABootWaivesNothingAndRecordsNoWaiver(boolean keyed) throws IOException {
        NoBoot n = noBootLab(keyed, true);
        CrashLab lab = n.lab();

        Run run = lab.accept(D1, 0, "--no-boot-checkpoint");

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(run.out()).doesNotContain("condition 7 (a) was not applied");
        assertThat(lab.cps.crashLinesAccepted().get(0).bootWaived()).isFalse();
        assertThat(Files.readString(lab.store)).doesNotContain("bootWaived");
        assertThat(lab.verifyMode().out()).doesNotContain("NOTICE: crash line accepted");
    }

    // -- recovery variants ----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnterminatedRecordAtTheEndOfANonFinalExpiredSegmentIsHashedAsItsOnDiskBytes(boolean keyed)
            throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder a = lab.writer("a", true);
        lab.rec(a, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z",
                "2026-03-01T23:59:00Z");
        lab.closeSink();
        byte[] bytes = Files.readAllBytes(lab.file(D1));
        Files.write(lab.file(D1), Arrays.copyOf(bytes, bytes.length - 1));
        long offset = lab.lastLineOffset(D1);
        lab.reopen();
        lab.rec(lab.writer("b", false), "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("c", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        assertThat(lab.read(D1)).doesNotEndWith("\n");
        refusedAt(lab.purgeThrown(lab.retention()), D1);
        String onDisk = lab.sha(D1, offset);
        String joined = CrashLab.sha256((lab.read(D1).substring((int) offset) + "\r").getBytes(StandardCharsets.UTF_8));
        assertThat(onDisk).isNotEqualTo(joined);

        Run run = lab.acceptRaw(D1, offset, onDisk, lab.keyFlags());

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(lab.cps.crashLinesAccepted().get(0).headHash()).isEqualTo(onDisk);
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnterminatedRecordAsTheFinalExpiringSegmentsTailIsAcceptedAndPurges(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder a = lab.writer("a", true);
        lab.rec(a, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z",
                "2026-03-01T04:00:00Z");
        lab.rec(lab.writer("b", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        byte[] bytes = Files.readAllBytes(lab.file(D1));
        Files.write(lab.file(D1), Arrays.copyOf(bytes, bytes.length - 1));
        long offset = lab.lastLineOffset(D1);
        refusedAt(lab.purgeThrown(lab.retention()), D1);
        String onDisk = lab.sha(D1, offset);

        Run run = lab.acceptRaw(D1, offset, onDisk, lab.keyFlags());

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(lab.cps.crashLinesAccepted().get(0).headHash()).isEqualTo(onDisk);
        assertThat(lab.purgeThrown(lab.retention())).isNull();
        assertThat(lab.names()).containsExactly(D9);
    }

    // -- exactly that line ----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSecondUnhashedCompleteLineInTheDayMakesPurgeRefuseAgain(boolean keyed) throws IOException {
        Probe p = probe(keyed);
        CrashLab lab = p.lab();
        assertThat(lab.accept(D1, p.offset()).code()).isEqualTo(0);
        lab.append(D1, lab.line(p.a().get(0)) + "\r\n");

        refusedAt(lab.purgeThrown(lab.retention()), D1);
        assertThat(lab.names()).contains(D1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aByteEditedAcceptedLineMakesPurgeRefuseAgain(boolean keyed) throws IOException {
        Probe p = probe(keyed);
        CrashLab lab = p.lab();
        assertThat(lab.accept(D1, p.offset()).code()).isEqualTo(0);
        byte[] bytes = Files.readAllBytes(lab.file(D1));
        int idx = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("ALLOW", (int) p.offset());
        bytes[idx + 4] = 'X';
        Files.write(lab.file(D1), bytes);

        refusedAt(lab.purgeThrown(lab.retention()), D1);
        assertThat(lab.names()).contains(D1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aByteIdenticalCopyAtAnotherSegmentOfTheDayMakesPurgeRefuseAgain(boolean keyed) throws IOException {
        Probe p = probe(keyed);
        CrashLab lab = p.lab();
        String accepted = lab.read(D1).substring((int) p.offset(), lab.read(D1).indexOf('\n', (int) p.offset()) + 1);
        assertThat(lab.accept(D1, p.offset()).code()).isEqualTo(0);
        lab.write("audit-2026-03-01.0001.log", accepted);

        refusedAt(lab.purgeThrown(lab.retention()), "audit-2026-03-01");
        assertThat(lab.names()).contains(D1, "audit-2026-03-01.0001.log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aByteIdenticalCopyAtTheSameOffsetOfAnotherSegmentMakesPurgeRefuseAgain(boolean keyed) throws IOException {
        Probe p = probe(keyed);
        CrashLab lab = p.lab();
        String accepted = lab.read(D1).substring((int) p.offset(), lab.read(D1).indexOf('\n', (int) p.offset()) + 1);
        assertThat(lab.accept(D1, p.offset()).code()).isEqualTo(0);
        // a torn fragment pads the copy to the very same offset, so only the segment name tells them apart (rule 4)
        lab.write("audit-2026-03-01.0001.log", "x".repeat((int) p.offset() - 2) + "\r\n" + accepted);

        refusedAt(lab.purgeThrown(lab.retention()), "audit-2026-03-01");
        assertThat(lab.names()).contains(D1, "audit-2026-03-01.0001.log");
    }

    // -- a partly deleted day whose remainder holds the accepted line -----------------------------------

    private static final String D1_1 = "audit-2026-03-01.0001.log";

    /**
     * D1.log holds a's records 1-2, c's 1 and a's accepted crash line (record 3, CR LF terminated); D1.0001 holds
     * c's 2; D2 holds c's 3. The purge deletes D1.0001, then the delete of D1.log fails once. Returns the lab after that failure.
     */
    private CrashLab partlyDeletedWithAcceptedLine(boolean keyed) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder a = lab.writer("a", true);
        AuditRecorder c = lab.writer("c", false);
        List<AuditEvent> ea = lab.rec(a, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T05:00:00Z");
        List<AuditEvent> ec = lab.rec(c, "2026-03-01T03:00:00Z", "2026-03-01T04:00:00Z",
                "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("o", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.write(D2, lab.line(ec.get(2)) + "\n");
        lab.write(D1, lab.line(ea.get(0)) + "\n" + lab.line(ea.get(1)) + "\n" + lab.line(ec.get(0)) + "\n"
                + lab.line(ea.get(2)) + "\r\n");
        lab.write(D1_1, lab.line(ec.get(1)) + "\n");
        Run run = lab.accept(D1, lab.lineOffset(D1, 3));
        assertThat(run.code()).as(run.all()).isEqualTo(0);

        AuditRetention failing = lab.retention();
        int[] failures = {0};
        failing.deleter = segment -> {
            if (segment.getFileName().toString().equals(D1) && failures[0]++ == 0) {
                throw new IOException("injected");
            }
            Files.delete(segment);
        };
        RuntimeException thrown = lab.purgeThrown(failing);
        assertThat(thrown).hasMessageContaining("AUDIT_RETENTION_DELETE_FAILED");
        assertThat(lab.names()).contains(D1).doesNotContain(D1_1);
        return lab;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPartlyDeletedDayWhoseRemainderHoldsAnAcceptedLineIsFinishedOnRetry(boolean keyed) throws IOException {
        CrashLab lab = partlyDeletedWithAcceptedLine(keyed);

        assertThat(lab.purgeThrown(lab.retention())).isNull();

        assertThat(lab.names()).containsExactly(D9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnacceptedFragmentInTheSameShapeStillRefuses(boolean keyed) throws IOException {
        CrashLab lab = partlyDeletedWithAcceptedLine(keyed);
        List<String> kept = new ArrayList<>();
        for (String l : Files.readAllLines(lab.store)) {
            if (!l.contains("CRASH_LINE_ACCEPTED")) {
                kept.add(l);
            }
        }
        Files.write(lab.store, (String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8));

        refusedAt(lab.purgeThrown(lab.retention()), D1);
        assertThat(lab.names()).contains(D1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnparseableFragmentAtTheAcceptedOffsetWithOtherBytesStillRefuses(boolean keyed) throws IOException {
        CrashLab lab = partlyDeletedWithAcceptedLine(keyed);
        // a fragment that does not parse is no unhashed complete record; only the structural check can refuse it
        lab.replaceLine(D1, 3, "torn fragment\r\n");

        refusedAt(lab.purgeThrown(lab.retention()), D1);
        assertThat(lab.names()).contains(D1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCutRemainderOfTheAcceptedLinesSegmentStillRefuses(boolean keyed) throws IOException {
        CrashLab lab = partlyDeletedWithAcceptedLine(keyed);
        lab.dropFirstLines(D1, 1);

        refusedAt(lab.purgeThrown(lab.retention()), D1);
        assertThat(lab.names()).contains(D1);
    }

    // -- the verifier is otherwise unchanged ----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAcceptanceInTheStoreChangesNoVerifierReport(boolean keyed) throws IOException {
        Probe p = probe(keyed);
        CrashLab lab = p.lab();
        // a copy of the inputs, one with a break and one with a trailing partial line
        Path broken = tempDir.resolve("broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve(D1), lab.read(D1).replace("T02:00:00", "T02:00:01"));
        Path storeCopy = tempDir.resolve("store-without.jsonl");
        Files.copy(lab.store, storeCopy);
        List<Path> dirs = List.of(lab.dir, broken);
        List<AuditChainVerifier.VerificationReport> without = new ArrayList<>();
        for (Path d : dirs) {
            without.add(AuditChainVerifier.verify(d, storeCopy, Period.ofMonths(6), lab.keys, false));
        }
        assertThat(lab.accept(D1, p.offset()).code()).isEqualTo(0);

        for (int i = 0; i < dirs.size(); i++) {
            assertThat(AuditChainVerifier.verify(dirs.get(i), lab.store, Period.ofMonths(6), lab.keys, false))
                    .isEqualTo(without.get(i));
        }
    }
}
