package io.github.aindriub.dataprism.orchestration;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditEvent;
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
import io.github.aindriub.dataprism.core.model.ModelDescription;
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

import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceLabels;
import io.github.aindriub.dataprism.orchestration.catalog.SourceSelection;
import io.github.aindriub.dataprism.orchestration.links.IssuedLink;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilder;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import java.time.Duration;
import io.github.aindriub.dataprism.core.link.LinkReason;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.orchestration.links.FollowRequest;
import io.github.aindriub.dataprism.orchestration.links.LinkFollower;
import io.github.aindriub.dataprism.orchestration.links.ResolverOutcome;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final OrchestrationOptions options;
    private final SourceCatalog catalog;
    private final LinkBuilder linkBuilder;
    private final LinkFollower follower;
    /** The clock of link issuing, which follow shares; the system clock when links are off. */
    private final Clock clock;

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
                new NamespaceCorrelationService(resolver), aliasing, PrivacyMetrics.none(), OrchestrationOptions.defaults());
    }

    /**
     * The full pipeline, with identity resolution, fan-out and limits supplied.
     *
     * <p>The shorter constructor above assumes every source shares a key and
     * that failures need no breaker, which is true of a single stub and of
     * very little else. A real deployment uses this one.
     *
     * @param options the catalog, visibility, real-names and selection settings; every later optional
     *                collaborator is added there, so this signature does not move again
     */
    public DefaultContextOrchestrator(List<DataSourceAdapter<?>> adapters, ScrubbingEngine scrubber,
                                      FieldMetadataResolver resolver,
                                      List<LlmResponseValidator> validators,
                                      SyntheticValueSource synthetics,
                                      ParameterFingerprinter fingerprinter, AuditRecorder audit,
                                      IdentityResolver identities, SourceFanOut fanOut,
                                      ScopeBudget budget, RequestLimits limits,
                                      EntityCorrelationService correlation,
                                      SourceAliasing aliasing, PrivacyMetrics metrics,
                                      OrchestrationOptions options) {
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
        this.options = Objects.requireNonNull(options, "options");
        this.catalog = options.catalog().orElseGet(() -> SourceCatalog.build(this.adapters, Map.of()));
        this.linkBuilder = options.links()
                .map(l -> new LinkBuilder(l.codec(), l.maxPerResponse(), l.resolvers(), l.descriptors(), l.clock()))
                .orElse(null);
        this.clock = options.links().map(OrchestrationOptions.LinkIssuing::clock).orElse(Clock.systemUTC());
        this.follower = options.follow()
                .map(f -> new LinkFollower(f.gate(), f.resolvers(), f.keys(), f.invoker(), f.maxTargets(),
                        f.resolverTimeout()))
                .orElse(null);
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
        int[] issuedLinks = {0};
        int[] omittedLinks = {0};
        Map<String, String> dispositions = new LinkedHashMap<>();
        // The effective label setting, on every event of this call, a denial included (OD-C2 (a)).
        dispositions.put(AuditEvent.NAMES_KEY,
                SourceLabels.effectiveKind(options.realNames(), investigationContext, context).name());
        boolean[] selectionRefused = {false};
        // Every declared pointer any source reported: what a validator's path is
        // checked against before it is allowed into a refusal.
        Set<String> declared = new LinkedHashSet<>();

        ContextResponse response;
        try {
            SourceLabels labels;
            try {
                labels = SourceLabels.forRequest(catalog, options.visibility(), aliasing, options.realNames(),
                        investigationContext, context);
            } catch (SourceLabels.Collision collision) {
                // Both real names go to the audit trail; the client learns only the code.
                selectionRefused[0] = true;
                collision.sources().forEach(name -> sources.add(
                        new SourceOutcome(name, SourceOutcome.Status.REFUSED, java.time.Duration.ZERO, null)));
                throw collision;
            }
            List<DataSourceAdapter<?>> selected;
            try {
                selected = select(request, labels);
            } catch (SourceSelection.Refusal refusal) {
                selectionRefused[0] = true;
                if (refusal.auditReason() != null) {
                    dispositions.put("catalog", "REFUSED:" + refusal.auditReason());
                }
                throw refusal;
            }
            FetchOutcome fetched = fetchAndScrub(request, context, labels, selected,
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
            LinkBuilder.Result linkResult = issueLinks(request, context, investigationContext, labels, trees,
                    raw, prohibited, emitted);
            // Only tokens the builder sealed are exempt from the key-shape scan; a token-shaped string
            // that came from a source record is not in this set and still fails.
            emitted.addAll(linkResult.tokens());
            issuedLinks[0] = linkResult.issued();
            omittedLinks[0] = linkResult.omitted();
            ArrayNode assembled = JsonNodeFactory.instance.arrayNode();
            trees.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(
                            labels::labelOf)))
                    .forEach(entry -> assembled.add(entry.getValue()));
            // The links are scanned too, after the trees so that no tree's index moves.
            linkResult.bySource().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (!entry.getValue().links().isEmpty()) {
                            assembled.add(linkNode(entry.getValue().links()));
                        }
                    });
            requireValid(assembled, prohibited, emitted, declared, context);
            response = ContextResponse.of(request.entityType(), subjectToken, sources,
                    findings, trees, fieldsByNamespace(raw), labels, correlationId, linkResult.bySource());
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
            if (failure instanceof PrivacyRefusedException && !allSourcesRefused[0] && !selectionRefused[0]) {
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
        // Counted once the response is certain to leave: a refused call issued nothing.
        for (int i = 0; i < issuedLinks[0]; i++) {
            metrics.increment(Metric.LINKS_ISSUED);
        }
        for (int i = 0; i < omittedLinks[0]; i++) {
            metrics.increment(Metric.LINKS_OMITTED);
        }
        audit(request, subjectToken, fingerprint, context, investigationContext, "ALLOW", sources,
                correlationId, dispositions);
        return response;
    }

    /**
     * The final check over everything that is about to leave, as one tree. Every validator runs; a violation
     * refuses, named by classification and path and never by value. The validators walk the trees, which can
     * hold payload keys the model never declared, so the path is checked against what was declared.
     */
    private void requireValid(ArrayNode assembled, Set<String> prohibited, Set<String> emitted,
                              Set<String> declared, PrivacyContext context) {
        List<Violation> violations = new ArrayList<>();
        for (LlmResponseValidator validator : validators) {
            violations.addAll(validator.validate(assembled, prohibited, emitted, context).violations());
        }
        if (!violations.isEmpty()) {
            violations.forEach(v -> metrics.increment(Metric.PRIVACY_VALIDATION_FAILURES));
            throw new PrivacyRefusedException("VALIDATION_FAILED",
                    RefusalPaths.redact(withoutSourceIndex(violations.get(0).path()), declared),
                    violations.size() + " violation(s), first " + violations.get(0).code()
                            + " by " + violations.get(0).detectionMethod() + "; response withheld");
        }
    }

    /**
     * Issues the sealed links of the sources that answered, or nothing: links off, a comparison (which carries
     * no links), no answered source, or any failure. Reads declarations and the raw records only; calls no
     * resolver and no adapter.
     */
    private LinkBuilder.Result issueLinks(ContextRequest request, PrivacyContext context,
                                          InvestigationContext caller, SourceLabels labels,
                                          Map<String, ObjectNode> trees,
                                          List<EntityCorrelationService.SourceRecord> raw,
                                          Set<String> prohibited, Set<String> emitted) {
        if (linkBuilder == null || request.includeAgreementFindings()) {
            return LinkBuilder.Result.NONE;
        }
        try {
            Map<String, Object> recordByLabel = new LinkedHashMap<>();
            raw.forEach(source -> recordByLabel.put(source.sourceName(), source.record()));
            List<LinkBuilder.Origin> origins = new ArrayList<>();
            for (String name : trees.keySet()) {
                String label = labels.labelOf(name);
                Object record = recordByLabel.get(label);
                if (record != null) {
                    origins.add(new LinkBuilder.Origin(name, label, record));
                }
            }
            return buildLinks(origins, labels, context, caller, prohibited, emitted);
        } catch (RuntimeException failure) {
            // Fail closed to no links. The class only: the message could name a value.
            LOG.warn("links not issued: {}", failure.getClass().getSimpleName());
            return LinkBuilder.Result.NONE;
        }
    }

    /** Builds the links of the given answered origins, or none when the scope has no known end. */
    private LinkBuilder.Result buildLinks(List<LinkBuilder.Origin> origins, SourceLabels labels,
                                          PrivacyContext context, InvestigationContext caller,
                                          Set<String> prohibited, Set<String> emitted) {
        Instant scopeExpiry = context.expiresAt();
        if (linkBuilder == null || scopeExpiry == null) {
            return LinkBuilder.Result.NONE; // fail closed: a link with no known end is not issued
        }
        // The scope's end already includes the token's (ScopeResolver), so it binds both.
        LinkBinding binding = new LinkBinding(context.scopeId(), caller.principalId(), scopeExpiry, scopeExpiry);
        return linkBuilder.build(origins, labels, binding, name -> nameIsSafe(name, prohibited, emitted, context));
    }

    /** Whether a declared field name may appear in output: every validator accepts it as a lone string. */
    private boolean nameIsSafe(String name, Set<String> prohibited, Set<String> emitted, PrivacyContext context) {
        ObjectNode probe = JsonNodeFactory.instance.objectNode().put("field", name);
        for (LlmResponseValidator validator : validators) {
            if (!validator.validate(probe, prohibited, emitted, context).violations().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static ObjectNode linkNode(List<IssuedLink> links) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (IssuedLink link : links) {
            ObjectNode node = array.addObject();
            node.put("rel", link.rel());
            node.put("target", link.target());
            node.put("field", link.field());
            node.put("namespace", link.namespace());
            if (link.toNamespace() != null) {
                node.put("toNamespace", link.toNamespace());
            }
            node.put("link", link.link());
            node.put("expiresAt", link.expiresAt());
        }
        ObjectNode holder = JsonNodeFactory.instance.objectNode();
        holder.set("_links", array);
        return holder;
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
                                       SourceLabels labels, List<DataSourceAdapter<?>> selected,
                                       List<SourceOutcome> sources, Set<String> prohibited,
                                       Map<String, String> dispositions) {
        return scrubAndValidate(fetchAll(request, context, selected), context, labels, sources, prohibited,
                dispositions);
    }

    /**
     * The scrub and validate steps over what was fetched, shared by every read: a record is scrubbed, then
     * validated on its own against every value the other answers hold; a refusal drops that answer whole.
     */
    private FetchOutcome scrubAndValidate(List<SourceFanOut.Fetched> fetchedAll, PrivacyContext context,
                                          SourceLabels labels, List<SourceOutcome> sources,
                                          Set<String> prohibited, Map<String, String> dispositions) {
        List<PrivacyRefusedException> scrubRefusals = new ArrayList<>();
        List<Scrubbed> scrubbed = new ArrayList<>();
        for (SourceFanOut.Fetched fetched : fetchedAll) {
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
                    labels.labelOf(sourceName), s.record()));
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
    private List<SourceFanOut.Fetched> fetchAll(ContextRequest request, PrivacyContext context,
                                                List<DataSourceAdapter<?>> selected) {
        SourceFanOut.Plan plan = fanOut.plan(selected, requestsPerSource(request, selected), limits);
        return fetchPlanned(plan, context, request.subjectId(), request.entityType(), null);
    }

    /**
     * Charges and runs a plan: one read per callable source is reserved against {@code budgetSubject} and the
     * scope total, and nothing is called if that is refused. {@code path} names the entity type in a refusal.
     * With a {@code deadline} (a follow's), the reservation may take time: if the deadline has passed when it
     * returns, the reservation is released, nothing is called and the answer is null; otherwise the reads are
     * bound by the time left, not by a fresh request timeout.
     */
    private List<SourceFanOut.Fetched> fetchPlanned(SourceFanOut.Plan plan, PrivacyContext context,
                                                    String budgetSubject, String path, Instant deadline) {
        ReadReservation reservation = null;
        if (!plan.callable().isEmpty()) {
            try {
                reservation = budget.reserve(context.scopeId(), budgetSubject, plan.callable().size(),
                        limits.scopeReadBudget(), limits.scopeTotalReadBudget());
            } catch (RuntimeException failure) {
                fanOut.abandon(plan);
                throw failure;
            }
            if (!reservation.granted()) {
                fanOut.abandon(plan);
                throw new PrivacyRefusedException("SCOPE_READ_BUDGET", path,
                        reservation.refusedBy() == ReadReservation.Refusal.SCOPE_TOTAL
                                ? "this scope has reached its read total of " + limits.scopeTotalReadBudget()
                                : "this subject would pass " + limits.scopeReadBudget()
                                        + " source reads in this scope");
            }
        }
        Duration window = limits.requestTimeout();
        if (deadline != null) {
            window = Duration.between(clock.instant(), deadline);
            if (window.isNegative() || window.isZero()) {
                // The reservation outlasted the follow: nothing is called, and every unused read goes back.
                fanOut.abandon(plan);
                settle(reservation, plan.callable().size());
                return null;
            }
        }
        int unused = 0;
        try {
            SourceFanOut.Execution execution = fanOut.execute(plan, limits, window);
            unused = execution.notStarted();
            return execution.results();
        } catch (SourceFanOut.ExecutionFailure failure) {
            // Cancelled and its trials handed back by the fan-out; the sources it never called go back too.
            unused = failure.notStarted();
            throw failure.failure();
        } finally {
            settle(reservation, unused);
        }
    }

    /**
     * Ends a reservation: the reads of sources never called go back, the rest were used and stay charged.
     * The used reads are forfeited from the reservation itself, so that a budget that tracks its reservations
     * can drop this one; without that, every request's reservation would stay tracked until its scope ends.
     */
    private void settle(ReadReservation reservation, int unused) {
        if (reservation == null) {
            return;
        }
        try {
            if (unused > 0) {
                budget.release(reservation, unused);
            }
        } catch (RuntimeException e) {
            // The reads stay charged: stricter, never looser. Nothing to refuse for.
            LOG.warn("could not give back {} unused read(s): {}", unused, e.getClass().getSimpleName());
        }
        try {
            reservation.takeUnused(reservation.reads());
            budget.release(reservation, 0);
        } catch (RuntimeException e) {
            LOG.warn("could not settle a read reservation: {}", e.getClass().getSimpleName());
        }
    }

    // ---- follow ----

    /** What one follow call has done so far, so that a refusal can audit it. */
    private static final class FollowState {
        String subject = "";
        final Map<String, String> dispositions = new LinkedHashMap<>();
        final List<SourceOutcome> sources = new ArrayList<>();
        boolean refusalAudited;
        int issuedLinks;
        int omittedLinks;
    }

    /**
     * What a follow has decided to answer with, before the finishing step has validated, measured and audited it.
     * {@code followChecked} can return nothing else, so no path can leave without that step.
     */
    private record FollowPending(String entityType, String label, SourceResult result, ArrayNode assembled,
                                 Set<String> prohibited, Set<String> emitted, Set<String> declared) {
        /** An answer that is a status alone: no tree, so nothing for the validators to read. */
        static FollowPending statusOnly(String entityType, String label, SourceResult result) {
            return new FollowPending(entityType, label, result, JsonNodeFactory.instance.arrayNode(),
                    Set.of(), Set.of(), Set.of());
        }
    }

    /** One key's read: its position in the fingerprint order, its outcome, and the scrubbed tree if it answered. */
    private record FollowItem(int index, SourceOutcome outcome, ObjectNode tree, Object record) {
    }

    @Override
    public ContextResponse follow(FollowRequest request, PrivacyContext context,
                                  InvestigationContext investigationContext) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(investigationContext, "investigationContext");
        String correlationId = UUID.randomUUID().toString();
        String fingerprint = fingerprinter.fingerprint(request.toolName(), context);
        FollowState state = new FollowState();
        state.dispositions.put(AuditEvent.NAMES_KEY,
                SourceLabels.effectiveKind(options.realNames(), investigationContext, context).name());
        ContextResponse response;
        try {
            FollowPending pending = followChecked(request, context, investigationContext, correlationId, state);
            // The one successful exit: every answer, whatever its status, is validated and measured here.
            response = followFinish(pending, context, correlationId, state);
        } catch (RuntimeException failure) {
            if (failure instanceof PrivacyRefusedException) {
                metrics.increment(Metric.PRIVACY_FAILCLOSED);
                if (!state.refusalAudited) {
                    state.dispositions.put("merged:<refused>", "REFUSED");
                }
            }
            try {
                auditFollow(request, state, fingerprint, context, investigationContext, denyDecision(failure),
                        correlationId);
            } catch (RuntimeException auditFailure) {
                LOG.error("audit record failed for a denial with code {}; refusing the call without an "
                        + "audit event", denyCode(failure), auditFailure);
                throw new DenialAuditFailedException(auditFailure);
            }
            if (failure instanceof PrivacyRefusedException refused) {
                throw new AuditedRefusalException(refused, correlationId);
            }
            throw new AuditedRefusalException(failure, request.toolName(), correlationId);
        }
        state.sources.stream().filter(o -> o.status() == SourceOutcome.Status.REFUSED)
                .forEach(o -> metrics.increment(Metric.PRIVACY_SOURCE_WITHHELD));
        for (int i = 0; i < state.issuedLinks; i++) {
            metrics.increment(Metric.LINKS_ISSUED);
        }
        for (int i = 0; i < state.omittedLinks; i++) {
            metrics.increment(Metric.LINKS_OMITTED);
        }
        auditFollow(request, state, fingerprint, context, investigationContext, "ALLOW", correlationId);
        return response;
    }

    private void auditFollow(FollowRequest request, FollowState state, String fingerprint, PrivacyContext context,
                             InvestigationContext investigationContext, String decision, String correlationId) {
        audit(request.toolName(), AuditedEntityTypes.UNREGISTERED, request.rejectedArguments(), request.approvalId(),
                request.approverId(), request.externalCorrelationId(), state.subject, fingerprint, context,
                investigationContext, decision, state.sources, correlationId, state.dispositions);
    }

    /** The refusal for a link, with the real reason set aside for the audit. */
    private static PrivacyRefusedException followRefusal(FollowState state, LinkFollower.Refused refused) {
        state.dispositions.put("link", refused.auditValue());
        state.refusalAudited = true;
        return new PrivacyRefusedException(refused.clientCode(), "link", refused.message());
    }

    private FollowPending followChecked(FollowRequest request, PrivacyContext context,
                                          InvestigationContext caller, String correlationId, FollowState state) {
        if (follower == null) {
            throw followRefusal(state, LinkFollower.refused(LinkReason.RELATION_UNDECLARED));
        }
        Instant scopeExpiry = context.expiresAt();
        if (scopeExpiry == null) {
            throw followRefusal(state, LinkFollower.refused(LinkReason.MALFORMED));
        }
        // The scope's end already includes the token's (ScopeResolver), so it binds both.
        LinkBinding binding = new LinkBinding(context.scopeId(), caller.principalId(), scopeExpiry, scopeExpiry);
        LinkFollower.Verdict verdict = follower.authorise(request.link(), binding, () -> SourceLabels.forRequest(
                catalog, options.visibility(), aliasing, options.realNames(), caller, context));
        if (!(verdict instanceof LinkFollower.Authorised target)) {
            throw followRefusal(state, (LinkFollower.Refused) verdict);
        }
        // A synthetic value, like any subject token; never the key.
        state.subject = synthetics.syntheticValue(target.key(), PrivacyNamespace.NONE, context);
        String name = target.target().name();
        DataSourceAdapter<?> adapter = adapters.stream().filter(a -> a.sourceName().equals(name)).findFirst()
                .orElseThrow(() -> followRefusal(state, LinkFollower.refused(LinkReason.TARGET_NOT_PERMITTED)));
        String entityType = target.entityType();
        boolean direct = target.relation() == LinkRelation.DIRECT;
        SourceLabels labels = target.labels();
        String label = target.label();

        List<String> keys;
        boolean truncated = false;
        // One deadline for the whole follow: the resolver and every read after it.
        Instant deadline = clock.instant().plus(limits.requestTimeout());
        if (direct) {
            keys = List.of(target.key());
        } else {
            LinkKeys.ScopeKeys scopeKeys = follower.scopeKeys(context.scopeId());
            chargeResolver(context, target.key(), entityType);
            ResolutionContext resolution = new ResolutionContext(context.scopeId(), caller.principalId(),
                    context.purpose(), deadline);
            ResolverOutcome outcome = follower.resolve(target, resolution);
            if (outcome instanceof ResolverOutcome.Keys resolved) {
                keys = LinkFollower.ordered(scopeKeys, resolved.keys());
                truncated = resolved.more();
            } else {
                // A failure never falls back to a direct read, another resolver or the untranslated key.
                SourceOutcome.Status status = outcome instanceof ResolverOutcome.Empty
                        ? SourceOutcome.Status.NO_DATA : SourceOutcome.Status.RESOLVER_FAILED;
                state.sources.add(new SourceOutcome(name, status, Duration.ZERO, null));
                return FollowPending.statusOnly(entityType, label, SourceResult.of(status));
            }
        }

        Set<String> prohibited = new LinkedHashSet<>();
        // The chained key and every key the resolver returned are raw values a target record must not echo.
        prohibited.add(target.key());
        prohibited.addAll(keys);
        Set<String> emitted = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>();
        List<FollowItem> items = new ArrayList<>();
        PrivacyRefusedException firstRefusal = null;
        for (int i = 0; i < keys.size(); i++) {
            if (!direct && !clock.instant().isBefore(deadline)) {
                // Past the follow's deadline no further read is started.
                if (i == 0) {
                    // Nothing was read, so this is not NO_DATA: the client sees the timeout the fan-out reports.
                    state.sources.add(new SourceOutcome(name, SourceOutcome.Status.TIMED_OUT, Duration.ZERO, null));
                    return FollowPending.statusOnly(entityType, label,
                            SourceResult.ofItems(SourceOutcome.Status.TIMED_OUT, List.of(), true));
                }
                truncated = true;
                break;
            }
            DataRequest read = DataRequest.of(entityType, keys.get(i))
                    .withContext(new SourceCallContext(request.externalCorrelationId()))
                    .withKeyNamespace(target.targetNamespace());
            List<SourceFanOut.Fetched> fetched;
            try {
                // Charged to the key's own subject counter and to the scope total, before the read.
                fetched = fetchPlanned(fanOut.plan(List.of(adapter), Map.of(name, read), limits), context,
                        keys.get(i), entityType, direct ? null : deadline);
            } catch (PrivacyRefusedException refused) {
                if (i > 0 && "SCOPE_READ_BUDGET".equals(refused.code())) {
                    truncated = true;
                    break;
                }
                throw refused;
            }
            if (fetched == null) {
                // The reservation took the rest of the follow's time; no read was started.
                if (i == 0) {
                    state.sources.add(new SourceOutcome(name, SourceOutcome.Status.TIMED_OUT, Duration.ZERO, null));
                    return FollowPending.statusOnly(entityType, label,
                            SourceResult.ofItems(SourceOutcome.Status.TIMED_OUT, List.of(), true));
                }
                truncated = true;
                break;
            }
            List<SourceOutcome> itemSources = new ArrayList<>();
            Map<String, String> itemDispositions = new LinkedHashMap<>();
            FetchOutcome done = scrubAndValidate(fetched, context, labels, itemSources, prohibited,
                    itemDispositions);
            String auditName = direct ? name : name + "[" + i + "]";
            itemDispositions.forEach((path, action) -> state.dispositions.put(
                    path.equals(name + ":<refused>") ? auditName + ":<refused>" : path, action));
            SourceOutcome outcome = itemSources.get(0);
            state.sources.add(outcome);
            if (outcome.status() == SourceOutcome.Status.REFUSED && firstRefusal == null) {
                firstRefusal = !done.scrubRefusals().isEmpty() ? done.scrubRefusals().get(0)
                        : done.validationRefusals().get(0);
            }
            items.add(new FollowItem(i, outcome, done.trees().get(name),
                    done.raw().isEmpty() ? null : done.raw().get(0).record()));
            emitted.addAll(done.emitted());
            declared.addAll(done.declared());
        }
        if (items.stream().noneMatch(item -> item.outcome().answered()) && firstRefusal != null) {
            // Every answer there was is refused: the call is refused, with the first code (D-070-2).
            state.refusalAudited = true;
            throw firstRefusal;
        }

        List<LinkBuilder.Origin> origins = new ArrayList<>();
        for (FollowItem item : items) {
            if (item.tree() != null && item.record() != null) {
                origins.add(new LinkBuilder.Origin(name, originLabel(direct, label, item), item.record()));
            }
        }
        LinkBuilder.Result linkResult = origins.isEmpty() ? LinkBuilder.Result.NONE
                : quietLinks(origins, labels, context, caller, prohibited, emitted);
        // Only tokens the builder sealed are exempt from the key-shape scan.
        emitted.addAll(linkResult.tokens());
        state.issuedLinks = linkResult.issued();
        state.omittedLinks = linkResult.omitted();

        ArrayNode assembled = JsonNodeFactory.instance.arrayNode();
        items.stream().filter(item -> item.tree() != null).forEach(item -> assembled.add(item.tree()));
        linkResult.bySource().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!entry.getValue().links().isEmpty()) {
                assembled.add(linkNode(entry.getValue().links()));
            }
        });

        SourceResult result;
        List<SourceResult.Item> resultItems = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (FollowItem item : items) {
            if (item.outcome().status() == SourceOutcome.Status.REFUSED) {
                continue; // omitted (D-070-2); audited under <target>[<i>]:<refused>
            }
            String status = item.outcome().status().name();
            statuses.add(status);
            if (item.tree() == null) {
                resultItems.add(new SourceResult.Item(status, null, List.of(), null));
                continue;
            }
            LinkBuilder.Linked linked = linkResult.bySource().get(originLabel(direct, label, item));
            List<IssuedLink> links = linked == null ? List.of() : linked.links();
            int omitted = linked == null ? 0 : linked.omitted();
            resultItems.add(new SourceResult.Item(status, item.tree(), links, omitted > 0 ? omitted : null));
        }
        if (direct) {
            SourceResult.Item only = resultItems.get(0);
            result = only.entity() == null ? SourceResult.of(SourceOutcome.Status.valueOf(only.status()))
                    : SourceResult.answered(only.entity(), only.links(), only.linksOmitted() == null ? 0 : only.linksOmitted());
        } else {
            result = SourceResult.ofItems(SourceOutcome.Status.valueOf(LinkFollower.outerStatus(statuses)),
                    resultItems, truncated);
        }
        return new FollowPending(entityType, label, result, assembled, prohibited, emitted, declared);
    }

    /**
     * The finishing step of every successful follow, in this order: the final validation, then the record and
     * byte limits on the response that leaves (with its real label). A refusal here is audited as a denial by
     * {@link #follow}; the allow audit follows in the same exit.
     */
    private ContextResponse followFinish(FollowPending pending, PrivacyContext context, String correlationId,
                                         FollowState state) {
        requireValid(pending.assembled(), pending.prohibited(), pending.emitted(), pending.declared(), context);
        ContextResponse measured = new ContextResponse(pending.entityType(), state.subject,
                Map.of(pending.label(), pending.result()), List.of(), Map.of(), correlationId);
        if (ResponseSize.records(measured) > limits.maxRecords()) {
            throw new PrivacyRefusedException("TOO_MANY_RECORDS", "$",
                    "response exceeds configured record limit " + limits.maxRecords());
        }
        if (ResponseSize.bytes(measured, false) > limits.maxResponseBytes()) {
            throw new PrivacyRefusedException("RESPONSE_TOO_LARGE", "$",
                    "response exceeds configured byte limit " + limits.maxResponseBytes());
        }
        return measured;
    }

    private static String originLabel(boolean direct, String label, FollowItem item) {
        return direct ? label : Integer.toString(item.index());
    }

    /** As {@link #buildLinks}, failing closed to no links. The class only is logged. */
    private LinkBuilder.Result quietLinks(List<LinkBuilder.Origin> origins, SourceLabels labels,
                                          PrivacyContext context, InvestigationContext caller,
                                          Set<String> prohibited, Set<String> emitted) {
        try {
            return buildLinks(origins, labels, context, caller, prohibited, emitted);
        } catch (RuntimeException failure) {
            LOG.warn("links not issued: {}", failure.getClass().getSimpleName());
            return LinkBuilder.Result.NONE;
        }
    }

    /** Charges one resolver call to the chained key's own counter and the scope total; refuses if unaffordable. */
    private void chargeResolver(PrivacyContext context, String key, String path) {
        int cost = options.follow().orElseThrow().budgetCost();
        ReadReservation reservation = budget.reserve(context.scopeId(), key, cost, limits.scopeReadBudget(),
                limits.scopeTotalReadBudget());
        if (!reservation.granted()) {
            throw new PrivacyRefusedException("SCOPE_READ_BUDGET", path,
                    reservation.refusedBy() == ReadReservation.Refusal.SCOPE_TOTAL
                            ? "this scope has reached its read total of " + limits.scopeTotalReadBudget()
                            : "this subject would pass " + limits.scopeReadBudget() + " source reads in this scope");
        }
        settle(reservation, 0);
    }

    /** Describes the named source through this orchestrator's own scrubber, the one that scrubs it. */
    @Override
    public Optional<ModelDescription> describe(String sourceName, PrivacyContext context, boolean linksEnabled) {
        Objects.requireNonNull(sourceName, "sourceName");
        return adapters.stream().filter(adapter -> adapter.sourceName().equals(sourceName)).findFirst()
                .flatMap(adapter -> scrubber.describe(sourceName, adapter.responseType(), context, linksEnabled));
    }

    /**
     * Which adapters this call asks, decided before the budget is touched: the ones the caller chose
     * by label, or, with no choice, the permitted sources that serve the entity type or declare none, if
     * there are few enough of them. A source
     * the catalog does not hold is never asked.
     */
    private List<DataSourceAdapter<?>> select(ContextRequest request, SourceLabels labels) {
        java.util.Set<String> names = request.targetSources().isPresent()
                ? SourceSelection.targeted(labels, request.targetSources().get(), request.entityType(),
                        limits.maxSources())
                : SourceSelection.unselected(labels, request.entityType(), options.unselectedSourceLimit());
        return adapters.stream().filter(adapter -> names.contains(adapter.sourceName())).toList();
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
    private Map<String, DataRequest> requestsPerSource(ContextRequest request,
                                                       List<DataSourceAdapter<?>> selected) {
        List<String> names = selected.stream().map(DataSourceAdapter::sourceName).toList();
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
        audit(request.toolName(), request.auditedEntityType(), request.rejectedArguments(), request.approvalId(),
                request.approverId(), request.externalCorrelationId(), subjectToken, fingerprint, context,
                investigationContext, decision, sources, correlationId, dispositions);
    }

    private void audit(String toolName, String auditedEntityType, Set<String> rejectedArguments,
                       String approvalId, String approverId, Optional<ExternalCorrelationId> externalId,
                       String subjectToken, String fingerprint, PrivacyContext context,
                       InvestigationContext investigationContext, String decision, List<SourceOutcome> sources,
                       String correlationId, Map<String, String> dispositions) {
        // Name and status only, and always the real source name: the audit trail
        // is an operational record for an operator, not a view a caller sees, so
        // it is never subject to SourceAliasing's capability check.
        Set<String> names = sources.stream()
                .map(outcome -> outcome.sourceName() + ":" + outcome.status())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        audit.record(new AuditEntry(investigationContext.principalId(), investigationContext.clientId(),
                toolName, auditedEntityType, subjectToken, fingerprint,
                context.redactionProfile(), context.scopeId(), context.purpose(),
                investigationContext.caseId(), decision, names, rejectedArguments,
                correlationId, dispositions, approvalId, approverId,
                externalId.map(ExternalCorrelationId::value).orElse("")));
    }
}
