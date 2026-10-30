package io.github.aindriub.dataprism.audit.retention;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditEventHash;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.audit.sink.TeeAuditSink;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import io.github.aindriub.dataprism.audit.retention.AuditRetentionHashedAnchorsTest.Lab;
import io.github.aindriub.dataprism.audit.retention.AuditRetentionHashedAnchorsTest.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rules 6 and 7 of task 292: each expiring day's end must link to what stays, the {@code SHUTDOWN} stop and
 * its contradictions, and dates that must match the file name. Every test runs plain and keyed.
 */
class AuditRetentionBoundaryTest {

    private static final String P = "2026-03-01T";
    private static final String HASH_X = "ab".repeat(32);

    @TempDir
    Path tempDir;

    private static String at(String date, int hour) {
        return date + "T" + String.format("%02d", hour) + ":00:00Z";
    }

    /** The {@code i}-th day (0-based) from 2026-03-11, all retained under the fixed clock. */
    private static String retainedDay(int i) {
        return LocalDate.of(2026, 3, 11).plusDays(i).toString();
    }

    private static List<Path> counting(AuditRetention r) {
        List<Path> reads = new ArrayList<>();
        r.segmentReader = segment -> {
            reads.add(segment);
            return Files.readAllBytes(segment);
        };
        return reads;
    }

    private static List<String> retainedReads(List<Path> reads) {
        return reads.stream().filter(p -> !SegmentedFileAuditSink.segmentDate(p).isBefore(Lab.CUTOFF))
                .map(p -> p.getFileName().toString()).toList();
    }

