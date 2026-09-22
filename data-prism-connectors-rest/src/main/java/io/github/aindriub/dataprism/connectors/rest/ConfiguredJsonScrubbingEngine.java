package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SourceValues;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * against one shared {@link io.github.aindriub.dataprism.core.FieldMetadataResolver}
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

    public ConfiguredJsonScrubbingEngine(ScrubbingEngine javaFirst,
                                         Map<String, ConfiguredJsonSource> sources,
                                         PrivacyPolicyResolver policy,
                                         SyntheticValueSource synthetics,
                                         ValueTokenSource tokens) {
        this.javaFirst = Objects.requireNonNull(javaFirst, "javaFirst");
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
                throw new PrivacyRefusedException(first.code(), payload.sourceName() + first.path(),
                        "configured source raw-value leak check failed: " + check.violations().size()
                                + " violation(s)");
            }
            return scrubbed;
        }
        return javaFirst.scrub(source, context);
    }
}
