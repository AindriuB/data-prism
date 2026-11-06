package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;

import java.util.Optional;

/** Runs the pipeline: fetch, scrub, validate, audit. */
public interface ContextOrchestrator {

    ContextResponse buildContext(ContextRequest request, PrivacyContext privacyContext,
                                 InvestigationContext investigationContext);

    /**
     * How each field of the named source is protected, by the scrubber that scrubs it. The default describes
     * nothing, and a caller treats empty as a refusal (fail closed). Only the engine describes a source:
     * an adapter supplies no description.
     */
    default Optional<ModelDescription> describe(String sourceName, PrivacyContext context, boolean linksEnabled) {
        return Optional.empty();
    }
}
