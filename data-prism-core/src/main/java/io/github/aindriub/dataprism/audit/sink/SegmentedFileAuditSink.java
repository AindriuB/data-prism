package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appends each event to {@code directory/audit-YYYY-MM-DD.log}, the date
 * being the UTC date of {@link AuditEvent#timestamp()}. One day, one file:
 * that is what lets {@link io.github.aindriub.dataprism.audit.retention.AuditRetention} delete a whole expired day without
 * rewriting any file that survives.
 *
 * <p>The segment date never goes backwards within one sink: if the clock steps back
 * across UTC midnight, later events stay in the current (later-dated) segment.
 *
 * <p>Each segment is written by a {@link FileAuditSink}, so the fsync-before-
 * return and poisoning discipline is that class's, unchanged. On top of it,
 * this sink poisons as a whole: once any segment's write has failed, every
 * later {@link #record(AuditEvent)} throws, whichever day it belongs to.
 * Segment files are never truncated or rewritten by this class. Opening a segment that
 * already ends without a newline (a process died mid-write) first terminates that torn tail
 * with {@code "\r\n"}, as {@link FileAuditSink} does; one live writer per directory is assumed.
 */
public final class SegmentedFileAuditSink implements AuditSink, Closeable {

    private static final String PREFIX = "audit-";
    private static final String SUFFIX = ".log";
    private static final Pattern SEGMENT_NAME =
            Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})(?:\\.((?!0000)\\d{4}))?\\.log");

    /**
     * Orders segment files by day, then by index within the day (the unindexed first segment before
     * {@code .0001}, {@code .0002}, ...). Names that are not segments sort last, by name.
     */
    public static final java.util.Comparator<Path> SEGMENT_ORDER = (a, b) -> {
        LocalDate da = segmentDate(a);
        LocalDate db = segmentDate(b);
        if (da == null || db == null) {
            if (da == null && db == null) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
            return da == null ? 1 : -1;
        }
        int c = da.compareTo(db);
        return c != 0 ? c : Integer.compare(segmentIndex(a), segmentIndex(b));
    };

    /** Opens the channel for one segment; replaceable in tests to inject write failures. */
    @FunctionalInterface
    interface ChannelOpener {
        FileChannel open(Path segment) throws IOException;
    }

    private final Path directory;
    private final ChannelOpener opener;
    private final String suffix;
    private final java.util.function.Function<AuditEvent, String> renderer;
    private final long maxSegmentBytes;
    private FileAuditSink current;
    private LocalDate currentDate;
    private int currentIndex;
    private long currentSize;
    private volatile RuntimeException poisonedBy;

    public SegmentedFileAuditSink(Path directory) {
        this(directory, APPEND_OPENER);
    }

    /**
     * As {@link #SegmentedFileAuditSink(Path)}, but a day's segment is capped at {@code maxSegmentBytes}:
     * the first is {@code audit-YYYY-MM-DD.log}, later ones {@code audit-YYYY-MM-DD.NNNN.log}. Before a
     * write that would take the current segment past the cap, it is closed and the next opened; a record
     * larger than the cap is written whole into a fresh segment, never split. The hash chain is unaffected.
     */
    public SegmentedFileAuditSink(Path directory, long maxSegmentBytes) {
        this(directory, APPEND_OPENER, SUFFIX, AuditRecordFormat::serialize, requirePositive(maxSegmentBytes));
    }

    private static long requirePositive(long maxSegmentBytes) {
        if (maxSegmentBytes <= 0) {
            throw new IllegalArgumentException("maxSegmentBytes must be positive: " + maxSegmentBytes);
        }
        return maxSegmentBytes;
    }

    /** Opens a segment create-and-append. */
    static final ChannelOpener APPEND_OPENER = segment -> FileChannel.open(segment, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND);

    SegmentedFileAuditSink(Path directory, ChannelOpener opener) {
        this(directory, opener, SUFFIX, AuditRecordFormat::serialize);
    }

    SegmentedFileAuditSink(Path directory, ChannelOpener opener, long maxSegmentBytes) {
        this(directory, opener, SUFFIX, AuditRecordFormat::serialize, requirePositive(maxSegmentBytes));
    }

    /**
     * Segments named {@code audit-YYYY-MM-DD<suffix>}, each line produced by {@code renderer}. Used by
     * {@link SegmentedJsonAuditSink}; the date, fsync and poisoning behaviour are this class's.
     */
    SegmentedFileAuditSink(Path directory, ChannelOpener opener, String suffix,
                           java.util.function.Function<AuditEvent, String> renderer) {
        this(directory, opener, suffix, renderer, 0);
    }

    private SegmentedFileAuditSink(Path directory, ChannelOpener opener, String suffix,
                                   java.util.function.Function<AuditEvent, String> renderer,
                                   long maxSegmentBytes) {
        this.maxSegmentBytes = maxSegmentBytes;
        this.directory = directory;
        this.opener = opener;
        this.suffix = suffix;
        this.renderer = renderer;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new FileAuditSink.OpenFailedException("AUDIT_SINK_OPEN_FAILED", directory, e);
        }
    }

    /** The segment file name for {@code date}. */
    public static String segmentName(LocalDate date) {
        return PREFIX + date + SUFFIX;
    }

    /** The segment file name for {@code date} and {@code index}: index 0 is the unindexed first segment. */
    public static String segmentName(LocalDate date, int index) {
        if (index < 0 || index > 9999) {
            throw new IllegalArgumentException("segment index out of range: " + index);
        }
        return index == 0 ? segmentName(date) : PREFIX + date + String.format(".%04d", index) + SUFFIX;
    }

    /** The date a segment file name (either form) carries, or {@code null} if the name is not a segment's. */
    public static LocalDate segmentDate(Path file) {
        Matcher m = SEGMENT_NAME.matcher(file.getFileName().toString());
        if (!m.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The index within its day: 0 for {@code audit-YYYY-MM-DD.log}, n for {@code .NNNN.log}, -1 if not a segment. */
    public static int segmentIndex(Path file) {
        if (segmentDate(file) == null) {
            return -1;
        }
        Matcher m = SEGMENT_NAME.matcher(file.getFileName().toString());
        m.matches();
        return m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
    }

    @Override
    public synchronized void record(AuditEvent event) {
        if (poisonedBy != null) {
            throw new FileAuditSink.PoisonedException("AUDIT_SINK_POISONED", directory,
                    new IOException(poisonedBy));
        }
        try {
            LocalDate date = event.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
            if (currentDate != null && date.isBefore(currentDate)) {
                // The clock stepped back across midnight. The segment date never goes backwards:
                // a writer's consecutive records must not land in an earlier file than their
                // predecessor, or a directory read in date order would see a false break.
                date = currentDate;
            }
            if (current == null || !date.equals(currentDate)) {
                switchTo(date, firstIndex(date));
            }
            String line = renderer.apply(event);
            if (maxSegmentBytes > 0) {
                long bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
                if (currentSize > 0 && currentSize + bytes > maxSegmentBytes) {
                    switchTo(currentDate, currentIndex + 1);
                }
                current.recordLine(line);
                currentSize += bytes;
                return;
            }
            current.recordLine(line);
        } catch (RuntimeException e) {
            poisonedBy = e;
            throw e;
        }
    }

    /** Without a cap, always the unindexed segment; with one, the highest index already on disk for the day. */
    private int firstIndex(LocalDate date) {
        if (maxSegmentBytes <= 0) {
            return 0;
        }
        int highest = 0;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (date.equals(segmentDate(f))) {
                    highest = Math.max(highest, segmentIndex(f));
                }
            }
        } catch (IOException e) {
            throw new FileAuditSink.OpenFailedException("AUDIT_SINK_OPEN_FAILED", directory, e);
        }
        return highest;
    }

    private void switchTo(LocalDate date, int index) {
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                current = null;
            }
        }
        if (index > 9999) {
            throw new FileAuditSink.OpenFailedException("AUDIT_SINK_OPEN_FAILED", directory,
                    new IOException("too many segments for " + date));
        }
        Path segment = directory.resolve(maxSegmentBytes > 0 ? segmentName(date, index) : PREFIX + date + suffix);
        try {
            FileChannel channel = opener.open(segment);
            try {
                FileAuditSink.terminateTornTail(segment, channel);
                currentSize = maxSegmentBytes > 0 ? channel.size() : 0;
            } catch (IOException | RuntimeException e) {
                FileAuditSink.closeQuietly(channel);
                throw e;
            }
            current = new FileAuditSink(segment, channel);
        } catch (IOException e) {
            throw new FileAuditSink.OpenFailedException("AUDIT_SINK_OPEN_FAILED", segment, e);
        }
        currentDate = date;
        currentIndex = index;
    }

    @Override
    public synchronized void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
