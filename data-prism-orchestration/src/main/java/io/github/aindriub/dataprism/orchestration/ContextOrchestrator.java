package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;

/** Runs the pipeline: fetch, scrub, validate, audit. */
public interface ContextOrchestrator {

    ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                 InvestigationContext investigationContext);
}
