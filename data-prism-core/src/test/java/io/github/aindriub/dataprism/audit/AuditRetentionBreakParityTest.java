package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retention must never purge past an anomaly that the verifier counts as a break. */
class AuditRetentionBreakParityTest {

    private static final Instant AT = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private static String seg(String date) {
        return SegmentedFileAuditSink.segmentName(LocalDate.parse(date));
    }

    private static AuditEvent chained(long seq, String prev, int version) {
        String external = version >= 3 ? "ext-" + seq : "";
        AuditEvent draft = new AuditEvent("id-" + seq, AT, "p", "c", "t", "e", "s", "f", "d", "sc",
                "pu", "ca", "ALLOW", Set.of(), Set.of(), "co", "w/1", seq, prev, "", version, Map.of(), "", "",
                external);
        return new AuditEvent("id-" + seq, AT, "p", "c", "t", "e", "s", "f", "d", "sc", "pu", "ca",
                "ALLOW", Set.of(), Set.of(), "co", "w/1", seq, prev, AuditEventHash.compute(draft), version,
                Map.of(), "", "", external);
    }

    /** One record per segment, dated 2026-03-01.., then a recent segment; the versions are per record. */
    private Path writeSegments(int... versions) throws IOException {
        Path dir = tempDir.resolve("seg");
        Files.createDirectories(dir);
        String prev = "0".repeat(64);
        for (int i = 0; i < versions.length; i++) {
            AuditEvent e = chained(i + 1, prev, versions[i]);
            prev = e.eventHash();
            Files.writeString(dir.resolve(seg("2026-03-0" + (i + 1))), AuditRecordFormat.serialize(e) + "\n");
        }
        return dir;
    }

    private void purgeExpectingRefusal(Path dir) throws IOException {
        Path cp = tempDir.resolve("cp.jsonl");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(cp, tempDir.resolve("unused.log"))) {
            AuditRetention retention = new AuditRetention(dir, Period.ofMonths(6), sink,
                    Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC));
            assertThatThrownBy(retention::purge)
                    .isInstanceOf(AuditRetention.RetentionException.class)
                    .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");
        }
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void purgeStopsAtAndKeepsASegmentWithAFieldCountMismatch() throws IOException {
        Path dir = writeSegments(2, 2, 2); // a v2 line with an appended field: 25 fields under a v2 header
        Path tampered = dir.resolve(seg("2026-03-02"));
        Files.writeString(tampered, Files.readString(tampered).replace("\n", "") + "\u001fforged-id\n");
        assertThat(AuditChainVerifier.verify(dir).hasBreak()).isTrue();

        purgeExpectingRefusal(dir);

        assertThat(names(dir)).containsExactly(seg("2026-03-02"), seg("2026-03-03"));
    }

    @Test
    void purgeStopsAtAndKeepsASegmentWithAVersionRegression() throws IOException {
        Path dir = writeSegments(3, 2, 2);
        assertThat(AuditChainVerifier.verify(dir).hasBreak()).isTrue();

        purgeExpectingRefusal(dir);

        assertThat(names(dir)).containsExactly(seg("2026-03-02"), seg("2026-03-03"));
    }
}
