package io.github.aindriub.dataprism.validation;

import java.util.List;

/**
 * What one scan found, and whether it got to look at everything.
 *
 * <p>{@code complete} is the fail-closed half. Scanning is bounded so a large
 * payload cannot make the validator the slowest part of a request, and a bound
 * that is reached silently is a hole: the response would go out reported as
 * checked. An incomplete scan is a refusal, not a warning.
 */
public record ScanReport(List<SensitiveMatch> matches, boolean complete) {

    public ScanReport {
        matches = List.copyOf(matches);
    }

    public static ScanReport complete(List<SensitiveMatch> matches) {
        return new ScanReport(matches, true);
    }

    public static ScanReport truncated(List<SensitiveMatch> matches) {
        return new ScanReport(matches, false);
    }
}
