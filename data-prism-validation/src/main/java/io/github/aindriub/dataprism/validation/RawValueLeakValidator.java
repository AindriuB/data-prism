package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a response that still contains a raw value from the source.
 *
 * <p>This is the S0 check, and it is deliberately the strongest one available
 * without pattern matching: it compares against what the source actually held,
 * so it cannot be fooled by a value that happens not to look sensitive. It
 * catches the engine passing a field through, an action silently doing nothing,
 * and a raw DTO being returned in place of a scrubbed tree.
 *
 * <p>Comparison is on canonical text rather than raw bytes. A source that stores
 * "Seań" and a response carrying the precomposed "Seán" are the same name,
 * and an exact byte match would let the second through while reporting the check
 * as passed -- the worst possible outcome for a check whose whole job is to be
 * the last line.
 *
 * <p>What it does not catch is a sensitive value that was never in a classified
 * field — a national identifier sitting in a free-text note, say. That is
 * {@link SensitivePatternValidator}'s job, and the two run together rather than
 * one replacing the other: this check cannot be fooled by an unusual shape, and
 * that one cannot be fooled by a value the source never held.
 */
public final class RawValueLeakValidator implements LlmResponseValidator {

    /**
     * The emitted set is ignored here. A value the engine generated is not a raw
     * source value, so it cannot be in {@code prohibited} in the first place; an
     * allowlist would only be able to hide a genuine leak.
     */
    @Override
    public ValidationResult validate(JsonNode response, Set<String> prohibited, Set<String> emitted,
                                     PrivacyContext context) {
        Set<String> canonical = prohibited.stream()
                .map(Text::canonical)
                .collect(java.util.stream.Collectors.toSet());

        List<Violation> violations = new ArrayList<>();
        walk(response, "$", canonical, violations);
        return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(violations);
    }

    private static void walk(JsonNode node, String path, Set<String> prohibited, List<Violation> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                walk(e.getValue(), path + "." + e.getKey(), prohibited, out);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", prohibited, out);
            }
            return;
        }
        String text = node.asText();
        if (text != null && !text.isBlank() && prohibited.contains(Text.canonical(text))) {
            out.add(new Violation(path, "RAW_SOURCE_VALUE", "exact-match"));
        }
    }
}
