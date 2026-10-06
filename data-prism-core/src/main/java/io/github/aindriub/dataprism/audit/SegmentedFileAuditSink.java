package io.github.aindriub.dataprism.audit;

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
 * that is what lets {@link AuditRetention} delete a whole expired day without
 * rewriting any file that survives.
 *
 * <p>The segment date never goes backwards within one sink: if the clock steps back
 * across UTC midnight, later events stay in the current (later-dated) segment.
 *
 * <p>Each segment is written by a {@link FileAuditSink}, so the fsync-before-
 * return and poisoning discipline is that class's, unchanged. On top of it,
 * this sink poisons as a whole: once any segment's write has failed, every
 * later {@link #record(AuditEvent)} throws, whichever day it belongs to.
 * Segment files are never truncated or rewritten by this class.
 */
public final class SegmentedFileAuditSink implements AuditSink, Closeable {

    private static final String PREFIX = "audit-";
    private static final String SUFFIX = ".log";
    private static final Pattern SEGMENT_NAME = Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})\\.log");

    /** Opens the channel for one segment; replaceable in tests to inject write failures. */
    @FunctionalInterface
    interface ChannelOpener {
        FileChannel open(Path segment) throws IOException;
    }

    private final Path directory;
    private final ChannelOpener opener;
    private FileAuditSink current;
    private LocalDate currentDate;
    private volatile RuntimeException poisonedBy;

    public SegmentedFileAuditSink(Path directory) {
        this(directory, segment -> FileChannel.open(segment, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND));
    }

    SegmentedFileAuditSink(Path directory, ChannelOpener opener) {
        this.directory = directory;
        this.opener = opener;
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

    /** The date a segment file name carries, or {@code null} if the name is not a segment's. */
    static LocalDate segmentDate(Path file) {
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
                switchTo(date);
            }
            current.record(event);
        } catch (RuntimeException e) {
            poisonedBy = e;
            throw e;
        }
    }

    private void switchTo(LocalDate date) {
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                current = null;
            }
        }
        Path segment = directory.resolve(segmentName(date));
        try {
            current = new FileAuditSink(segment, opener.open(segment));
        } catch (IOException e) {
            throw new FileAuditSink.OpenFailedException("AUDIT_SINK_OPEN_FAILED", segment, e);
        }
        currentDate = date;
    }

    @Override
    public synchronized void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
