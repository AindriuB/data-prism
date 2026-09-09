package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PrivacyContext;

import java.util.Objects;

/** The pair every downstream stage needs: the scope, and who is inside it. */
public record PrivacySession(PrivacyContext privacyContext, InvestigationContext investigationContext) {

    public PrivacySession {
        Objects.requireNonNull(privacyContext, "privacyContext");
        Objects.requireNonNull(investigationContext, "investigationContext");
    }
}
