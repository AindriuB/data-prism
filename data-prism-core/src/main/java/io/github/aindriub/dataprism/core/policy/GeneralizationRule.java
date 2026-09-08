package io.github.aindriub.dataprism.core.policy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * How a value is coarsened when the action is {@code GENERALIZE}.
 *
 * <p>Generalisation is the only action that keeps real information about the
 * value, which is what makes it useful — a model can reason about an amount band
 * or a year of birth in a way it cannot about {@code [REDACTED]} — and also what
 * makes it the action most easily misused. Two things follow.
 *
 * <p>The bands are policy, not a platform default. What counts as coarse enough
 * depends on the population: a band of 0–1,000,000 discloses nothing about a
 * retail customer and identifies a company outright. There is deliberately no
 * shipped default, and a namespace with no rule refuses rather than guessing.
 *
 * <p>Repeated reads narrow the value. Nothing here prevents a caller asking about
 * one subject under several profiles, or asking about a population and
 * subtracting; generalisation resists a single look, not a determined series of
 * them. Rate limiting per subject per scope is the mitigation and belongs with
 * the request limits in S5.
 */
public record GeneralizationRule(Kind kind, List<BigDecimal> bounds, String unit, Precision precision) {

    public enum Kind {
        /** Replace a number with the band it falls in. */
        NUMERIC_BAND,
        /** Replace a date or timestamp with a coarser one. */
        DATE_TRUNCATION
    }

    public enum Precision {
        YEAR,
        MONTH
    }

    public GeneralizationRule {
        Objects.requireNonNull(kind, "kind");
        bounds = bounds == null ? List.of() : List.copyOf(bounds);

        if (kind == Kind.NUMERIC_BAND) {
            if (bounds.size() < 2) {
                throw new IllegalArgumentException(
                        "a numeric band rule needs at least two bounds");
            }
            for (int i = 1; i < bounds.size(); i++) {
                if (bounds.get(i).compareTo(bounds.get(i - 1)) <= 0) {
                    // Out-of-order bounds would silently produce bands that
                    // overlap or invert, and the output would look plausible.
                    throw new IllegalArgumentException(
                            "band bounds must ascend: " + bounds);
                }
            }
        } else if (precision == null) {
            throw new IllegalArgumentException("a date truncation rule needs a precision");
        }
    }

    public static GeneralizationRule bands(List<BigDecimal> bounds, String unit) {
        return new GeneralizationRule(Kind.NUMERIC_BAND, bounds, unit, null);
    }

    public static GeneralizationRule dates(Precision precision) {
        return new GeneralizationRule(Kind.DATE_TRUNCATION, List.of(), null, precision);
    }
}
