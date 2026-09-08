package io.github.aindriub.dataprism.validation;

import java.util.List;

public record ValidationResult(boolean valid, List<Violation> violations) {

    public ValidationResult {
        violations = List.copyOf(violations);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failed(List<Violation> violations) {
        return new ValidationResult(false, violations);
    }
}
