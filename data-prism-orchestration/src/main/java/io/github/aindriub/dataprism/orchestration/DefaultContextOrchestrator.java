package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SourceValues;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The S0 pipeline, in order and without shortcuts.
 *
 * <p>Fetch, scrub, then validate against what the source actually held and
 * against what sensitive data looks like, then audit, then return. Every
 * validator runs and their violations are pooled, so one refusal does not hide
 * the rest. The validation step is not conditional and there is no path that
 * returns a response after it fails: a refusal is an exception, so there is
 * nothing for a caller to accidentally ignore.
 *
 * <p>S0 calls its sources in sequence. Parallel fan-out with timeouts, circuit
 * breakers and bounded concurrency is S5; sequencing is fine for one stub and
 * would be wrong for a real deployment.
 */
public final class DefaultContextOrchestrator implements ContextOrchestrator {

    private final List<DataSourceAdapter<?>> adapters;
    private final ScrubbingEngine scrubber;
    private final FieldMetadataResolver resolver;
    private final List<LlmResponseValidator> validators;
    private final SyntheticValueSource synthetics;
    private final ParameterFingerprinter fingerprinter;
    private final AuditRecorder audit;

    /**
     * The standard pipeline: the supplied comparison check, plus the pattern
     * scan. Pattern detection is not a caller's choice — a value that was never
     * in a classified field is invisible to any check that compares against the
     * source, and a deployment that simply forgot to wire the scan would look
     * exactly like one that is protected.
     */
    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver, LlmResponseValidator validator,
                                      SyntheticValueSource synthetics, ParameterFingerprinter fingerprinter,
                                      AuditRecorder audit) {
        this(adapters, scrubber, resolver,
                List.of(Objects.requireNonNull(validator, "validator"), new SensitivePatternValidator()),
                synthetics, fingerprinter, audit);
    }

    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver,
                                      List<LlmResponseValidator> validators,
                                      SyntheticValueSource synthetics, ParameterFingerprinter fingerprinter,
                                      AuditRecorder audit) {
        this.adapters = List.copyOf(adapters);
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.validators = List.copyOf(validators);
        this.synthetics = Objects.requireNonNull(synthetics, "synthetics");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (this.validators.isEmpty()) {
            throw new IllegalArgumentException("at least one response validator is required");
        }
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
        // Never logged and never audited: this is every synthetic value the
        // response contains, and it is only ever read by the validators.
        Set<String> emitted = new LinkedHashSet<>();
        ObjectNode merged = null;

        try {
            for (DataSourceAdapter<?> adapter : adapters) {
                Object record = adapter.fetch(dataRequest);
                if (record == null) {
                    continue;
                }
                sources.add(adapter.sourceName());
                prohibited.addAll(SourceValues.prohibited(record, resolver));

                ScrubResult scrubbed = scrubber.scrub(record, context);
                emitted.addAll(scrubbed.emitted());
                if (merged == null) {
                    merged = scrubbed.tree();
                } else {
                    merged.setAll(scrubbed.tree());
                }
            }

            if (merged == null) {
                throw new PrivacyRefusedException("NO_SOURCE_DATA", request.entityType(),
                        "no source returned a record for this subject");
            }

            List<Violation> violations = new ArrayList<>();
            for (LlmResponseValidator validator : validators) {
                ValidationResult result = validator.validate(merged, prohibited, emitted, context);
                violations.addAll(result.violations());
            }
            if (!violations.isEmpty()) {
                // Named by classification and path; the values themselves stay in
                // the withheld response, which no caller ever sees.
                throw new PrivacyRefusedException("VALIDATION_FAILED", violations.get(0).path(),
                        violations.size() + " violation(s), first " + violations.get(0).code()
                                + " by " + violations.get(0).detectionMethod() + "; response withheld");
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
