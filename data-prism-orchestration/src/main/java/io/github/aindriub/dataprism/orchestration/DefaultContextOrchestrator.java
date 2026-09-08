package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SourceValues;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The S0 pipeline, in order and without shortcuts.
 *
 * <p>Fetch, scrub, then validate against what the source actually held, then
 * audit, then return. The validation step is not conditional and there is no
 * path that returns a response after it fails: a refusal is an exception, so
 * there is nothing for a caller to accidentally ignore.
 *
 * <p>S0 calls its sources in sequence. Parallel fan-out with timeouts, circuit
 * breakers and bounded concurrency is S5; sequencing is fine for one stub and
 * would be wrong for a real deployment.
 */
public final class DefaultContextOrchestrator implements ContextOrchestrator {

    private final List<DataSourceAdapter<?>> adapters;
    private final ScrubbingEngine scrubber;
    private final FieldMetadataResolver resolver;
    private final LlmResponseValidator validator;
    private final SyntheticValueSource synthetics;
    private final ParameterFingerprinter fingerprinter;
    private final AuditRecorder audit;

    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver, LlmResponseValidator validator,
                                      SyntheticValueSource synthetics, ParameterFingerprinter fingerprinter,
                                      AuditRecorder audit) {
        this.adapters = List.copyOf(adapters);
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.synthetics = Objects.requireNonNull(synthetics, "synthetics");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public ContextResponse buildContext(ContextRequest request, PrivacyContext context) {
        String correlationId = UUID.randomUUID().toString();
        String subjectToken = synthetics.syntheticValue(request.subjectId(), PrivacyNamespace.NONE, context);
        String fingerprint = fingerprinter.fingerprint(
                request.entityType() + "/" + request.subjectId(), context);

        DataRequest dataRequest = DataRequest.of(request.entityType(), request.subjectId());
        List<String> sources = new ArrayList<>();
        Set<String> prohibited = new LinkedHashSet<>();
        ObjectNode merged = null;

        try {
            for (DataSourceAdapter<?> adapter : adapters) {
                Object record = adapter.fetch(dataRequest);
                if (record == null) {
                    continue;
                }
                sources.add(adapter.sourceName());
                prohibited.addAll(SourceValues.prohibited(record, resolver));

                ObjectNode scrubbed = scrubber.scrub(record, context);
                if (merged == null) {
                    merged = scrubbed;
                } else {
                    merged.setAll(scrubbed);
                }
            }

            if (merged == null) {
                throw new PrivacyRefusedException("NO_SOURCE_DATA", request.entityType(),
                        "no source returned a record for this subject");
            }

            ValidationResult result = validator.validate(merged, prohibited, context);
            if (!result.valid()) {
                throw new PrivacyRefusedException("VALIDATION_FAILED",
                        result.violations().get(0).path(),
                        result.violations().size() + " violation(s); response withheld");
            }
        } catch (RuntimeException failure) {
            audit(request, subjectToken, fingerprint, context, "DENY", sources, correlationId);
            throw failure;
        }

        audit(request, subjectToken, fingerprint, context, "ALLOW", sources, correlationId);
        return new ContextResponse(request.entityType(), subjectToken, sources, merged);
    }

    private void audit(ContextRequest request, String subjectToken, String fingerprint,
                       PrivacyContext context, String decision, List<String> sources,
                       String correlationId) {
        audit.record("system", "get_entity_context", request.entityType(), subjectToken,
                fingerprint, context.redactionProfile(), context.scopeId(), decision,
                Set.copyOf(sources), correlationId);
    }
}
