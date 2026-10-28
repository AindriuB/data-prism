package io.github.aindriub.dataprism.validation;

import tools.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.engine.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** A {@link FreeTextRecogniser} recognised personal data in a string leaf. */
    public static final String FREE_TEXT_RECOGNISED = "FREE_TEXT_RECOGNISED";

    /** A {@link FreeTextRecogniser} threw or returned null, so the leaf could not be cleared. */
    public static final String FREE_TEXT_RECOGNISER_FAILED = "FREE_TEXT_RECOGNISER_FAILED";

    /** Enough to identify the problem; a response with more is refused regardless. */
    private static final int MAX_RECOGNISER_VIOLATIONS = 64;

    private final SensitiveDataScanner scanner;
    private final List<FreeTextRecogniser> recognisers;

    public SensitivePatternValidator() {
        this(new SensitiveDataScanner(), List.of());
    }

    /**
     * @param recognisers consulted in list order for every string leaf the shape scan visits, so
     *                    they share its depth and size budget. They can only add refusals.
     */
    public SensitivePatternValidator(SensitiveDataScanner scanner, List<FreeTextRecogniser> recognisers) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.recognisers = List.copyOf(Objects.requireNonNull(recognisers, "recognisers"));
    }

    @Override
    public ValidationResult validate(JsonNode response, Set<String> prohibited, Set<String> emitted,
                                     PrivacyContext context) {
        // Canonicalised on both sides for the same reason RawValueLeakValidator
        // canonicalises: a synthetic value that came back in a different Unicode
        // form would miss the allowlist and refuse a perfectly good response.
        Set<String> allowed = HashSet.newHashSet(emitted.size());
        emitted.forEach(value -> allowed.add(Text.canonical(value)));

        // The recognisers ride on the scanner's exemption hook, which it calls for every text it
        // has charged to its budget, so they see exactly the leaves the shape scan sees and an
        // exhausted budget stops both. The hook exempts only what the allowlist exempts.
        List<Violation> violations = new ArrayList<>();
        ScanReport report = scanner.scan(response, value -> {
            if (allowed.contains(Text.canonical(value))) {
                return true;
            }
            recognise(value, violations);
            return false;
        });

        if (!report.complete()) {
            violations.add(new Violation("$", SCAN_INCOMPLETE, "scan-budget-exhausted", null));
        }
        for (SensitiveMatch match : report.matches()) {
            violations.add(new Violation(match.path(), SENSITIVE_PATTERN, match.detectionMethod(),
                    match.classification().name()));
        }
        return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(violations);
    }

    private void recognise(String value, List<Violation> violations) {
        for (FreeTextRecogniser recogniser : recognisers) {
            if (violations.size() >= MAX_RECOGNISER_VIOLATIONS) {
                return;
            }
            Set<DataClassification> recognised;
            try {
                recognised = recogniser.recognise(value);
            } catch (RuntimeException | StackOverflowError e) {
                // Nothing from the exception is kept: its message may quote the value.
                violations.add(new Violation("$", FREE_TEXT_RECOGNISER_FAILED, "recogniser-failed"));
                continue;
            }
            if (recognised == null) {
                violations.add(new Violation("$", FREE_TEXT_RECOGNISER_FAILED, "recogniser-failed"));
            } else if (!recognised.isEmpty()) {
                String names = recognised.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
                violations.add(new Violation("$", FREE_TEXT_RECOGNISED, "free-text:" + names));
            }
        }
    }
}
