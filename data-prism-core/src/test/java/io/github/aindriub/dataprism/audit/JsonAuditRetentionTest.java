package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonAuditRetentionTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-10T00:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private void touch(String name) throws IOException {
        Files.writeString(dir.resolve(name), "{}\n");
    }

    @Test
    void deletesNdjsonSegmentsStrictlyOlderThanRetentionAndNothingElse() throws IOException {
        // six months before 2026-09-10 is 2026-03-10
        touch("audit-2026-03-09.ndjson");
        touch("audit-2026-03-10.ndjson");
        touch("audit-2026-03-11.ndjson");
        touch("audit-2026-09-10.ndjson");
        touch("audit-2026-01-01.log");
        touch("notes.ndjson");
        touch("audit-2026-13-40.ndjson");

        List<Path> deleted = new JsonAuditRetention(dir, Period.ofMonths(6), NOW).purge();

        assertThat(deleted).extracting(p -> p.getFileName().toString()).containsExactly("audit-2026-03-09.ndjson");
        assertThat(dir.toFile().list()).containsExactlyInAnyOrder("audit-2026-03-10.ndjson",
                "audit-2026-03-11.ndjson", "audit-2026-09-10.ndjson", "audit-2026-01-01.log", "notes.ndjson",
                "audit-2026-13-40.ndjson");
    }

    @Test
    void neverDeletesTodaysSegmentEvenWithAnOverriddenZeroRetention() throws IOException {
        touch("audit-2026-09-09.ndjson");
        touch("audit-2026-09-10.ndjson");

        List<Path> deleted = new JsonAuditRetention(dir, Period.ZERO, NOW, true).purge();

        assertThat(deleted).extracting(p -> p.getFileName().toString()).containsExactly("audit-2026-09-09.ndjson");
        assertThat(dir.toFile().list()).containsExactly("audit-2026-09-10.ndjson");
    }

    @Test
    void refusesARetentionBelowSixMonthsUnlessOverridden() {
        assertThatThrownBy(() -> new JsonAuditRetention(dir, Period.ofMonths(5), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AuditRetention.BELOW_MINIMUM);
        assertThatThrownBy(() -> new JsonAuditRetention(dir, Period.ofDays(181), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AuditRetention.BELOW_MINIMUM);
        new JsonAuditRetention(dir, Period.ofMonths(5), NOW, true);
    }
}
