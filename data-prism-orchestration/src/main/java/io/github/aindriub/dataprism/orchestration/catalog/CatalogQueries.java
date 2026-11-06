package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.link.CatalogPagePayload;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkBinding;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkLabelKind;
import io.github.aindriub.dataprism.core.link.LinkReason;
import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.link.SealResult;
import io.github.aindriub.dataprism.core.link.SealedLink;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@code list_sources} and {@code describe_source}: metadata only, no source is called (catalog.md sections 3
 * and 4, batch-and-bulk-follow.md Part A).
 *
 * <p>Authorisation, scope resolution, admission and the audit write are the tool's. This class decides the
 * answer and says what to audit. A page link is opened only through {@link LinkAttemptGate}, which owns the
 * lock and the counting; the MCP layer never sees a link type. Every body passes the response validators
 * before it is returned, and any failure to compute an answer is a refusal, never a partial body.
 *
 * <p>No client string reaches an {@link Outcome}'s audit parts: the labels, filters and page token a client
 * sent are never in a disposition, a source-system set or a message.
 */
public final class CatalogQueries {

    /** The code of every {@code describe_source} refusal, whatever the reason. */
    public static final String SOURCE_NOT_AVAILABLE = SourceSelection.NOT_AVAILABLE;
    /** The code when a response failed its validators or the served schema's caps; the orchestrator's own code. */
    public static final String RESPONSE_REFUSED = "VALIDATION_FAILED";
    /** The code of an unexpected failure: no validator ran, so it is not a validation failure. */
    public static final String REQUEST_FAILED = io.github.aindriub.dataprism.orchestration.AuditedRefusalException.REQUEST_FAILED;
    /** The code when a page link could not be issued, so the page is not served. */
    public static final String PAGING_UNAVAILABLE = "PAGING_UNAVAILABLE";
    /** The code for a refused page link that never reached the gate (no codec configured). */
    public static final String LINK_INVALID = "LINK_INVALID";

