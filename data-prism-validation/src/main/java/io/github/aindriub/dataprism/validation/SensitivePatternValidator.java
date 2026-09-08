package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses a response containing something that looks sensitive and was not
 * emitted by this scope.
 *
 * <p>The allowlist is the whole reason this can exist. {@code SYNTHESIZE}
 * produces emails, addresses and names on purpose — a pseudonym that did not
 * look like the thing it replaces would be useless to a model, and would also
 * mean a bug leaking a real email went unnoticed by shape. A detector matches
 * those synthetic values exactly as it matches real ones, so without knowing
 * what the engine produced, this validator would refuse every synthesising
 * profile's output. See docs/design-review.md §A5.
 *
 * <p>Exemption is by exact canonical value, held in a hash set, so a lookup is
 * constant time however many values a scope has emitted. It is never a substring
 * or prefix rule: the engine emits whole scalars, and a looser rule would let a
 * real value ride along inside a string that starts with a synthetic one.
 */
public final class SensitivePatternValidator implements LlmResponseValidator {

    /** The scan looked at less than the whole response, so nothing can be said about the rest. */
    public static final String SCAN_INCOMPLETE = "RESPONSE_TOO_LARGE_TO_SCAN";

    public static final String SENSITIVE_PATTERN = "SENSITIVE_PATTERN";

    private final SensitiveDataScanner scanner;

    public SensitivePatternValidator() {
        this(new SensitiveDataScanner());
    }

    public SensitivePatternValidator(SensitiveDataScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    @Override
    public ValidationResult validate(JsonNode response, Set<String> prohibited, Set<String> emitted,
                                     PrivacyContext context) {
        // Canonicalised on both sides for the same reason RawValueLeakValidator
        // canonicalises: a synthetic value that came back in a different Unicode
        // form would miss the allowlist and refuse a perfectly good response.
        Set<String> allowed = HashSet.newHashSet(emitted.size());
        emitted.forEach(value -> allowed.add(Text.canonical(value)));

        ScanReport report = scanner.scan(response, value -> allowed.contains(Text.canonical(value)));

        List<Violation> violations = new ArrayList<>();
        if (!report.complete()) {
            violations.add(new Violation("$", SCAN_INCOMPLETE, "scan-budget-exhausted", null));
        }
        for (SensitiveMatch match : report.matches()) {
            violations.add(new Violation(match.path(), SENSITIVE_PATTERN, match.detectionMethod(),
                    match.classification().name()));
        }
        return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(violations);
    }
}
