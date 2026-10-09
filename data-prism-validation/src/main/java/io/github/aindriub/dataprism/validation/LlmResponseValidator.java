package io.github.aindriub.dataprism.validation;

import tools.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.core.model.PrivacyContext;

import java.util.Set;

/**
 * Checks a finished response before it leaves.
 *
 * <p>Independent of the engine that produced it, on purpose. The engine deciding
 * its own output is safe is not a check; the point of this stage is to catch the
 * case where the engine is wrong, misconfigured or bypassed.
 *
 * <p>The orchestrator calls a validator twice. First once per source, with that source's own
 * scrubbed object tree, so a violation withholds only that source. Then once more with the whole
 * response: a JSON array holding the returned sources' trees, in the order the response lists them,
 * so a rule that refuses a combination across sources (a name in one, a date in another) can see it.
 * Neither tree has a source name or alias as a key, and a path a validator reports must not
 * contain one either. Write a validator against any {@link JsonNode}, not an object root.
 */
public interface LlmResponseValidator {

    /**
     * @param prohibited exact values that must not appear anywhere in the
     *                   response — the raw sensitive values read from the source
     * @param emitted    values this scope generated, which a shape-based check
     *                   must not refuse: a synthesised email is an email. The
     *                   engine reports these; nothing else may add to them, and
     *                   they are never logged. See docs/design-review.md §A5
     */
    ValidationResult validate(JsonNode response, Set<String> prohibited, Set<String> emitted,
                              PrivacyContext context);
}
