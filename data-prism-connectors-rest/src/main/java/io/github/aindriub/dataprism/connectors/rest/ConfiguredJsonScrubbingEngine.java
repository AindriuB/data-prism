package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.engine.ChainableField;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.policy.EffectivePrivacyPolicy;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import tools.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.refusal.RefusalPaths;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.engine.SourceValues;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The one {@link ScrubbingEngine} a deployment that mixes Java-first and
 * configuration-driven JSON sources actually installs.
 *
 * <p>It does not reimplement scrubbing. A {@link ConfiguredJsonPayload} is
 * routed to a plain {@link JsonTreeScrubbingEngine} built for that one source —
 * the identical class that scrubs every Java-first model in this platform,
 * constructed with a {@link ConfiguredJsonFieldMetadataResolver} instead of one
 * backed by annotations. Anything else is a Java-first result and goes to the
 * delegate engine unchanged. Either way, every field decision still passes
 * through the same {@link PrivacyPolicyResolver}, the same {@link
 * SyntheticValueSource} and the same {@link ValueTokenSource} the deployment
 * wired up once: this class only chooses which catalogue applies, never what a
 * classified field's value becomes.
 *
 * <p>A configured JSON result also gets its own raw-value leak check, run here
 * rather than left to the orchestrator's. {@link
 * io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator} builds
 * its prohibited-value set by resolving the fetched record's own {@code Class}
 * against one shared {@link io.github.aindriub.dataprism.core.spi.FieldMetadataResolver}
 * — correct for a Java-first model, but a {@link ConfiguredJsonPayload} is one
 * compiled wrapper shared by every configured source, so that resolution cannot
 * recover a specific source's catalogue from the wrapper's class the way it can
 * from an annotated model's. Computing the prohibited set here instead, against
 * the correct per-source resolver and the actual response body rather than the
 * wrapper, closes that gap before it ever reaches the orchestrator's own check.
 */
public final class ConfiguredJsonScrubbingEngine implements ScrubbingEngine {

    private final ScrubbingEngine javaFirst;
    private final Map<String, JsonTreeScrubbingEngine> engines;
    private final Map<String, ConfiguredJsonFieldMetadataResolver> resolvers;
    private final Map<String, ConfiguredJsonSource> sources;
    private final RawValueLeakValidator leakCheck = new RawValueLeakValidator();
    private final PrivacyPolicyResolver policy;
    private final SyntheticValueSource synthetics;
    private final ValueTokenSource tokens;

    /** Stands for the enclosing record's subject when a nested catalogue is described on its own. */
    private static final String SUBJECT_STAND_IN = "$subject";

    public ConfiguredJsonScrubbingEngine(ScrubbingEngine javaFirst,
                                         Map<String, ConfiguredJsonSource> sources,
                                         PrivacyPolicyResolver policy,
                                         SyntheticValueSource synthetics,
                                         ValueTokenSource tokens) {
        this.javaFirst = Objects.requireNonNull(javaFirst, "javaFirst");
        this.policy = policy;
        this.synthetics = synthetics;
        this.tokens = tokens;
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(synthetics, "synthetics");
        Objects.requireNonNull(tokens, "tokens");

        Map<String, JsonTreeScrubbingEngine> builtEngines = new LinkedHashMap<>();
        Map<String, ConfiguredJsonFieldMetadataResolver> builtResolvers = new LinkedHashMap<>();
        for (Map.Entry<String, ConfiguredJsonSource> entry : sources.entrySet()) {
            ConfiguredJsonFieldMetadataResolver resolver =
                    new ConfiguredJsonFieldMetadataResolver(entry.getValue());
            builtResolvers.put(entry.getKey(), resolver);
            builtEngines.put(entry.getKey(), new JsonTreeScrubbingEngine(resolver, policy, synthetics, tokens));
        }
        this.engines = Map.copyOf(builtEngines);
        this.resolvers = Map.copyOf(builtResolvers);
        this.sources = Map.copyOf(sources);
    }

