package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.ValueTokenSource;

import java.util.Objects;

/**
 * Decides what a source is called in a response.
 *
 * <p>A finding that says two systems disagree has to name them, or an
 * investigator cannot act on it. Naming them tells the model what the estate
 * looks like: which systems exist, how many, and which one is the odd one out.
 * That is not personal data, but it is reconnaissance, and the specification is
 * clear that source identifiers are not exposed by default (docs/pack.md §32,
 * docs/design-review.md §E).
 *
 * <p>So the default is a scope-local alias, derived by keyed HMAC from the source
 * name. It is stable for the life of a scope, so a finding in one call and a
 * finding in the next refer to the same system by the same alias, and it is
 * meaningless outside that scope. An operator maps aliases back through the audit
 * trail, which always carries the real name regardless of this decision.
 *
 * <p>Real names are returned only when the caller asking — not the scope, the
 * caller — holds {@link Capability#EXPOSE_SOURCE_NAMES}. That is a capability
 * check, made fresh for every call, rather than a deployment-wide switch: two
 * callers in the same scope may see two different answers.
 */
public final class SourceAliasing {

    private final ValueTokenSource tokens;

    public SourceAliasing(ValueTokenSource tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    public String nameFor(String sourceName, InvestigationContext investigationContext,
                          PrivacyContext context) {
        if (investigationContext.has(Capability.EXPOSE_SOURCE_NAMES)) {
            return sourceName;
        }
        // ORGANISATION_IDENTITY because that is what a source system is: a named
        // party whose identity is being substituted, keyed on the value so the
        // same system reads the same way throughout the scope.
        return tokens.token(sourceName, PrivacyNamespace.ORGANISATION_IDENTITY, context);
    }
}
