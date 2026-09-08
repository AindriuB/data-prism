package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.core.PrivacyContext;

import java.util.Set;

/**
 * Checks a finished response before it leaves.
 *
 * <p>Independent of the engine that produced it, on purpose. The engine deciding
 * its own output is safe is not a check; the point of this stage is to catch the
 * case where the engine is wrong, misconfigured or bypassed.
 */
public interface LlmResponseValidator {

    /**
     * @param prohibited exact values that must not appear anywhere in the
     *                   response — the raw sensitive values read from the source
     */
    ValidationResult validate(JsonNode response, Set<String> prohibited, PrivacyContext context);
}