    /** A Java-first model is described by the delegate; a configured source is described by name, below. */
    @Override
    public Optional<ModelDescription> describe(Class<?> model, PrivacyContext context, boolean linksEnabled) {
        if (model == ConfiguredJsonPayload.class) {
            // One compiled wrapper serves every configured source, so the class does not say which.
            return Optional.empty();
        }
        return javaFirst.describe(model, context, linksEnabled);
    }

    /**
     * Describes one configured source from its {@code fields:} catalogue and nested catalogues, by the
     * plan the scrubber applies: the same policy resolver, the same token-source check and the same
     * {@link JsonTreeScrubbingEngine#describe} rules as a Java-first model.
     *
     * <p>A catalogue does not say whether a property arrives as one value or as a list, and the scrubber
     * protects both the same way, so each property is described as {@code name} and as {@code name[]}, and
     * a nested property likewise, as {@code name.child}, {@code name[].child} and their list forms. Only an identifier entry with
     * {@code chainable: true} carries a key namespace, and only while {@code linksEnabled}.
     *
     * @return empty for an unknown source or when the plan cannot be described (fail closed)
     */
    public Optional<ModelDescription> describe(String sourceName, PrivacyContext context, boolean linksEnabled) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(context, "context");
        ConfiguredJsonSource source = sources.get(sourceName);
        if (source == null) {
            return Optional.empty();
        }
        Map<String, FieldMetadata> nestedPointers = new LinkedHashMap<>();
        List<FieldMetadata> own = new ArrayList<>();
        for (FieldMetadata md : source.fields().values()) {
            String catalogue = nestedCatalogueName(md);
            if (catalogue == null) {
                own.add(md);
            } else {
                nestedPointers.put(catalogue, md);
            }
        }

        Map<String, FieldProtection> found = new TreeMap<>();
        ModelDescription.Undeclared undeclared;
        Optional<ModelDescription> root = describeCatalogue(own, false, context);
        Optional<ModelDescription> rootAsList = describeCatalogue(own, true, context);
        if (root.isEmpty() || rootAsList.isEmpty()) {
            return Optional.empty();
        }
        undeclared = root.get().undeclared();
        root.get().fields().forEach(f -> found.put(f.path(), f));
        rootAsList.get().fields().forEach(f -> found.put(f.path(), f));

        for (FieldMetadata pointer : nestedPointers.values()) {
            String catalogue = nestedCatalogueName(pointer);
            EffectivePrivacyPolicy field = this.policy.resolve(pointer, context);
            if (!field.allowed()) {
                // The scrubber refuses a response carrying this field, so nothing under it is described.
                continue;
            }
            if (field.action() == PrivacyAction.REMOVE) {
                continue;
            }
            List<FieldMetadata> inner = new ArrayList<>(source.nestedCatalogues().get(catalogue).values());
            Optional<ModelDescription> asObject = describeCatalogue(inner, false, context);
            Optional<ModelDescription> asList = describeCatalogue(inner, true, context);
            if (asObject.isEmpty() || asList.isEmpty()) {
                return Optional.empty();
            }
            String name = pointer.fieldName();
            for (String prefix : List.of(name + ".", name + "[].")) {
                for (ModelDescription inside : List.of(asObject.get(), asList.get())) {
                    inside.fields().forEach(f -> found.put(prefix + f.path(), withPath(f, prefix + f.path())));
                }
            }
        }

