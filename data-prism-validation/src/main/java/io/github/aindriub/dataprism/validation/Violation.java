package io.github.aindriub.dataprism.validation;

/**
 * One reason a response was refused.
 *
 * <p>Carries where and what kind, never the value that triggered it. A violation
 * record is written to logs and audit, so putting the detected value in it would
 * leak exactly the data the check just caught. See docs/pack.md §48.
 */
public record Violation(String path, String code, String detectionMethod) {
}
