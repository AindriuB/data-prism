package io.github.aindriub.dataprism.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Applies a {@link GeneralizationRule} to one value.
 *
 * <p>Refuses rather than guesses. A value that does not parse as the rule expects
 * — a number where a date was configured, free text where a number was — is a
 * mismatch between the model and the profile, and emitting the value unchanged
 * or replacing it with something arbitrary would both be worse than saying so.
 */
public final class Generalizer {

    private Generalizer() {
    }

    public static String generalise(JsonNode value, GeneralizationRule rule, String path) {
        return switch (rule.kind()) {
            case NUMERIC_BAND -> band(value, rule, path);
            case DATE_TRUNCATION -> truncate(value, rule, path);
        };
    }

    private static String band(JsonNode value, GeneralizationRule rule, String path) {
        BigDecimal amount;
        try {
            amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim());
        } catch (NumberFormatException e) {
            throw new PrivacyRefusedException("GENERALIZE_TYPE_MISMATCH", path,
                    "a numeric band rule applies to this field but the value is not a number");
        }

        List<BigDecimal> bounds = rule.bounds();
        String unit = rule.unit() == null || rule.unit().isBlank() ? "" : rule.unit() + " ";

        if (amount.compareTo(bounds.get(0)) < 0) {
            return "< " + unit + bounds.get(0).toPlainString();
        }
        for (int i = 1; i < bounds.size(); i++) {
            if (amount.compareTo(bounds.get(i)) < 0) {
                return unit + bounds.get(i - 1).toPlainString()
                        + "–" + bounds.get(i).toPlainString();
            }
        }
        // Open-ended at the top on purpose: a closed top band would either be
        // unbounded in effect or would have to name the largest value seen,
        // which is itself a disclosure.
        return ">= " + unit + bounds.get(bounds.size() - 1).toPlainString();
    }

    private static String truncate(JsonNode value, GeneralizationRule rule, String path) {
        String text = value.asText();
        LocalDate date;
        try {
            // Take the date part of an ISO timestamp; anything else must be a date.
            date = LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            throw new PrivacyRefusedException("GENERALIZE_TYPE_MISMATCH", path,
                    "a date truncation rule applies to this field but the value is not an ISO date");
        }
        return switch (rule.precision()) {
            case YEAR -> String.valueOf(date.getYear());
            case MONTH -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
        };
    }
}
