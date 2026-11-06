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
     * Follows one sealed link: re-checks everything a fresh call checks, reads the link's target on the holder's
     * behalf, and returns the {@code get_entity_context} envelope with exactly one entry, keyed by the target's
     * label. An invalid link is refused with {@code LINK_INVALID} whatever the reason.
     *
     * <p>The default refuses: an orchestrator that does not implement {@code follow} follows nothing.
     */
    default ContextResponse follow(FollowRequest request,
                                   PrivacyContext privacyContext, InvestigationContext investigationContext) {
        throw new UnsupportedOperationException("this orchestrator does not follow links");
    }

    /**
     * How each field of the named source is protected, by the scrubber that scrubs it. The default describes
     * nothing, and a caller treats empty as a refusal (fail closed). Only the engine describes a source:
     * an adapter supplies no description.
     */
    default Optional<ModelDescription> describe(String sourceName, PrivacyContext context, boolean linksEnabled) {
        return Optional.empty();
    }
}
