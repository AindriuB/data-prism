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
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The segment date an anchor carries, and the verifier's refusal of an anchor too recent to be a purge's. */
class AuditRetentionAnchorDateTest {

    @TempDir
    Path tempDir;

    private Path dir;
    private Path checkpoints;
    private List<AuditEvent> events;

    private void writeChain(String... instants) throws IOException {
        dir = tempDir.resolve("seg");
        Instant[] now = new Instant[1];
        Clock mutable = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        events = new ArrayList<>();
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            AuditRecorder recorder = new AuditRecorder(sink, mutable, "w1");
            for (String instant : instants) {
                now[0] = Instant.parse(instant);
                events.add(recorder.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                        Set.of("a:ANSWERED"), Set.of(), "corr"));
            }
        }
        checkpoints = tempDir.resolve("cp.jsonl");
    }

    private void anchor(int index, String segmentDate, String recordedAt) throws IOException {
        AuditEvent e = events.get(index);
        Files.writeString(checkpoints, new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, e.instanceId(),
                e.sequence(), e.eventHash(), Instant.parse(recordedAt),
                segmentDate == null ? null : LocalDate.parse(segmentDate)).toJsonLine() + "\n");
    }

    private int cli(ByteArrayOutputStream out, String... args) {
        return AuditChainVerifierCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void theSegmentDateRoundTripsAndIsOmittedWhenAbsent() {
        AuditCheckpoint dated = new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, "w", 4, "h",
                Instant.parse("2026-09-10T00:00:00Z"), LocalDate.parse("2026-03-01"));
        assertThat(dated.toJsonLine()).contains("\"segmentDate\":\"2026-03-01\"");
        assertThat(AuditCheckpoint.fromJsonLine(dated.toJsonLine())).isEqualTo(dated);

        AuditCheckpoint plain = new AuditCheckpoint(AuditCheckpoint.Kind.BOOT, "w", 0, "h",
                Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(plain.toJsonLine()).doesNotContain("segmentDate");
    }

    @Test
    void aCheckpointLineWrittenBeforeTheFieldExistedStillParses() {
        String legacy = "{\"kind\":\"RETENTION_ANCHOR\",\"instanceId\":\"w\",\"sequence\":2,\"headHash\":\"h\","
                + "\"recordedAt\":\"2026-09-10T00:00:00Z\"}";

        AuditCheckpoint parsed = AuditCheckpoint.fromJsonLine(legacy);

        assertThat(parsed.segmentDate()).isNull();
        assertThat(parsed.sequence()).isEqualTo(2);
    }

    @Test
    void purgeWritesTheSegmentDateIntoEachAnchor() throws IOException {
        writeChain("2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-09-09T01:00:00Z");
        List<AuditCheckpoint> written = new ArrayList<>();

        new AuditRetention(dir, Period.ofMonths(6), written::add,
                Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC)).purge();

        assertThat(written).extracting(AuditCheckpoint::segmentDate)
                .containsExactly(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-02"));
    }

    @Test
    void anAnchorWhoseSegmentIsOldEnoughIsAccepted() throws IOException {
        writeChain("2026-03-01T01:00:00Z", "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve("audit-2026-03-01.jsonl"));
        anchor(0, "2026-03-01", "2026-09-10T00:00:00Z");

        assertThat(cli(new ByteArrayOutputStream(), dir.toString(), "--checkpoints", checkpoints.toString()))
                .isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
    }

    @Test
    void anAnchorOverARecentSegmentIsRejectedNamingTheWriter() throws IOException {
        writeChain("2026-09-07T01:00:00Z", "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve("audit-2026-09-07.jsonl"));
        anchor(0, "2026-09-07", "2026-09-10T00:00:00Z");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = cli(out, dir.toString(), "--checkpoints", checkpoints.toString());

        assertThat(code).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RETENTION_ANCHOR_REJECTED")
                .contains(events.get(0).instanceId());
    }

    @Test
    void anAnchorWithNoSegmentDateCoversNothing() throws IOException {
        writeChain("2026-03-01T01:00:00Z", "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve("audit-2026-03-01.jsonl"));
        anchor(0, null, "2026-09-10T00:00:00Z");

        assertThat(cli(new ByteArrayOutputStream(), dir.toString(), "--checkpoints", checkpoints.toString()))
                .isNotEqualTo(AuditChainVerifierCli.EXIT_INTACT);
    }

    @Test
    void minRetentionLetsAnOverrideDeploymentVerifyItsOwnShorterPurge() throws IOException {
        writeChain("2026-09-01T01:00:00Z", "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve("audit-2026-09-01.jsonl"));
        anchor(0, "2026-09-01", "2026-09-10T00:00:00Z");

        assertThat(cli(new ByteArrayOutputStream(), dir.toString(), "--checkpoints", checkpoints.toString()))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(cli(new ByteArrayOutputStream(), dir.toString(), "--checkpoints", checkpoints.toString(),
                "--min-retention", "P7D")).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        assertThat(cli(new ByteArrayOutputStream(), dir.toString(), "--min-retention", "nonsense"))
                .isEqualTo(AuditChainVerifierCli.EXIT_UNREADABLE_INPUT);
    }

    @Test
    void theRunTimeGuardRefusesACutoffNewerThanSixCalendarMonths() {
        LocalDate today = LocalDate.parse("2026-12-31");
        assertThatThrownBy(() -> AuditRetention.cutoff(today, Period.ofDays(181), false))
                .isInstanceOf(AuditRetention.RetentionException.class)
                .hasMessageContaining("AUDIT_RETENTION_BELOW_MINIMUM");
        assertThat(AuditRetention.cutoff(today, Period.ofDays(181), true)).isEqualTo(LocalDate.parse("2026-07-03"));
        assertThat(AuditRetention.cutoff(today, Period.ofMonths(6), false)).isEqualTo(LocalDate.parse("2026-06-30"));
    }
}
