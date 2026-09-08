package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.core.PrivacyContext;

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
 * <p>What it does not catch is a sensitive value that was never in a classified
 * field — a national identifier sitting in a free-text note, say. Pattern
 * detection for that is S4, along with the scope-aware allowlist that stops
 * those patterns rejecting the platform's own synthetic values.
 */
public final class RawValueLeakValidator implements LlmResponseValidator {

    @Override
    public ValidationResult validate(JsonNode response, Set<String> prohibited, PrivacyContext context) {
        List<Violation> violations = new ArrayList<>();
        walk(response, "$", prohibited, violations);
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
        if (text != null && !text.isBlank() && prohibited.contains(text)) {
            out.add(new Violation(path, "RAW_SOURCE_VALUE", "exact-match"));
        }
    }
}