    /** The audit key of a description refusal. */
    static final String CATALOG_KEY = "catalog";
    /** The audit key of a page-link refusal. */
    static final String LINK_KEY = "link";

    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");
    private static final int MAX_LABEL_BYTES = 64;
    // The caps of the served output schemas (list_sources.output.json, describe_source.output.json).
    private static final int MAX_SOURCE_CHARS = 128;
    private static final int MAX_DESCRIPTION_CHARS = 240;
    private static final int MAX_INPUT_KEYS = 8;
    private static final int MAX_PATH_CHARS = 64;
    private static final Pattern ENTITY_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern KEY_NAMESPACE = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern VALUE_NAMESPACE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern FIELD_PATH =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\[\\])?(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\])?)*");
    private static final Comparator<String> BYTE_ORDER = (a, b) ->
            Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    /**
     * The link parts of paging. All three or none: without them a call returns the whole visible list.
     */
    public record PageLinks(LinkCodec codec, LinkKeys keys, LinkAttemptGate gate) {
        public PageLinks {
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(keys, "keys");
            Objects.requireNonNull(gate, "gate");
        }
    }

    /**
     * The arguments of a {@code list_sources} call, already checked against the schema patterns by the caller.
     *
     * @param entityType   exact filter; {@code null} when absent
     * @param keyNamespace exact filter; {@code null} when absent
     * @param pageSize     requested size; {@code null} when absent
     * @param page         the sealed link as sent; {@code null} when absent. Never logged or audited
     */
    public record ListRequest(String entityType, String keyNamespace, Integer pageSize, String page) {
        @Override
        public String toString() {
            return "ListRequest[redacted]";
        }
    }

    /**
     * The answer, and what to audit about it.
     *
     * @param body          the response body, or {@code null} on a refusal
     * @param refusalCode   the code the client sees, or {@code null} on success
     * @param sourceSystems the real names of the sources on the page, or of the described source
     * @param dispositions  audit-only keys ({@code link}, {@code catalog}); never client text
     * @param names         the effective label setting
     */
    public record Outcome(ObjectNode body, String refusalCode, Set<String> sourceSystems,
                          Map<String, String> dispositions, SourceCatalog.LabelKind names) {
        public Outcome {
            sourceSystems = Set.copyOf(sourceSystems);
            dispositions = Map.copyOf(dispositions);
        }

        public boolean refused() {
            return refusalCode != null;
        }
    }

    private record Row(String label, SourceCatalog.Entry entry) {
    }

    private final SourceCatalog catalog;
    private final SourceVisibility visibility;
    private final SourceAliasing aliasing;
    private final RealNamesPolicy realNames;
    private final ContextOrchestrator orchestrator;
    private final List<LlmResponseValidator> validators;
    private final PagingSettings paging;
    private final PageLinks links;
    private final boolean chainableEnabled;
    private final PrivacyMetrics metrics;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param orchestrator     describes a source by name, through the scrubber that scrubs it
     * @param links            {@code null} when no link codec is configured
     * @param chainableEnabled whether {@code dataprism.links.enabled} is true: only then does a description
     *                         carry {@code chainable}
     */
    public CatalogQueries(SourceCatalog catalog, SourceVisibility visibility, SourceAliasing aliasing,
                          RealNamesPolicy realNames, ContextOrchestrator orchestrator,
                          List<LlmResponseValidator> validators,
                          PagingSettings paging, PageLinks links, boolean chainableEnabled,
                          PrivacyMetrics metrics, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.aliasing = Objects.requireNonNull(aliasing, "aliasing");
        this.realNames = Objects.requireNonNull(realNames, "realNames");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.validators = List.copyOf(validators);
        if (this.validators.isEmpty()) {
            throw new IllegalArgumentException("at least one response validator is required");
        }
        this.paging = Objects.requireNonNull(paging, "paging");
        this.links = links;
        this.chainableEnabled = chainableEnabled;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PagingSettings paging() {
        return paging;
    }

    /** The effective label setting of a request; the tool records it on every event, a denial included. */
    public SourceCatalog.LabelKind names(InvestigationContext caller, PrivacyContext context) {
        return SourceLabels.effectiveKind(realNames, caller, context);
    }

    // ---- list_sources ----

    /**
     * @param tokenExpiry when the caller's token ends; {@code null} when it carries no expiry
     */
    public Outcome listSources(ListRequest request, InvestigationContext caller, PrivacyContext context,
                               Instant tokenExpiry, String correlationId) {
        SourceCatalog.LabelKind kind = names(caller, context);
        Map<String, String> dispositions = new LinkedHashMap<>();
        try {
            SourceLabels labels = SourceLabels.forRequest(catalog, visibility, aliasing, realNames, caller, context);
            return list(request, caller, context, tokenExpiry, correlationId, kind, labels, dispositions);
        } catch (SourceLabels.Collision collision) {
            return new Outcome(null, SourceLabels.Collision.CODE, collision.sources(), dispositions, kind);
        } catch (RuntimeException failure) {
            // Fail closed. The message of an unexpected failure is not copied anywhere.
            return refuse(REQUEST_FAILED, dispositions, kind);
        }
    }

    private Outcome list(ListRequest request, InvestigationContext caller, PrivacyContext context,
                         Instant tokenExpiry, String correlationId, SourceCatalog.LabelKind kind,
                         SourceLabels labels, Map<String, String> dispositions) {
        String entityType = request.entityType();
        String keyNamespace = request.keyNamespace();
        int size = paging.effective(request.pageSize());
        String after = null;

        if (request.page() != null) {
            if (links == null) {
                dispositions.put(LINK_KEY, LinkReason.MALFORMED.auditValue());
                return refuse(LINK_INVALID, dispositions, kind);
            }
            LinkBinding binding = binding(caller, context, context.expiresAt(), tokenExpiry);
            LinkAttemptGate.GateDecision decision =
                    links.gate().open(request.page(), binding, Set.of(LinkRelation.CATALOG_PAGE));
            if (decision instanceof LinkAttemptGate.Refusal refusal) {
                dispositions.put(LINK_KEY, refusal.auditValue());
                return refuse(refusal.clientCode(), dispositions, kind);
            }
            if (!(decision instanceof LinkAttemptGate.Proceed proceed)
                    || !(proceed.opened().payload() instanceof CatalogPagePayload payload)) {
                dispositions.put(LINK_KEY, LinkReason.WRONG_TOOL.auditValue());
                return refuse(LINK_INVALID, dispositions, kind);
            }
            // Every comparison is evaluated, and only then combined, so no early exit separates the reasons.
            boolean filterMismatch = !sameFilter(entityType, payload.entityTypeFilter())
                    || !sameFilter(keyNamespace, payload.keyNamespaceFilter())
                    || (request.pageSize() != null && size != payload.pageSize());
            boolean labelsChanged = payload.labelKind() != linkKind(kind);
            entityType = payload.entityTypeFilter().orElse(null);
            keyNamespace = payload.keyNamespaceFilter().orElse(null);
            size = payload.pageSize();
            List<Row> walk = visible(labels, entityType, keyNamespace);
            boolean catalogChanged;
            try {
                LinkKeys.ScopeKeys scopeKeys = links.keys().forScope(context.scopeId(), proceed.opened().keyId());
                byte[] tag = scopeKeys.catalogTag(walk.stream().map(Row::label).toList());
                catalogChanged = !MessageDigest.isEqual(tag, payload.catalogTag());
            } catch (RuntimeException keyFailure) {
                dispositions.put(LINK_KEY, LinkReason.KEY_UNAVAILABLE.auditValue());
                return refuse(LINK_INVALID, dispositions, kind);
            }
            LinkReason reason = filterMismatch ? LinkReason.FILTER_MISMATCH
                    : labelsChanged ? LinkReason.LABELS_CHANGED
                    : catalogChanged ? LinkReason.CATALOG_CHANGED : null;
            if (reason != null) {
                dispositions.put(LINK_KEY, reason.auditValue());
                return refuse(LINK_INVALID, dispositions, kind);
            }
            after = payload.lastLabel();
        }

        List<Row> rows = visible(labels, entityType, keyNamespace);
        int start = 0;
        if (after != null) {
            while (start < rows.size() && BYTE_ORDER.compare(rows.get(start).label(), after) <= 0) {
                start++;
            }
        }

        int end;
        boolean last;
        if (links == null) {
            // No codec: the whole visible list on one page, whatever pageSize says (batch OD-B2 (a)).
            end = rows.size();
            last = true;
            size = Math.max(1, rows.size());
        } else {
            int window = Math.min(rows.size(), start + size);
            if (window == rows.size()) {
                end = window;
                last = true;
            } else {
                end = lastFitting(rows, start, window);
                if (end < 0) {
                    end = firstFitting(rows, window);
                    if (end < 0) {
                        end = rows.size();
                    }
                }
                last = end == rows.size();
            }
        }
        List<Row> pageRows = rows.subList(start, end);

        Optional<String> next = Optional.empty();
        Optional<Instant> expiresAt = Optional.empty();
        if (!last) {
            Optional<SealedLink> sealed = seal(request, entityType,
                    keyNamespace, kind, size, pageRows.get(pageRows.size() - 1).label(), rows, caller, context,
                    tokenExpiry);
            if (sealed.isEmpty()) {
                return refuse(PAGING_UNAVAILABLE, dispositions, kind);
            }
            next = Optional.of(sealed.get().token());
            expiresAt = Optional.of(sealed.get().expiry());
        }

        CatalogPage page = new CatalogPage(kind, pageRows.stream().map(row -> entry(row, kind)).toList(), size,
                last, next, expiresAt);
        ObjectNode body = page.toJson(correlationId);
        if (!valid(body, context, kind)) {
            return refuse(RESPONSE_REFUSED, dispositions, kind);
        }
        metrics.increment(Metric.PAGING_PAGES);
        Set<String> real = new LinkedHashSet<>();
        pageRows.forEach(row -> real.add(row.entry().name()));
        return new Outcome(body, null, real, dispositions, kind);
    }

    private Optional<SealedLink> seal(
            ListRequest request, String entityType, String keyNamespace, SourceCatalog.LabelKind kind, int size,
            String lastLabel, List<Row> rows, InvestigationContext caller, PrivacyContext context,
            Instant tokenExpiry) {
        try {
            Instant now = clock.instant();
            Instant lifetimeEnd = now.plus(paging.lifetime());
            Instant scopeEnd = context.expiresAt() == null || context.expiresAt().isAfter(lifetimeEnd)
                    ? lifetimeEnd : context.expiresAt();
            byte[] id = new byte[8];
            random.nextBytes(id);
            byte[] tag = links.keys().forScope(context.scopeId())
                    .catalogTag(rows.stream().map(Row::label).toList());
            CatalogPagePayload payload = new CatalogPagePayload(id, now, Optional.ofNullable(entityType),
                    Optional.ofNullable(keyNamespace), linkKind(kind), size, lastLabel, tag);
            SealResult result = links.codec().sealDetailed(payload, binding(caller, context, scopeEnd, tokenExpiry));
            return result instanceof SealResult.Issued issued ? Optional.of(issued.link()) : Optional.empty();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private LinkBinding binding(InvestigationContext caller, PrivacyContext context, Instant scopeExpiry,
                                Instant tokenExpiry) {
        return new LinkBinding(context.scopeId(), caller.principalId(),
                scopeExpiry == null ? FAR_FUTURE : scopeExpiry, tokenExpiry == null ? FAR_FUTURE : tokenExpiry);
    }

    private static LinkLabelKind linkKind(SourceCatalog.LabelKind kind) {
        return kind == SourceCatalog.LabelKind.REAL ? LinkLabelKind.REAL : LinkLabelKind.ALIAS;
    }

    private static boolean sameFilter(String sent, Optional<String> linked) {
        return sent == null || linked.isPresent() && sent.equals(linked.get());
    }

    private static boolean fits(Row row) {
        return row.label().getBytes(StandardCharsets.UTF_8).length <= MAX_LABEL_BYTES;
    }

    /** The last row in {@code [start, window)} whose label a page link can carry, as an exclusive end; -1 if none. */
    private static int lastFitting(List<Row> rows, int start, int window) {
        for (int i = window - 1; i >= start; i--) {
            if (fits(rows.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The first row at or after {@code from} whose label fits, as an exclusive end; -1 if none. */
    private static int firstFitting(List<Row> rows, int from) {
        for (int i = from; i < rows.size(); i++) {
            if (fits(rows.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The permitted, filtered list in ascending byte order of label. */
    private List<Row> visible(SourceLabels labels, String entityType, String keyNamespace) {
        Map<String, String> labelOf = labels.permittedLabels();
        List<Row> rows = new ArrayList<>();
        labels.permittedEntries().forEach((name, entry) -> {
            if (entityType != null && !SourceLabels.serves(entry, entityType)) {
                return;
            }
            if (keyNamespace != null && entry.description().inputKeys().stream()
                    .noneMatch(key -> key.value().equals(keyNamespace))) {
                return;
            }
            rows.add(new Row(labelOf.get(name), entry));
        });
        rows.sort(Comparator.comparing(Row::label, BYTE_ORDER));
        return rows;
    }

    private static CatalogPage.Entry entry(Row row, SourceCatalog.LabelKind kind) {
        SourceDescription d = row.entry().description();
        return new CatalogPage.Entry(row.label(), row.entry().textFor(kind),
                new TreeSet<>(d.entityTypes()).stream().toList(),
                d.inputKeys().stream().map(KeyNamespace::value).toList(),
                Optional.ofNullable(d.cost()).map(Enum::name),
                Optional.ofNullable(d.freshness()).map(Enum::name));
    }

    // ---- describe_source ----

    public Outcome describeSource(String label, InvestigationContext caller, PrivacyContext context,
                                  String correlationId) {
        SourceCatalog.LabelKind kind = names(caller, context);
        Map<String, String> dispositions = new LinkedHashMap<>();
        SourceLabels labels;
        try {
            labels = SourceLabels.forRequest(catalog, visibility, aliasing, realNames, caller, context);
        } catch (SourceLabels.Collision collision) {
            return new Outcome(null, SourceLabels.Collision.CODE, collision.sources(), dispositions, kind);
        } catch (RuntimeException failure) {
            return unavailable("CATALOG_LABELS_FAILED", Set.of(), dispositions, kind);
        }
        SourceCatalog.Entry entry;
        switch (labels.resolve(label)) {
            case SourceLabels.Unresolved unresolved ->
                    { return unavailable(unresolved.reason().name(), Set.of(), dispositions, kind); }
            case SourceLabels.Found found -> entry = found.entry();
        }
        Set<String> real = Set.of(entry.name());
        try {
            Optional<ModelDescription> described = orchestrator.describe(entry.name(), context, chainableEnabled);
            if (described == null || described.isEmpty()) {
                return unavailable("CATALOG_DESCRIPTION_FAILED", real, dispositions, kind);
            }
            ObjectNode body = describeBody(correlationId, kind, labels.labelOf(entry.name()), entry,
                    described.get());
            if (!valid(body, context, kind)) {
                return unavailable("CATALOG_DESCRIPTION_REFUSED", real, dispositions, kind);
            }
            return new Outcome(body, null, real, dispositions, kind);
        } catch (RuntimeException failure) {
            return unavailable("CATALOG_DESCRIPTION_FAILED", real, dispositions, kind);
        }
    }

    private ObjectNode describeBody(String correlationId, SourceCatalog.LabelKind kind, String label,
                                    SourceCatalog.Entry entry, ModelDescription description) {
        SourceDescription d = entry.description();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("correlationId", correlationId);
        root.put("labels", kind.name());
        root.put("source", label);
        root.put("description", entry.textFor(kind));
        if (!d.entityTypes().isEmpty()) {
            ArrayNode types = root.putArray("entityTypes");
            new TreeSet<>(d.entityTypes()).forEach(types::add);
        }
        ArrayNode keys = root.putArray("inputKeys");
        d.inputKeys().forEach(key -> keys.add(key.value()));
        if (d.cost() != null) {
            root.put("cost", d.cost().name());
        }
        if (d.freshness() != null) {
            root.put("freshness", d.freshness().name());
        }
        root.put("undeclared", description.undeclared().name());
        ArrayNode fields = root.putArray("fields");
        for (FieldProtection field : description.fields()) {
            ObjectNode node = fields.addObject();
            node.put("path", field.path());
            node.put("protection", field.protection().name());
            boolean valued = field.protection() == FieldProtection.Protection.SYNTHETIC
                    || field.protection() == FieldProtection.Protection.TOKEN;
            if (valued && field.valueNamespace() != null) {
                node.put("valueNamespace", field.valueNamespace());
            }
            if (chainableEnabled && field.keyNamespace() != null) {
                node.put("chainable", true);
                node.put("keyNamespace", field.keyNamespace());
            }
        }
        return root;
    }

    // ---- shared ----

    /** The one client answer for every description failure; the reason goes to the audit only. */
    private static Outcome unavailable(String reason, Set<String> sources, Map<String, String> dispositions,
                                       SourceCatalog.LabelKind kind) {
        dispositions.put(CATALOG_KEY, "REFUSED:" + reason);
        return new Outcome(null, SOURCE_NOT_AVAILABLE, sources, dispositions, kind);
    }

    private static Outcome refuse(String code, Map<String, String> dispositions, SourceCatalog.LabelKind kind) {
        return new Outcome(null, code, Set.of(), dispositions, kind);
    }

    /**
     * The body fits the served schema, and every validator passes over the whole of it; any failure, or any
     * throw, withholds it. The schema is checked here so that a body the SDK would reject is refused before
     * the audit write, not audited as served.
     *
     * <p>The correlation id, and under {@code ALIAS} the labels, are values this server generated, so they
     * are the validators' {@code emitted} set, which matches by exact value. A real name is the operator's
     * and is scanned like any other text: one that looks like an identifier refuses the response.
     */
    private boolean valid(ObjectNode body, PrivacyContext context, SourceCatalog.LabelKind kind) {
        if (!withinServedSchema(body)) {
            return false;
        }
        Set<String> emitted = new LinkedHashSet<>();
        addText(emitted, body.get("correlationId"));
        if (kind == SourceCatalog.LabelKind.ALIAS) {
            addText(emitted, body.get("source"));
            if (body.get("sources") instanceof ArrayNode sources) {
                sources.forEach(entry -> addText(emitted, entry.get("source")));
            }
        }
        boolean ok = true;
        for (LlmResponseValidator validator : validators) {
            try {
                ValidationResult result = validator.validate(body, Set.of(), emitted, context);
                ok &= result != null && result.valid();
            } catch (RuntimeException failure) {
                ok = false;
            }
        }
        return ok;
    }

    private static void addText(Set<String> into, tools.jackson.databind.JsonNode node) {
        if (node != null && node.isString()) {
            into.add(node.asString());
        }
    }

    private static boolean withinServedSchema(ObjectNode body) {
        if (body.get("sources") instanceof ArrayNode sources) {
            for (tools.jackson.databind.JsonNode entry : sources) {
                if (!entryWithinSchema(entry)) {
                    return false;
                }
            }
        } else if (!entryWithinSchema(body)) {
            return false;
        }
        if (body.get("fields") instanceof ArrayNode fields) {
            for (tools.jackson.databind.JsonNode field : fields) {
                String path = textOf(field.get("path"));
                if (path == null || path.codePointCount(0, path.length()) > MAX_PATH_CHARS
                        || !FIELD_PATH.matcher(path).matches()) {
                    return false;
                }
                String valueNamespace = textOf(field.get("valueNamespace"));
                String keyNamespace = textOf(field.get("keyNamespace"));
                if (field.has("valueNamespace") && (valueNamespace == null
                        || !VALUE_NAMESPACE.matcher(valueNamespace).matches())) {
                    return false;
                }
                if (field.has("keyNamespace") && (keyNamespace == null
                        || !KEY_NAMESPACE.matcher(keyNamespace).matches())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** One source's part of a body: a list entry, or the root of a description. */
    private static boolean entryWithinSchema(tools.jackson.databind.JsonNode entry) {
        String source = textOf(entry.get("source"));
        String description = textOf(entry.get("description"));
        if (source == null || description == null
                || !between(source, MAX_SOURCE_CHARS) || !between(description, MAX_DESCRIPTION_CHARS)) {
            return false;
        }
        if (entry.get("entityTypes") instanceof ArrayNode types) {
            for (tools.jackson.databind.JsonNode type : types) {
                String text = textOf(type);
                if (text == null || !ENTITY_TYPE.matcher(text).matches()) {
                    return false;
                }
            }
        }
        if (entry.get("inputKeys") instanceof ArrayNode keys) {
            if (keys.size() > MAX_INPUT_KEYS) {
                return false;
            }
            for (tools.jackson.databind.JsonNode key : keys) {
                String text = textOf(key);
                if (text == null || !KEY_NAMESPACE.matcher(text).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean between(String text, int max) {
        int length = text.codePointCount(0, text.length());
        return length >= 1 && length <= max;
    }

    private static String textOf(tools.jackson.databind.JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}
