package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
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
 * trail.
 *
 * <p>Real names are available to a caller that holds the capability. The example
 * application turns it on, because its sources are fictional and the output is
 * meant to be read.
 */
public final class SourceAliasing {

    private final ValueTokenSource tokens;
    private final boolean exposeRealNames;

    public SourceAliasing(ValueTokenSource tokens, boolean exposeRealNames) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.exposeRealNames = exposeRealNames;
    }

    /** Aliases sources, which is the right default for anything not fictional. */
    public static SourceAliasing aliased(ValueTokenSource tokens) {
        return new SourceAliasing(tokens, false);
    }

    /** Names sources as they are. Requires the caller to hold the capability. */
    public static SourceAliasing exposed() {
        return new SourceAliasing(ValueTokenSource.unavailable(), true);
    }

    public String nameFor(String sourceName, PrivacyContext context) {
        if (exposeRealNames) {
            return sourceName;
        }
        // ORGANISATION_IDENTITY because that is what a source system is: a named
        // party whose identity is being substituted, keyed on the value so the
        // same system reads the same way throughout the scope.
        return tokens.token(sourceName, PrivacyNamespace.ORGANISATION_IDENTITY, context);
    }

    public boolean exposesRealNames() {
        return exposeRealNames;
    }
}
