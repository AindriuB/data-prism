package io.github.aindriub.dataprism.validation;

import io.github.aindriub.dataprism.annotations.DataClassification;

/**
 * One place a detector recognised something sensitive.
 *
 * <p>Where, what kind, and how it was recognised — never the text that matched.
 * A match becomes a {@link Violation}, which is written to logs and audit, so a
 * match carrying the value would publish exactly the data the scan just caught.
 *
 * <p>{@code detectionMethod} distinguishes a shape match from one whose check
 * digits also validated ({@code iban-shape} against {@code iban-mod97}), which
 * is the difference between "this looks like an account number" and "this is
 * one". Both refuse the response; only the reason recorded differs.
 */
public record SensitiveMatch(String path, DataClassification classification, String detectionMethod) {
}