        if (linksEnabled) {
            for (ChainableField chainable : source.chainableFields()) {
                FieldMetadata md = source.fields().get(chainable.path());
                EffectivePrivacyPolicy chosen = this.policy.resolve(md, context);
                if (!chosen.allowed()) {
                    continue;
                }
                FieldProtection existing = found.get(chainable.path());
                Protection protection = existing != null ? existing.protection()
                        : chosen.action() == PrivacyAction.REMOVE ? Protection.ABSENT : null;
                if (protection != null) {
                    found.put(chainable.path(), new FieldProtection(chainable.path(), protection,
                            existing == null ? null : existing.valueNamespace(), chainable.keyNamespace().value()));
                }
            }
        }
        return Optional.of(new ModelDescription(List.copyOf(found.values()), undeclared));
    }

    private static FieldProtection withPath(FieldProtection f, String path) {
        return new FieldProtection(path, f.protection(), f.valueNamespace(), f.keyNamespace());
    }

    private static String nestedCatalogueName(FieldMetadata md) {
        String reason = md.nonSensitiveReason();
        return reason != null && reason.startsWith(ConfiguredJsonSources.NESTED_FIELD_REASON_PREFIX)
                ? reason.substring(ConfiguredJsonSources.NESTED_FIELD_REASON_PREFIX.length())
                : null;
    }

    /**
     * One flat catalogue described by the core describer. A nested catalogue has no identifier of its
     * own and inherits its subject, so a stand-in identifier gives it one, and is dropped from the result.
     *
     * @param asList describe each property as the elements of a list rather than as one value
     */
    private Optional<ModelDescription> describeCatalogue(List<FieldMetadata> fields, boolean asList,
                                                          PrivacyContext context) {
        List<FieldMetadata> shaped = new ArrayList<>();
        boolean hasSubject = false;
        for (FieldMetadata md : fields) {
            hasSubject |= md.internalIdentifier();
            shaped.add(asList ? new FieldMetadata(md.fieldName(), md.identifier(), md.subjectRole(),
                    md.classifications(), md.namespace(), md.suggestedAction(), md.subjectField(),
                    md.nonSensitiveReason(), List.class, md.valueType()) : md);
        }
        if (!hasSubject) {
            shaped.add(new FieldMetadata(SUBJECT_STAND_IN, true, FieldMetadata.SELF, List.of(),
                    PrivacyNamespace.NONE, null, "", null, String.class, null));
        }
        FieldMetadataResolver flat = new FlatResolver(shaped);
        Optional<ModelDescription> described = new JsonTreeScrubbingEngine(flat, policy, synthetics, tokens)
                .describe(ObjectNode.class, context, false);
        return described.map(d -> new ModelDescription(
                d.fields().stream().filter(f -> !f.path().startsWith(SUBJECT_STAND_IN)).toList(),
                d.undeclared()));
    }

    /** One flat list of fields for any type, which is all the core describer asks of a configured source. */
    private record FlatResolver(List<FieldMetadata> fields) implements FieldMetadataResolver {
        @Override
        public List<FieldMetadata> resolve(Class<?> type) {
            return fields;
        }

        @Override
        public boolean exposed(Class<?> type) {
            return true;
        }

        @Override
        public boolean descendable(Class<?> type) {
            return false;
        }
    }

    @Override
    public ScrubResult scrub(Object source, PrivacyContext context) {
        if (source instanceof ConfiguredJsonPayload payload) {
            JsonTreeScrubbingEngine engine = engines.get(payload.sourceName());
            ConfiguredJsonFieldMetadataResolver resolver = resolvers.get(payload.sourceName());
            if (engine == null || resolver == null) {
                // Reachable only if a payload's source name was never part of the
                // catalogue this engine was built from -- never from configuration,
                // since every configured adapter and this engine are built from the
                // same parsed sources together.
                throw new PrivacyRefusedException("UNKNOWN_CONFIGURED_SOURCE", payload.sourceName(),
                        "no reviewed catalogue is registered for this source");
            }

            ConfiguredJsonNestedLeafShapeGuard.check(payload.sourceName(), payload.body(),
                    sources.get(payload.sourceName()).fields(), resolver);

            ScrubResult scrubbed = engine.scrub(payload.body(), context);
            Set<String> prohibited = SourceValues.prohibited(payload.body(), resolver);
            ValidationResult check = leakCheck.validate(scrubbed.tree(), prohibited, scrubbed.emitted(), context);
            if (!check.valid()) {
                Violation first = check.violations().get(0);
                // The validator walked the scrubbed tree, which can hold payload keys the
                // catalogue never declared; only declared segments may reach the path.
                throw new PrivacyRefusedException(first.code(),
                        payload.sourceName() + RefusalPaths.redact(first.path(), scrubbed.dispositions().keySet()),
                        "configured source raw-value leak check failed: " + check.violations().size()
                                + " violation(s)");
            }
            return scrubbed;
        }
        return javaFirst.scrub(source, context);
    }
}
