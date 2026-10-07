package io.github.aindriub.dataprism.audit;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Period;
import java.util.List;

/** Stub: red phase. */
public final class JsonAuditRetention {

    public JsonAuditRetention(Path directory, Period retention, Clock clock) {
        throw new UnsupportedOperationException();
    }

    public JsonAuditRetention(Path directory, Period retention, Clock clock, boolean allowBelowMinimum) {
        throw new UnsupportedOperationException();
    }

    public List<Path> purge() {
        throw new UnsupportedOperationException();
    }
}
