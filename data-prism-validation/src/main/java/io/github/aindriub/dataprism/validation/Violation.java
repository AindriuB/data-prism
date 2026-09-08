package io.github.aindriub.dataprism.validation;

/**
 * One reason a response was refused.
 *
 * <p>Carries where, what kind, and how it was found, never the value that
 * triggered it. A violation record is written to logs and audit, so putting the
 * detected value in it would leak exactly the data the check just caught. See
 * docs/pack.md §48.
 *
 * @param classification what the detected value is, where the check knows —
 *                       null for a check that compares against source values
 *                       and so has no classification of its own
 */
public record Violation(String path, String code, String detectionMethod, String classification) {

    public Violation(String path, String code, String detectionMethod) {
        this(path, code, detectionMethod, null);
    }
}
