package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.format.AuditFieldMapping;
import io.github.aindriub.dataprism.audit.format.AuditJsonRenderer;
import io.github.aindriub.dataprism.audit.format.AuditJsonTestSupport;
import io.github.aindriub.dataprism.audit.format.AuditRouting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.aindriub.dataprism.audit.format.AuditJsonTestSupport.invert;
import static io.github.aindriub.dataprism.audit.format.AuditJsonTestSupport.parseObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentedJsonAuditSinkTest {

    private static final AuditFieldMapping MAPPING = AuditFieldMapping.ecs();
    private static final AuditRouting ROUTING = new AuditRouting("dataprism.audit", "logs", "dataprism.audit", "prod");

    @TempDir
    Path tempDir;

    private static AuditEvent at(String timestamp, long sequence) {
        AuditEvent g = AuditJsonTestSupport.generate(new Random(sequence), sequence);
        return new AuditEvent(g.eventId(), Instant.parse(timestamp), g.principalId(), g.clientId(), g.tool(),
                g.entityType(), g.subjectPseudonym(), g.parameterFingerprint(), g.privacyProfile(), g.scopeId(),
                g.purpose(), g.caseId(), g.policyDecision(), g.sourceSystems(), g.rejectedArguments(),
                g.correlationId(), g.instanceId(), sequence, g.previousHash(), g.eventHash(), 3,
                g.fieldDispositions(), g.approvalId(), g.approverId(), g.externalCorrelationId());
    }

    @Test
    void appendsEachEventToTheNdjsonFileOfItsUtcDate() throws IOException {
        Path dir = tempDir.resolve("json");
        AuditEvent e1 = at("2026-03-01T23:59:59Z", 1);
        AuditEvent e2 = at("2026-03-02T00:00:00Z", 2);
        AuditEvent e3 = at("2026-03-02T10:00:00Z", 3);
        try (SegmentedJsonAuditSink sink = new SegmentedJsonAuditSink(dir, MAPPING, ROUTING)) {
            sink.record(e1);
            sink.record(e2);
            sink.record(e3);
        }

        assertThat(dir.toFile().list()).containsExactlyInAnyOrder("audit-2026-03-01.ndjson",
                "audit-2026-03-02.ndjson");
        List<String> day2 = Files.readAllLines(dir.resolve("audit-2026-03-02.ndjson"));
        assertThat(day2).hasSize(2);
        assertThat(day2.get(0)).isEqualTo(AuditJsonRenderer.render(e2, MAPPING, ROUTING));
        assertThat(invert(parseObject(day2.get(1)), MAPPING)).isEqualTo(e3);
    }

    @Test
    void recordIsDurableWithoutTheTestClosing() throws IOException {
        Path dir = tempDir.resolve("json");
        SegmentedJsonAuditSink sink = new SegmentedJsonAuditSink(dir, MAPPING, ROUTING);
        AuditEvent e = at("2026-03-01T00:00:00Z", 1);
        sink.record(e);

        assertThat(Files.readString(dir.resolve("audit-2026-03-01.ndjson")))
                .isEqualTo(AuditJsonRenderer.render(e, MAPPING, ROUTING) + "\n");
        sink.close();
    }

    @Test
    void aRoutingPathCollidingWithAMappedPathRefusesConstruction() {
        AuditFieldMapping clash = AuditFieldMapping.canonical().withOverrides(Map.of("tool", "event.dataset"));
        assertThatThrownBy(() -> new SegmentedJsonAuditSink(tempDir.resolve("j"), clash, ROUTING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_FIELD_MAPPING_CONFLICT");
    }

    @Test
    void aShortWriteThatThrowsPoisonsTheWholeSinkIncludingOtherDays() throws IOException {
        Path dir = tempDir.resolve("json");
        Path day1 = dir.resolve("audit-2026-03-01.ndjson");
        Files.createDirectories(dir);
        FileChannel real = FileChannel.open(day1, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        ShortWriteThenFailChannel faulty = new ShortWriteThenFailChannel(real, 5);
        SegmentedJsonAuditSink sink = new SegmentedJsonAuditSink(dir, MAPPING, ROUTING, segment -> faulty);

        assertThatThrownBy(() -> sink.record(at("2026-03-01T00:00:00Z", 1)))
                .isInstanceOf(UncheckedIOException.class);
        byte[] afterFailure = Files.readAllBytes(day1);
        assertThat(afterFailure).hasSize(5);
        assertThat(new String(afterFailure, StandardCharsets.UTF_8)).doesNotContain("\n");

        assertThatThrownBy(() -> sink.record(at("2026-03-01T00:00:01Z", 2)))
                .isInstanceOf(FileAuditSink.PoisonedException.class);
        assertThatThrownBy(() -> sink.record(at("2026-03-02T00:00:00Z", 3)))
                .isInstanceOf(FileAuditSink.PoisonedException.class);
        assertThat(Files.exists(dir.resolve("audit-2026-03-02.ndjson"))).isFalse();
        assertThat(Files.readAllBytes(day1)).hasSize(5);
        faulty.close();
    }

    private static final class ShortWriteThenFailChannel extends FileChannel {

        private final FileChannel delegate;
        private final int bytesBeforeFailure;
        private final AtomicInteger writeCalls = new AtomicInteger();

        ShortWriteThenFailChannel(FileChannel delegate, int bytesBeforeFailure) {
            this.delegate = delegate;
            this.bytesBeforeFailure = bytesBeforeFailure;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (writeCalls.getAndIncrement() == 0) {
                ByteBuffer limited = src.duplicate();
                limited.limit(Math.min(src.limit(), src.position() + bytesBeforeFailure));
                int written = delegate.write(limited);
                src.position(src.position() + written);
                throw new IOException("simulated short write failure");
            }
            throw new IOException("sink should be poisoned before writing again");
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
        public int read(ByteBuffer dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
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
        public long transferTo(long position, long count, WritableByteChannel target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(ByteBuffer dst, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(ByteBuffer src, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }
    }
}
