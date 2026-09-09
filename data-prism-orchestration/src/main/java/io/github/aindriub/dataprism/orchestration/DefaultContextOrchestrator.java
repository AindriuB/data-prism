package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.ConsistencyFinding;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.EntityCorrelationService;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SourceValues;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
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
 * <p>Sources are called in parallel on virtual threads, under a bulkhead, a
 * per-source timeout and a circuit breaker. A source that fails or times out is
 * recorded as absent and the answer is built from the rest: failing the whole
 * request because one system is slow would make the platform less available
 * than the systems behind it, and the gap is stated in the response rather
 * than hidden.
 *
 * <p>Every call carries an {@link InvestigationContext}: who is actually asking,
 * never a constant. It decides whether source names are exposed and is what
 * every audit event is attributed to.
 */
public final class DefaultContextOrchestrator implements ContextOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultContextOrchestrator.class);

    private final List<DataSourceAdapter<?>> adapters;
    private final ScrubbingEngine scrubber;
    private final FieldMetadataResolver resolver;
    private final List<LlmResponseValidator> validators;
    private final SyntheticValueSource synthetics;
    private final ParameterFingerprinter fingerprinter;
    private final AuditRecorder audit;
    private final IdentityResolver identities;
    private final SourceFanOut fanOut;
    private final ScopeBudget budget;
    private final RequestLimits limits;
    private final EntityCorrelationService correlation;
    private final SourceAliasing aliasing;
    private final PrivacyMetrics metrics;

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
                                      AuditRecorder audit, SourceAliasing aliasing) {
        this(adapters, scrubber, resolver, validator, synthetics, fingerprinter, audit, aliasing,
                PrivacyMetrics.none());
    }

    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver, LlmResponseValidator validator,
                                      SyntheticValueSource synthetics, ParameterFingerprinter fingerprinter,
                                      AuditRecorder audit, SourceAliasing aliasing, PrivacyMetrics metrics) {
        this(adapters, scrubber, resolver,
                List.of(Objects.requireNonNull(validator, "validator"), new SensitivePatternValidator()),
                synthetics, fingerprinter, audit, aliasing, metrics);
    }

    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver,
                                      List<LlmResponseValidator> validators,
                                      SyntheticValueSource synthetics, ParameterFingerprinter fingerprinter,
                                      AuditRecorder audit, SourceAliasing aliasing) {
        this(adapters, scrubber, resolver, validators, synthetics, fingerprinter, audit, aliasing,
                PrivacyMetrics.none());
    }

    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver,
                                      List<LlmResponseValidator> validators,
                                      SyntheticValueSource synthetics, ParameterFingerprinter fingerprinter,
                                      AuditRecorder audit, SourceAliasing aliasing, PrivacyMetrics metrics) {
        this.adapters = List.copyOf(adapters);
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.validators = List.copyOf(validators);
        this.synthetics = Objects.requireNonNull(synthetics, "synthetics");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.identities = new PassThroughIdentityResolver();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), this.metrics);
        this.budget = new InMemoryScopeBudget();
        this.limits = RequestLimits.DEFAULT;
        this.aliasing = Objects.requireNonNull(aliasing, "aliasing");
        this.correlation = new NamespaceCorrelationService(resolver);
        if (this.validators.isEmpty()) {
            throw new IllegalArgumentException("at least one response validator is required");
        }
    }

    /**
     * The full pipeline, with identity resolution, fan-out and limits supplied.
     *
     * <p>The shorter constructors above assume every source shares a key and
     * that failures need no breaker, which is true of a single stub and of
     * very little else. A real deployment uses this one.
     */
    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver,
                                      List<LlmResponseValidator> validators,
                                      SyntheticValueSource synthetics,
                                      ParameterFingerprinter fingerprinter, AuditRecorder audit,
                                      IdentityResolver identities, SourceFanOut fanOut,
                                      ScopeBudget budget, RequestLimits limits,
                                      EntityCorrelationService correlation,
                                      SourceAliasing aliasing) {
        this(adapters, scrubber, resolver, validators, synthetics, fingerprinter, audit, identities,
                fanOut, budget, limits, correlation, aliasing, PrivacyMetrics.none());
    }

    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver,
                                      List<LlmResponseValidator> validators,
                                      SyntheticValueSource synthetics,
                                      ParameterFingerprinter fingerprinter, AuditRecorder audit,
                                      IdentityResolver identities, SourceFanOut fanOut,
                                      ScopeBudget budget, RequestLimits limits,
                                      EntityCorrelationService correlation,
                                      SourceAliasing aliasing, PrivacyMetrics metrics) {
        this.adapters = List.copyOf(adapters);
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.validators = List.copyOf(validators);
        this.synthetics = Objects.requireNonNull(synthetics, "synthetics");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.aliasing = Objects.requireNonNull(aliasing, "aliasing");
        this.correlation = Objects.requireNonNull(correlation, "correlation");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (this.validators.isEmpty()) {
            throw new IllegalArgumentException("at least one response validator is required");
        }
    }

    @Override
    public ContextResponse buildContext(ContextRequest request, PrivacyContext context,
                                        InvestigationContext investigationContext) {
        Objects.requireNonNull(investigationContext, "investigationContext");
        if (!request.rejectedArguments().isEmpty()) {
            // Names only: request.rejectedArguments() is Set<String> of argument
            // names, never values, so there is nothing here for a value to leak
            // through even by accident.
            LOG.warn("caller supplied reserved argument name(s), ignored: {}",
                    request.rejectedArguments());
        }

        String correlationId = UUID.randomUUID().toString();
        String subjectToken = synthetics.syntheticValue(request.subjectId(), PrivacyNamespace.NONE, context);
        String fingerprint = fingerprinter.fingerprint(
                request.entityType() + "/" + request.subjectId(), context);

        List<SourceOutcome> sources = new ArrayList<>();
        List<ConsistencyFinding> findings = List.of();
        Set<String> prohibited = new LinkedHashSet<>();
        // Never logged and never audited: this is every synthetic value the
        // response contains, and it is only ever read by the validators.
        Set<String> emitted = new LinkedHashSet<>();
        ObjectNode merged = null;

        try {
            if (!budget.tryRead(context.scopeId(), request.subjectId(), limits.scopeReadBudget())) {
                // Refused rather than throttled. A banded value narrows under
                // repeated asking and every individual answer looks correct,
                // so there is nothing later in the pipeline that could notice.
                throw new PrivacyRefusedException("SCOPE_READ_BUDGET", request.entityType(),
                        "this subject has been read " + limits.scopeReadBudget()
                                + " times in this scope");
            }

            List<EntityCorrelationService.SourceRecord> raw = new ArrayList<>();
            for (SourceFanOut.Fetched fetched : fanOut.fetchAll(adapters,
                    requestsPerSource(request), limits)) {
                sources.add(fetched.outcome());
                Object record = fetched.record();
                if (record == null) {
                    continue;
                }
                raw.add(new EntityCorrelationService.SourceRecord(
                        aliasing.nameFor(fetched.outcome().sourceName(), investigationContext, context),
                        record));
                prohibited.addAll(SourceValues.prohibited(record, resolver));

                ScrubResult scrubbed = scrubber.scrub(record, context);
                metrics.increment(Metric.PRIVACY_TRANSFORMATIONS);
                emitted.addAll(scrubbed.emitted());
                if (merged == null) {
                    merged = scrubbed.tree();
                } else {
                    merged.setAll(scrubbed.tree());
                }
            }

            // Before scrubbing, and it has to be: the pseudonym is keyed on the
            // subject, so once these records are scrubbed every source's version
            // of a name is the same string and there is nothing left to compare.
            findings = correlation.correlate(raw, context);

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
                violations.forEach(v -> metrics.increment(Metric.PRIVACY_VALIDATION_FAILURES));
                // Named by classification and path; the values themselves stay in
                // the withheld response, which no caller ever sees.
                throw new PrivacyRefusedException("VALIDATION_FAILED", violations.get(0).path(),
                        violations.size() + " violation(s), first " + violations.get(0).code()
                                + " by " + violations.get(0).detectionMethod() + "; response withheld");
            }
        } catch (RuntimeException failure) {
            if (failure instanceof PrivacyRefusedException) {
                metrics.increment(Metric.PRIVACY_FAILCLOSED);
            }
            audit(request, subjectToken, fingerprint, context, investigationContext, "DENY", sources,
                    correlationId);
            throw failure;
        }

        audit(request, subjectToken, fingerprint, context, investigationContext, "ALLOW", sources,
                correlationId);
        return ContextResponse.of(request.entityType(), subjectToken, sources,
                findings, merged, aliasing, investigationContext, context);
    }

    /**
     * What to ask each source for.
     *
     * <p>The identity resolver decides, because a source keyed by its own
     * reference needs that reference rather than the canonical id. A source
     * the resolver leaves out is not called at all, which keeps a subject
     * that only some systems know from producing a row of misleading
     * no-data outcomes.
     */
    private Map<String, DataRequest> requestsPerSource(ContextRequest request) {
        List<String> names = adapters.stream().map(DataSourceAdapter::sourceName).toList();
        Map<String, DataRequest> requests = new LinkedHashMap<>();
        for (IdentityResolver.SourceRef ref : identities.expand(
                new IdentityResolver.CanonicalId(request.subjectId()), names)) {
            requests.put(ref.sourceName(), DataRequest.of(request.entityType(), ref.key()));
        }
        return requests;
    }

    private void audit(ContextRequest request, String subjectToken, String fingerprint,
                       PrivacyContext context, InvestigationContext investigationContext,
                       String decision, List<SourceOutcome> sources, String correlationId) {
        // Name and status only, and always the real source name: the audit trail
        // is an operational record for an operator, not a view a caller sees, so
        // it is never subject to SourceAliasing's capability check.
        Set<String> names = sources.stream()
                .map(outcome -> outcome.sourceName() + ":" + outcome.status())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        audit.record(investigationContext.principalId(), investigationContext.clientId(),
                "get_entity_context", request.entityType(), subjectToken, fingerprint,
                context.redactionProfile(), context.scopeId(), context.purpose(),
                investigationContext.caseId(), decision, names, request.rejectedArguments(),
                correlationId);
    }
}