    // -- rule 6: the link across an expiring day's end ------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLinkBreakAtTheNextExpiringDaysFirstRecordRefusesThePreviousDayToo(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        lab.record(w, at("2026-03-02", 1));
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine("audit-2026-03-01.log", AuditRecordFormat.serialize(d1.get(2)).substring(0, 60) + "\r\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("audit-2026-03-01.log");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-2026-03-02.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @Test
    void theAttributionHoldsWithTwoKeysSuppliedToo() throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), true);
        AuditChainKey k0 = new AuditChainKey("k0", new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
                9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        lab.record(w, at("2026-03-02", 1));
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine("audit-2026-03-01.log", AuditRecordFormat.serialize(d1.get(2)).substring(0, 60) + "\r\n");
        List<AuditChainKey> both = List.of(k0, lab.key);

        Outcome outcome = lab.purge(lab.retention(both), both);

        outcome.refused("audit-2026-03-01.log");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-2026-03-02.log");
    }

    @ParameterizedTest
    @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void aLastExpiringRecordHiddenAsAFragmentIsCaughtByTheRetainedRecordThatDoesNotLinkToIt(boolean keyed,
                                                                                              boolean terminated)
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        lab.record(w, "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine("audit-2026-03-01.log",
                AuditRecordFormat.serialize(d1.get(2)).substring(0, 60) + (terminated ? "\r\n" : ""));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("audit-2026-03-01.log");
        assertThat(lab.names()).contains("audit-2026-03-01.log");
        assertThat(lab.anchors()).isEmpty();
    }

    // -- rule 7: dates must match ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRecentSegmentRenamedIntoAnExpiredDayIsRefused(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        lab.record(w, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        lab.record(w, at("2026-09-05", 1), at("2026-09-05", 2), at("2026-09-05", 3));
        lab.record(w, at("2026-09-08", 1));
        lab.close();
        Files.move(lab.file("audit-2026-09-05.log"), lab.file("audit-2026-03-01.0001.log"));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("audit-2026-03-01.0001.log");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log");
        assertThat(lab.anchors()).isEmpty();
    }

    /** w on 03-01, x's only line alone in a 09-09 segment later renamed into 03-01, another instance on 09-10. */
    private Lab renameLab(boolean keyed, java.util.function.Function<AuditEvent, String> xLine) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        lab.record(lab.writer("w"), at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        AuditEvent x = lab.record(lab.writer("x"), "2026-09-09T01:00:00Z");
        lab.record(lab.writer("y"), "2026-09-10T01:00:00Z");
        lab.close();
        lab.write("audit-2026-09-09.log", xLine.apply(x));
        Files.move(lab.file("audit-2026-09-09.log"), lab.file("audit-2026-03-01.0001.log"));
        return lab;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRenamedSegmentWithNoHashedRecordIsDeletedUnderItsNewDate(boolean keyed) throws IOException {
        Lab lab = renameLab(keyed, x -> AuditRecordFormat.serialize(x).substring(0, 60) + "\r\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-09-10.log");
        assertThat(AuditChainVerifier.verify(lab.dir, null, Period.ofMonths(6), lab.keys, false).hasBreak())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRenamedSegmentHoldingAnUnhashedCompleteRecordIsRefused(boolean keyed) throws IOException {
        Lab lab = renameLab(keyed, x -> AuditRecordFormat.serialize(x) + "\r\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("audit-2026-03-01.0001.log");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRecordDatedBeforeItsSegmentAfterTheClockSteppedBackDoesNotRefuseTheDay(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        lab.record(w, "2026-03-02T00:30:00Z", "2026-03-01T23:50:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        assertThat(lab.names()).contains("audit-2026-03-02.log").doesNotContain("audit-2026-03-01.log");

        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-09-09.log");
    }

    // -- the SHUTDOWN stop: reads bounded for a cleanly closed writer ---------------------------------

    /** Two closing writers on 03-01, then a new instance on each of 180 retained days. */
    private Lab restartLab(boolean keyed, boolean close) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w1 = close ? lab.closingWriter("w1") : lab.writer("w1");
        AuditRecorder w2 = close ? lab.closingWriter("w2") : lab.writer("w2");
        lab.record(w1, at("2026-03-01", 1), at("2026-03-01", 2));
        lab.record(w2, at("2026-03-01", 3));
        w1.close();
        w2.close();
        AuditRecorder n = lab.writer("n");
        for (int i = 0; i < 180; i++) {
            lab.record(n, at(retainedDay(i), 1));
        }
        lab.close();
        return lab;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCleanRestartReadsNoRetainedSegment(boolean keyed) throws IOException {
        Lab lab = restartLab(keyed, true);
        AuditRetention r = lab.retention();
        List<Path> reads = counting(r);

        Outcome outcome = lab.purge(r);

        outcome.succeeded();
        assertThat(lab.names()).doesNotContain("audit-2026-03-01.log").hasSize(180);
        assertThat(retainedReads(reads)).isEmpty();
        assertThat(lab.anchors()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCrashWithoutAShutdownReadsOnToTheLastSegment(boolean keyed) throws IOException {
        Lab lab = restartLab(keyed, false);
        AuditRetention r = lab.retention();
        List<Path> reads = counting(r);

        Outcome outcome = lab.purge(r);

        outcome.succeeded();
        assertThat(lab.names()).doesNotContain("audit-2026-03-01.log");
        assertThat(retainedReads(reads)).hasSize(180).endsWith("audit-" + retainedDay(179) + ".log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailingShutdownReadRefusesBeforeReadingOrDeletingAnything(boolean keyed) throws IOException {
        Lab lab = restartLab(keyed, true);
        lab.store.shutdownReadFails = new IllegalStateException("store unreadable");
        AuditRetention r = lab.retention();
        List<Path> reads = counting(r);

        Outcome outcome = lab.purge(r);

        outcome.refused("SHUTDOWN checkpoints");
        assertThat(lab.names()).contains("audit-2026-03-01.log").hasSize(181);
        assertThat(reads).isEmpty();
    }

    // -- rolled-back N+1 ------------------------------------------------------------------------------

    /**
     * w1 writes 1 to 3 on {@code day}, then a record 4 that reaches disk on {@code nextDay} and whose
     * projection fails (so the recorder rolls back to 3), then closes with SHUTDOWN at 3. w2, with no
     * SHUTDOWN, writes on {@code day} and its next record on the first retained day.
     */
    private Lab rolledBackLab(boolean keyed, String day, String nextDay) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AtomicLong failAt = new AtomicLong(-1);
        TeeAuditSink tee = new TeeAuditSink(lab.sink, event -> {
            if (event.sequence() == failAt.get()) {
                throw new IllegalStateException("projection failed");
            }
        });
        AuditRecorder w1 = lab.writerOn(tee, "w1");
        AuditRecorder w2 = lab.writer("w2");
        lab.record(w1, at(day, 1), at(day, 2), at(day, 3));
        lab.record(w2, at(day, 4));
        failAt.set(4);
        assertThatThrownBy(() -> lab.record(w1, at(nextDay, 1))).isInstanceOf(RuntimeException.class);
        w1.close();
        lab.record(w2, at(retainedDay(0), 5));
        for (int i = 1; i < 30; i++) {
            lab.record(lab.writer("n" + i), at(retainedDay(i), 1));
        }
        lab.close();
        return lab;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRolledBackRecordInTheSameDayLeavesAShutdownBelowTheLastHashedRecordAndStillPurges(boolean keyed)
            throws IOException {
        Lab lab = rolledBackLab(keyed, "2026-03-01", "2026-03-01");
        AuditRetention r = lab.retention();
        List<Path> reads = counting(r);

        Outcome outcome = lab.purge(r);

        outcome.succeeded();
        assertThat(lab.names()).doesNotContain("audit-2026-03-01.log");
        assertThat(retainedReads(reads)).containsExactly("audit-" + retainedDay(0) + ".log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRolledBackRecordAcrossMidnightIntoAnExpiredDayStillPurgesBothDays(boolean keyed) throws IOException {
        Lab lab = rolledBackLab(keyed, "2026-03-01", "2026-03-02");

        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).doesNotContain("audit-2026-03-01.log", "audit-2026-03-02.log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRolledBackRecordAcrossMidnightIntoARetainedDayStillPurgesTheFirstDay(boolean keyed) throws IOException {
        Lab lab = rolledBackLab(keyed, "2026-03-09", "2026-03-10");

        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).doesNotContain("audit-2026-03-09.log").contains("audit-2026-03-10.log");
    }

    // -- a writer spanning D1 and later days closes cleanly -------------------------------------------

    private Lab spanLab(boolean keyed, String[][] daysAndRecords) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.closingWriter("w");
        int hour = 1;
        for (String[] dayAndCount : daysAndRecords) {
            for (int i = 0; i < Integer.parseInt(dayAndCount[1]); i++) {
                lab.record(w, at(dayAndCount[0], hour));
            }
        }
        w.close();
        return lab;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSpanningWriterWhoseSecondDayIsRetainedPurgesTheFirst(boolean keyed) throws IOException {
        Lab lab = spanLab(keyed, new String[][]{{"2026-03-09", "5"}, {"2026-03-10", "5"}});
        lab.close();

        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-03-10.log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSpanningWriterWhoseSecondDayHasAlsoExpiredPurgesBothDays(boolean keyed) throws IOException {
        Lab lab = spanLab(keyed, new String[][]{{"2026-03-01", "5"}, {"2026-03-02", "5"}});
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();

        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-09-09.log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSpanningWriterIsReadUpToItsShutdownRecordAndNoFurther(boolean keyed) throws IOException {
        Lab lab = spanLab(keyed, new String[][]{{"2026-03-09", "5"}, {"2026-03-10", "3"}, {"2026-03-11", "2"}});
        for (int i = 1; i <= 30; i++) {
            lab.record(lab.writer("q" + i), at(LocalDate.of(2026, 3, 11).plusDays(i).toString(), 1));
        }
        lab.close();
        AuditRetention r = lab.retention();
        List<Path> reads = counting(r);

        Outcome outcome = lab.purge(r);

        outcome.succeeded();
        assertThat(lab.names()).doesNotContain("audit-2026-03-09.log");
        assertThat(retainedReads(reads)).containsExactly("audit-2026-03-10.log", "audit-2026-03-11.log");
    }

    // -- a partly deleted day after a clean shutdown --------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPartlyDeletedDayAfterACleanShutdownIsFinishedFromItsAnchor(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed, 10);
        AuditRecorder w = lab.closingWriter("w1");
        for (int i = 1; i <= 10; i++) {
            lab.record(w, at("2026-03-01", i));
        }
        w.close();
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        AuditRetention first = lab.retention();
        first.deleter = segment -> {
            if (segment.getFileName().toString().equals("audit-2026-03-01.log")) {
                throw new IOException("injected");
            }
            Files.delete(segment);
        };

        Outcome failed = lab.purge(first);

        assertThat(failed.thrown()).hasMessageContaining("AUDIT_RETENTION_DELETE_FAILED");
        assertThat(lab.names()).contains("audit-2026-03-01.log").doesNotContain("audit-2026-03-01.0001.log");
        assertThat(lab.anchors()).singleElement().satisfies(a -> assertThat(a.sequence()).isEqualTo(10));

        Outcome second = lab.purge(lab.retention());

        second.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-09-09.log");
    }

    // -- a deleted tail after a linking next record ---------------------------------------------------

    /** w: 1 to 3 on 03-09 (expired), 4 to 6 on 03-10 (retained), then a clean close with SHUTDOWN at 6. */
    private List<AuditEvent> tailLab(Lab lab) throws IOException {
        AuditRecorder w = lab.closingWriter("w");
        List<AuditEvent> all = new ArrayList<>(lab.record(w, at("2026-03-09", 1), at("2026-03-09", 2),
                at("2026-03-09", 3)));
        all.addAll(lab.record(w, at("2026-03-10", 1), at("2026-03-10", 2), at("2026-03-10", 3)));
        w.close();
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        return all;
    }

    private static void refusedByShutdown(Outcome outcome, String segment) {
        outcome.refused("SHUTDOWN checkpoint at sequence 6");
        assertThat(outcome.thrown().getMessage()).contains("records in " + segment + ";")
                .contains("dataprism.audit.checkpoint.file-path");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTailCutCleanlyFromTheRetainedDayIsCaughtThroughTheShutdown(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        List<AuditEvent> all = tailLab(lab);
        lab.write("audit-2026-03-10.log", AuditRecordFormat.serialize(all.get(3)) + "\n");

        Outcome outcome = lab.purge(lab.retention());

        refusedByShutdown(outcome, "audit-2026-03-09.log");
        assertThat(lab.names()).contains("audit-2026-03-09.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aShutdownRecordWithTheWrongHashIsRefusedInTheSegmentHoldingIt(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        List<AuditEvent> all = tailLab(lab);
        String wrong = AuditRecordFormat.serialize(all.get(5)).replace(all.get(5).eventHash(), HASH_X);
        lab.write("audit-2026-03-10.log", AuditRecordFormat.serialize(all.get(3)) + "\n"
                + AuditRecordFormat.serialize(all.get(4)) + "\n" + wrong + "\n");

        Outcome outcome = lab.purge(lab.retention());

        refusedByShutdown(outcome, "audit-2026-03-10.log");
        assertThat(lab.names()).contains("audit-2026-03-09.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCarriageReturnCopyOfTheShutdownRecordIsNotAcceptedInItsPlace(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        List<AuditEvent> all = tailLab(lab);
        lab.write("audit-2026-03-10.log", AuditRecordFormat.serialize(all.get(3)) + "\n"
                + AuditRecordFormat.serialize(all.get(4)) + "\n" + AuditRecordFormat.serialize(all.get(5)) + "\r\n");

        Outcome outcome = lab.purge(lab.retention());

        refusedByShutdown(outcome, "audit-2026-03-10.log");
        assertThat(lab.names()).contains("audit-2026-03-09.log");
        assertThat(lab.anchors()).isEmpty();
    }

    // -- two SHUTDOWN checkpoints for one instance ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeRecordCloseLeavesTwoShutdownsAndThePurgeUsesTheHighest(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.closingWriter("w");
        lab.record(w, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        w.close();
        lab.record(w, at("2026-03-01", 4));
        w.close();
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();

        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-09-09.log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTailCutBelowTheHighestShutdownIsRefusedEvenThoughTheLowerOneWouldPass(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.closingWriter("w");
        List<AuditEvent> d1 = new ArrayList<>(lab.record(w, at("2026-03-01", 1), at("2026-03-01", 2),
                at("2026-03-01", 3)));
        w.close();
        d1.addAll(lab.record(w, at("2026-03-01", 4), at("2026-03-01", 5)));
        w.close();
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            kept.append(AuditRecordFormat.serialize(d1.get(i))).append('\n');
        }
        lab.write("audit-2026-03-01.log", kept.toString());

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("SHUTDOWN checkpoint at sequence 5");
        assertThat(lab.names()).contains("audit-2026-03-01.log");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoShutdownsAtTheSameHighestSequenceWithDifferentHeadsAreRefused(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.closingWriter("w");
        lab.record(w, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        w.close();
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.store.extraShutdowns.add(new AuditCheckpoint(AuditCheckpoint.Kind.SHUTDOWN, w.instanceId(), 3, HASH_X,
                Instant.parse("2026-03-01T05:00:00Z")));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("SHUTDOWN checkpoint at sequence 3");
        assertThat(lab.names()).contains("audit-2026-03-01.log");
    }

    // -- a SHUTDOWN that the records contradict -------------------------------------------------------

    /** w1 closes with SHUTDOWN at 3 on 03-09; the unclosed u writes on 03-09 and its next record on 03-10. */
    private AuditRecorder contradictionLab(Lab lab) throws IOException {
        AuditRecorder w1 = lab.closingWriter("w1");
        lab.record(w1, at("2026-03-09", 1), at("2026-03-09", 2), at("2026-03-09", 3));
        w1.close();
        return w1;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aForgedRecordAboveTheShutdownInTheFirstRetainedSegmentReadIsRefusedAsABrokenLink(boolean keyed)
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w1 = contradictionLab(lab);
        AuditRecorder u = lab.writer("u");
        lab.record(u, at("2026-03-09", 4));
        AuditEvent forged = lab.record(w1, at("2026-03-10", 1));
        lab.record(u, at("2026-03-10", 2));
        lab.close();
        lab.write("audit-2026-03-10.log", lab.read("audit-2026-03-10.log")
                .replace(forged.previousHash(), HASH_X));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("does not chain");
        assertThat(lab.names()).contains("audit-2026-03-09.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aForgedRecordAboveTheShutdownInALaterExpiredDayIsRefusedAsABrokenLink(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w1 = lab.closingWriter("w1");
        lab.record(w1, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        w1.close();
        AuditEvent forged = lab.record(w1, at("2026-03-02", 1));
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.write("audit-2026-03-02.log", lab.read("audit-2026-03-02.log").replace(forged.previousHash(), HASH_X));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("does not chain");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-2026-03-02.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aShutdownWithAnotherHeadAtTheSameSequenceIsRefused(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w1 = lab.writer("w1");
        lab.record(w1, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.store.extraShutdowns.add(new AuditCheckpoint(AuditCheckpoint.Kind.SHUTDOWN, w1.instanceId(), 3, HASH_X,
                Instant.parse("2026-03-01T05:00:00Z")));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("SHUTDOWN checkpoint at sequence 3");
        assertThat(lab.names()).contains("audit-2026-03-01.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aShutdownBeyondTheLastHashedSequenceWithNoLaterRecordIsRefused(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w1 = lab.closingWriter("w1");
        List<AuditEvent> d1 = lab.record(w1, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3));
        w1.close();
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.write("audit-2026-03-01.log", AuditRecordFormat.serialize(d1.get(0)) + "\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("SHUTDOWN checkpoint at sequence 3");
        assertThat(lab.names()).contains("audit-2026-03-01.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aHandWrittenShutdownBelowAGenuineNextRecordThatDoesNotLinkFromItsHeadIsRefused(boolean keyed)
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w1 = lab.writer("w1");
        lab.record(w1, at("2026-03-01", 1), at("2026-03-01", 2), at("2026-03-01", 3), at("2026-03-01", 4));
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.store.extraShutdowns.add(new AuditCheckpoint(AuditCheckpoint.Kind.SHUTDOWN, w1.instanceId(), 3, HASH_X,
                Instant.parse("2026-03-01T05:00:00Z")));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("SHUTDOWN checkpoint at sequence 3");
        assertThat(lab.names()).contains("audit-2026-03-01.log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aForgedRecordBeyondTheChainInALaterSegmentReadForAnotherWriterIsRefused(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w1 = lab.closingWriter("w1");
        AuditRecorder u = lab.writer("u");
        lab.record(w1, at("2026-03-09", 1), at("2026-03-09", 2), at("2026-03-09", 3), at("2026-03-09", 4),
                at("2026-03-09", 5));
        lab.record(u, at("2026-03-09", 6));
        lab.record(w1, at("2026-03-10", 1));
        w1.close();
        lab.record(w1, at("2026-03-12", 1), at("2026-03-12", 2));
        lab.record(u, at("2026-03-12", 3));
        lab.close();
        // Seq 7 is removed, so seq 8 does not chain from the SHUTDOWN's head.
        String file = lab.read("audit-2026-03-12.log");
        String[] lines = file.split("\n");
        lab.write("audit-2026-03-12.log", lines[1] + "\n" + lines[2] + "\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("SHUTDOWN checkpoint at sequence 6");
        assertThat(lab.names()).contains("audit-2026-03-09.log");
        assertThat(lab.anchors()).isEmpty();
    }

    // -- a rewritten, re-chained day whose next record is in another expired day ----------------------

    private static String rehash(Lab lab, String line) {
        AuditEvent e = AuditRecordFormat.parse(line);
        String hash = lab.keyed ? AuditEventHash.compute(e, lab.key) : AuditEventHash.compute(e);
        return line.replace(e.eventHash(), hash);
    }

    /**
     * w closes with SHUTDOWN at 1 on 03-01 and writes record 2 on {@code nextDay}. An attacker then rewrites
     * record 1 and recomputes the chain, so the joined expiring days verify; only the checkpoint disagrees.
     */
    private Lab rewrittenLab(boolean keyed, String nextDay) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.closingWriter("w");
        AuditEvent e1 = lab.record(w, at("2026-03-01", 1));
        w.close();
        lab.record(lab.writer("mid"), at("2026-03-02", 2));
        AuditEvent e2 = lab.record(w, at(nextDay, 3));
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        String forged1 = rehash(lab, AuditRecordFormat.serialize(e1).replace("ALLOW", "DENY"));
        String newHash1 = AuditRecordFormat.parse(forged1).eventHash();
        String forged2 = rehash(lab, AuditRecordFormat.serialize(e2).replace(e1.eventHash(), newHash1));
        lab.write("audit-2026-03-01.log", forged1 + "\n");
        String nextFile = "audit-" + nextDay + ".log";
        String rest = lab.read(nextFile).replace(AuditRecordFormat.serialize(e2), forged2);
        lab.write(nextFile, rest);
        assertThat(AuditChainVerifier.verifySegments(
                List.of(lab.file("audit-2026-03-01.log"), lab.file(nextFile)), new ArrayList<>(), lab.keys, false)
                .hasBreak()).isFalse();
        return lab;
    }

    @ParameterizedTest
    @CsvSource({"false,2026-03-02", "true,2026-03-02", "false,2026-03-03", "true,2026-03-03"})
    void aRewrittenAndRechainedDayContradictingItsShutdownIsKeptWhateverExpiredDayHoldsTheNextRecord(
            boolean keyed, String nextDay) throws IOException {
        Lab lab = rewrittenLab(keyed, nextDay);

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("SHUTDOWN checkpoint at sequence 1");
        assertThat(outcome.thrown().getMessage()).contains("records in audit-2026-03-01.log;");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-" + nextDay + ".log");
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRewrittenAndRechainedPartlyDeletedDayContradictingItsShutdownIsNotFinished(boolean keyed)
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed, 10);
        AuditRecorder w = lab.closingWriter("w");
        AuditEvent e1 = lab.record(w, at("2026-03-01", 1));
        w.close();
        AuditEvent e2 = lab.record(w, at("2026-03-01", 2));
        lab.record(w, at("2026-03-01", 3));
        lab.record(w, at("2026-03-02", 1));
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        AuditRetention first = lab.retention();
        first.deleter = segment -> {
            if (segment.getFileName().toString().equals("audit-2026-03-01.0001.log")) {
                throw new IOException("injected");
            }
            Files.delete(segment);
        };
        Outcome failed = lab.purge(first);
        assertThat(failed.thrown()).hasMessageContaining("AUDIT_RETENTION_DELETE_FAILED");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log")
                .doesNotContain("audit-2026-03-01.0002.log");
        assertThat(lab.anchors()).extracting(AuditCheckpoint::sequence).containsExactly(3L, 4L);
        String forged1 = rehash(lab, AuditRecordFormat.serialize(e1).replace("ALLOW", "DENY"));
        String newHash1 = AuditRecordFormat.parse(forged1).eventHash();
        String forged2 = rehash(lab, AuditRecordFormat.serialize(e2).replace(e1.eventHash(), newHash1));
        lab.write("audit-2026-03-01.log", forged1 + "\n");
        lab.write("audit-2026-03-01.0001.log", forged2 + "\n");

        Outcome second = lab.purge(lab.retention());

        second.refused("SHUTDOWN checkpoint at sequence 1");
        assertThat(lab.names()).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log");
        assertThat(lab.anchors()).hasSize(2);
    }
}
