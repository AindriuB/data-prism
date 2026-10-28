package io.github.aindriub.dataprism.orchestration;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.model.ConsistencyFinding;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.EntityCorrelationService;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.refusal.RefusalCodes;
import io.github.aindriub.dataprism.core.refusal.RefusalPaths;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.ReadReservation;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SourceCallContext;
import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import io.github.aindriub.dataprism.core.engine.SourceValues;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
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
        this(adapters, scrubber, resolver,
                List.of(Objects.requireNonNull(validator, "validator"), new SensitivePatternValidator()),
                synthetics, fingerprinter, audit, new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(resolver), aliasing, PrivacyMetrics.none());
    }

    /**
     * The full pipeline, with identity resolution, fan-out and limits supplied.
     *
     * <p>The shorter constructor above assumes every source shares a key and
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
        List<EntityCorrelationService.SourceRecord> raw = List.of();
        Set<String> prohibited = new LinkedHashSet<>();
        // Never logged and never audited: this is every synthetic value the
        // response contains, and it is only ever read by the validators.
        Set<String> emitted = new LinkedHashSet<>();
        Map<String, ObjectNode> trees = Map.of();
        boolean[] allSourcesRefused = {false};
        Map<String, String> dispositions = new LinkedHashMap<>();
        // Every declared pointer any source reported: what a validator's path is
        // checked against before it is allowed into a refusal.
        Set<String> declared = new LinkedHashSet<>();

        ContextResponse response;
        try {
            FetchOutcome fetched = fetchAndScrub(request, context, investigationContext,
                    sources, prohibited, dispositions);
            trees = fetched.trees();
            raw = fetched.raw();
            emitted.addAll(fetched.emitted());
            declared.addAll(fetched.declared());

            // Before scrubbing, and it has to be: the pseudonym is keyed on the
            // subject, so once these records are scrubbed every source's version
            // of a name is the same string and there is nothing left to compare.
            // Only sources that were not refused: a refused source leaves no trace.
            findings = correlation.correlate(fetched.raw(), context);
            if (!request.includeAgreementFindings()) {
                // get_entity_context's response must not change: agreement
                // findings are for the comparison path only, asked for on the
                // request rather than inferred, so this filter is the one place
                // that decides and every caller of buildContext shares it.
                findings = findings.stream()
                        .filter(f -> f.kind() != ConsistencyFinding.Kind.CONSISTENT)
                        .toList();
            }

            if (trees.isEmpty()) {
                // Nothing left to return. A source that was refused takes the call with it,
                // with its code; the scrub code first, as when scrubbing was the only
                // stage that could refuse.
                if (!fetched.scrubRefusals().isEmpty()) {
                    allSourcesRefused[0] = true;
                    throw fetched.scrubRefusals().get(0);
                }
                if (!fetched.validationRefusals().isEmpty()) {
                    allSourcesRefused[0] = true;
                    throw fetched.validationRefusals().get(0);
                }
                throw new PrivacyRefusedException("NO_SOURCE_DATA", request.entityType(),
                        "no source returned a record for this subject");
            }

            // The final check, over everything that is about to leave, as ONE tree: an array of
            // the returned sources' trees in the response's own key order. A validator that refuses a
            // combination across sources sees it, and no source name or alias is a key it can see or
            // report in a path. The union of every returned source's emitted values applies.
            ArrayNode assembled = JsonNodeFactory.instance.arrayNode();
            trees.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(
                            (String name) -> aliasing.nameFor(name, investigationContext, context))))
                    .forEach(entry -> assembled.add(entry.getValue()));
            List<Violation> violations = new ArrayList<>();
            for (LlmResponseValidator validator : validators) {
                violations.addAll(validator.validate(assembled, prohibited, emitted, context).violations());
            }
            if (!violations.isEmpty()) {
                violations.forEach(v -> metrics.increment(Metric.PRIVACY_VALIDATION_FAILURES));
                // Named by classification and path; the values themselves stay in
                // the withheld response, which no caller ever sees. The validators walk
                // the trees, which can hold payload keys the model never declared.
                throw new PrivacyRefusedException("VALIDATION_FAILED",
                        RefusalPaths.redact(withoutSourceIndex(violations.get(0).path()), declared),
                        violations.size() + " violation(s), first " + violations.get(0).code()
                                + " by " + violations.get(0).detectionMethod() + "; response withheld");
            }
            response = ContextResponse.of(request.entityType(), subjectToken, sources,
                    findings, trees, fieldsByNamespace(raw), aliasing, investigationContext, context, correlationId);
            if (ResponseSize.records(response) > limits.maxRecords()) {
                throw new PrivacyRefusedException("TOO_MANY_RECORDS", "$",
                        "response exceeds configured record limit " + limits.maxRecords());
            }
            if (ResponseSize.bytes(response, request.includeAgreementFindings()) > limits.maxResponseBytes()) {
                throw new PrivacyRefusedException("RESPONSE_TOO_LARGE", "$",
                        "response exceeds configured byte limit " + limits.maxResponseBytes());
            }
        } catch (RuntimeException failure) {
            if (failure instanceof PrivacyRefusedException) {
                metrics.increment(Metric.PRIVACY_FAILCLOSED);
            }
            if (failure instanceof PrivacyRefusedException && !allSourcesRefused[0]) {
                // Refusal paths are built from payload keys, so none is ever recorded.
                // Not when every source was refused: each already has its own key and code.
                dispositions.put("merged:<refused>", "REFUSED");
            }
            try {
                audit(request, subjectToken, fingerprint, context, investigationContext, denyDecision(failure),
                        sources, correlationId, dispositions);
            } catch (RuntimeException auditFailure) {
                // Full detail, which can name the sink's own file path, stays in the server's log;
                // the exception that leaves here carries only its stable code.
                // The refusal's code is safe to log; its message can carry payload and is not.
                LOG.error("audit record failed for a denial with code {}; refusing the call without an "
                        + "audit event", denyCode(failure), auditFailure);
                throw new DenialAuditFailedException(auditFailure);
            }
            // The DENY event is written; hand its id to the caller so a refusal can
            // be joined to it. Still a PrivacyRefusedException, so existing catches hold.
            if (failure instanceof PrivacyRefusedException refused) {
                throw new AuditedRefusalException(refused, correlationId);
            }
            throw new AuditedRefusalException(failure, request.entityType(), correlationId);
        }

        // A withheld source leaves no other trace on a dashboard when the call goes on to succeed.
        // failclosed counts refused calls only; this counts each source withheld from a returned response.
        sources.stream().filter(o -> o.status() == SourceOutcome.Status.REFUSED)
                .forEach(o -> metrics.increment(Metric.PRIVACY_SOURCE_WITHHELD));
        audit(request, subjectToken, fingerprint, context, investigationContext, "ALLOW", sources,
                correlationId, dispositions);
        return response;
    }

    /**
     * A final-pass path starts with the index of the source's tree in the assembled array
     * ({@code $[1].name}). The index says which source, and says nothing a reader needs, so it is
     * dropped before the path is checked against the declared fields: {@code $.name}.
     */
    private static String withoutSourceIndex(String path) {
        return path != null && path.matches("\\$\\[\\d+\\].*") ? "$" + path.substring(path.indexOf(']') + 1) : path;
    }

    /**
     * The serialised field name(s) each namespace appeared under, across every
     * source that answered — the same filter {@code NamespaceCorrelationService}
     * applies (namespaced, non-identifier fields), read from the same raw
     * records it correlates. {@code entity} is keyed by these field names, never
     * by a namespace's own name, so this is how {@code compare_entity_sources}
     * finds the node a namespace-compared finding is about without ever reading
     * a raw value itself.
     */
    private Map<PrivacyNamespace, List<String>> fieldsByNamespace(
            List<EntityCorrelationService.SourceRecord> raw) {
        Map<PrivacyNamespace, List<String>> out = new LinkedHashMap<>();
        for (EntityCorrelationService.SourceRecord source : raw) {
            for (FieldMetadata field : resolver.resolve(source.record().getClass())) {
                if (field.namespace() == PrivacyNamespace.NONE || field.identifier()) {
                    continue;
                }
                List<String> names = out.computeIfAbsent(field.namespace(), namespace -> new ArrayList<>());
                if (!names.contains(field.fieldName())) {
                    names.add(field.fieldName());
                }
            }
        }
        return out;
    }

    /**
     * The audited decision for a failed call: {@code DENY:<code>}, the form
     * the re-identification service writes. A code that is not a plain token
     * is never copied into the operator-facing audit file.
     */
    private static String denyDecision(RuntimeException failure) {
        return "DENY:" + denyCode(failure);
    }

    private static String denyCode(RuntimeException failure) {
        if (failure instanceof PrivacyRefusedException refused) {
            return RefusalCodes.sanitise(refused.code());
        }
        return AuditedRefusalException.REQUEST_FAILED;
    }

    /**
     * What {@link #fetchAndScrub} produces: the raw records {@code correlate} needs,
     * the scrubbed tree of every source that was not refused, and what was refused.
     */
    private record FetchOutcome(List<EntityCorrelationService.SourceRecord> raw, Map<String, ObjectNode> trees,
                                Set<String> emitted, Set<String> declared,
                                List<PrivacyRefusedException> scrubRefusals,
                                List<PrivacyRefusedException> validationRefusals) {
    }

    /** One source's scrub, held until it has been validated. */
    private record Scrubbed(int index, SourceOutcome outcome, Object record, ScrubResult result) {
    }

    /**
     * Fetches every source in parallel, scrubs each answer, and validates each scrubbed tree on its own.
     *
     * <p>A source whose scrub or validation refuses is marked {@code REFUSED} and dropped whole: no
     * field, no tree, no raw record for correlation, no synthetic value in {@code emitted}. Its raw values
     * stay in {@code prohibited}, so no other source can carry them out either. Its refusal is kept, and
     * is thrown by the caller only if no source is left. The audit disposition is a fixed key from the
     * declared source name with the sanitised code; the refusal path may carry payload keys and is not recorded.
     *
     * <p>A source's raw record is captured for {@code correlate} before it is
     * scrubbed, because correlation compares what each source actually said;
     * once scrubbed, every source's version of a name is the same pseudonym
     * and there is nothing left to compare.
     */
    private FetchOutcome fetchAndScrub(ContextRequest request, PrivacyContext context,
                                       InvestigationContext investigationContext,
                                       List<SourceOutcome> sources, Set<String> prohibited,
                                       Map<String, String> dispositions) {
        List<PrivacyRefusedException> scrubRefusals = new ArrayList<>();
        List<Scrubbed> scrubbed = new ArrayList<>();
        for (SourceFanOut.Fetched fetched : fetchAll(request, context)) {
            Object record = fetched.record();
            if (record == null) {
                sources.add(fetched.outcome());
                continue;
            }
            prohibited.addAll(SourceValues.prohibited(record, resolver));
            ScrubResult result;
            try {
                result = scrubber.scrub(record, context);
            } catch (PrivacyRefusedException refused) {
                sources.add(fetched.outcome().refused());
                dispositions.put(fetched.outcome().sourceName() + ":<refused>",
                        "REFUSED:" + RefusalCodes.sanitise(refused.code()));
                scrubRefusals.add(refused);
                continue;
            }
            metrics.increment(Metric.PRIVACY_TRANSFORMATIONS);
            scrubbed.add(new Scrubbed(sources.size(), fetched.outcome(), record, result));
            sources.add(fetched.outcome());
        }

        List<EntityCorrelationService.SourceRecord> raw = new ArrayList<>();
        Map<String, ObjectNode> trees = new LinkedHashMap<>();
        Set<String> emitted = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>();
        List<PrivacyRefusedException> validationRefusals = new ArrayList<>();
        for (Scrubbed s : scrubbed) {
            // Every source's raw values are known by now, so a value that crossed from another source is caught.
            List<Violation> violations = new ArrayList<>();
            for (LlmResponseValidator validator : validators) {
                violations.addAll(validator.validate(s.result().tree(), prohibited, s.result().emitted(), context)
                        .violations());
            }
            String sourceName = s.outcome().sourceName();
            if (!violations.isEmpty()) {
                violations.forEach(v -> metrics.increment(Metric.PRIVACY_VALIDATION_FAILURES));
                sources.set(s.index(), s.outcome().refused());
                dispositions.put(sourceName + ":<refused>", "REFUSED:VALIDATION_FAILED");
                validationRefusals.add(new PrivacyRefusedException("VALIDATION_FAILED",
                        RefusalPaths.redact(violations.get(0).path(), s.result().dispositions().keySet()),
                        violations.size() + " violation(s), first " + violations.get(0).code()
                                + " by " + violations.get(0).detectionMethod() + "; response withheld"));
                continue;
            }
            raw.add(new EntityCorrelationService.SourceRecord(
                    aliasing.nameFor(sourceName, investigationContext, context), s.record()));
            emitted.addAll(s.result().emitted());
            // Real source name, never the alias: the audit trail is operator-facing.
            s.result().dispositions().forEach((pointer, action) ->
                    dispositions.put(sourceName + ":" + pointer, action.name()));
            declared.addAll(s.result().dispositions().keySet());
            trees.put(sourceName, s.result().tree());
        }
        return new FetchOutcome(raw, trees, emitted, declared, scrubRefusals, validationRefusals);
    }

    /**
     * Calls the sources, charging the read budget first and once.
     *
     * <p>The fan-out decides which sources it will call (and refuses a selection that
     * is too large, before anything is reserved); one read per such source is then
     * reserved against the subject and the scope total in a single call, and nothing
     * is called if that is refused. Reads for sources the deadline passed before
     * they started are given back: they never happened. Refused rather than
     * throttled: a banded value narrows under repeated asking and every individual
     * answer looks correct, so there is nothing later in the pipeline that could
     * notice.
     */
    private List<SourceFanOut.Fetched> fetchAll(ContextRequest request, PrivacyContext context) {
        SourceFanOut.Plan plan = fanOut.plan(adapters, requestsPerSource(request), limits);
        ReadReservation reservation = null;
        if (!plan.callable().isEmpty()) {
            try {
                reservation = budget.reserve(context.scopeId(), request.subjectId(), plan.callable().size(),
                        limits.scopeReadBudget(), limits.scopeTotalReadBudget());
            } catch (RuntimeException failure) {
                fanOut.abandon(plan);
                throw failure;
            }
            if (!reservation.granted()) {
                fanOut.abandon(plan);
                throw new PrivacyRefusedException("SCOPE_READ_BUDGET", request.entityType(),
                        reservation.refusedBy() == ReadReservation.Refusal.SCOPE_TOTAL
                                ? "this scope has reached its read total of " + limits.scopeTotalReadBudget()
                                : "this subject would pass " + limits.scopeReadBudget()
                                        + " source reads in this scope");
            }
        }
        // An execution that fails leaves its reads charged: stricter, never looser.
        SourceFanOut.Execution execution = fanOut.execute(plan, limits);
        release(reservation, execution.notStarted());
        return execution.results();
    }

    private void release(ReadReservation reservation, int unused) {
        if (reservation == null || unused <= 0) {
            return;
        }
        try {
            budget.release(reservation, unused);
        } catch (RuntimeException e) {
            // The reads stay charged: stricter, never looser. Nothing to refuse for.
            LOG.warn("could not give back {} unused read(s)", unused, e);
        }
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
            requests.put(ref.sourceName(), DataRequest.of(request.entityType(), ref.key())
                    .withContext(new SourceCallContext(request.externalCorrelationId())));
        }
        return requests;
    }

    private void audit(ContextRequest request, String subjectToken, String fingerprint,
                       PrivacyContext context, InvestigationContext investigationContext,
                       String decision, List<SourceOutcome> sources, String correlationId,
                       Map<String, String> dispositions) {
        // Name and status only, and always the real source name: the audit trail
        // is an operational record for an operator, not a view a caller sees, so
        // it is never subject to SourceAliasing's capability check.
        Set<String> names = sources.stream()
                .map(outcome -> outcome.sourceName() + ":" + outcome.status())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        audit.record(new AuditEntry(investigationContext.principalId(), investigationContext.clientId(),
                request.toolName(), request.auditedEntityType(), subjectToken, fingerprint,
                context.redactionProfile(), context.scopeId(), context.purpose(),
                investigationContext.caseId(), decision, names, request.rejectedArguments(),
                correlationId, dispositions, request.approvalId(), request.approverId(),
                request.externalCorrelationId().map(ExternalCorrelationId::value).orElse("")));
    }
}
