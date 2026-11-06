package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Describing a source by name: the engine stays the only authority, and the adapter supplies nothing (task 299). */
class DefaultContextOrchestratorDescribeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-299-test-key-not-for-any-real-data-32b");
    private static final ModelDescription ANSWER = new ModelDescription(
            List.of(new FieldProtection("id", FieldProtection.Protection.VERBATIM, null, null)),
            ModelDescription.Undeclared.REFUSE);

    private record Thing(String id) {
    }

    /** A Java-first engine: it only knows the {@code Class} form. */
    private static final class ClassFormEngine implements ScrubbingEngine {
        final List<Class<?>> asked = new ArrayList<>();

        @Override
        public ScrubResult scrub(Object source, PrivacyContext context) {
            throw new AssertionError("describe never scrubs");
        }

        @Override
        public Optional<ModelDescription> describe(Class<?> model, PrivacyContext context, boolean linksEnabled) {
            asked.add(model);
            return Optional.of(ANSWER);
        }
    }

    /** An engine that answers only the by-name form, as a configured engine does. */
    private static final class NameFormEngine implements ScrubbingEngine {
        final List<String> askedNames = new ArrayList<>();
        final List<Class<?>> askedModels = new ArrayList<>();
        boolean lastLinks;

        @Override
        public ScrubResult scrub(Object source, PrivacyContext context) {
            throw new AssertionError("describe never scrubs");
        }

        @Override
        public Optional<ModelDescription> describe(String sourceName, Class<?> model, PrivacyContext context,
                                                   boolean linksEnabled) {
            askedNames.add(sourceName);
            askedModels.add(model);
            lastLinks = linksEnabled;
            return Optional.of(ANSWER);
        }
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static DefaultContextOrchestrator orchestrator(ScrubbingEngine engine) {
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        LlmResponseValidator ok = (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        DataSourceAdapter<Thing> adapter = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "source-a";
            }

            @Override
            public Class<Thing> responseType() {
                return Thing.class;
            }

            @Override
            public Thing fetch(DataRequest request) {
                throw new AssertionError("describe never fetches");
            }
        };
        return new DefaultContextOrchestrator(
                List.of(adapter), engine, resolver, List.of(ok, new SensitivePatternValidator()),
                (subjectId, namespace, ctx) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(e -> { }, CLOCK, "test-299"), new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none(),
                OrchestrationOptions.defaults());
    }

    @Test
    @DisplayName("the default ScrubbingEngine.describe by name gives the Class form's answer for a Java-first engine")
    void defaultDelegatesToClassForm() {
        ClassFormEngine engine = new ClassFormEngine();

        assertThat(engine.describe("anything", Thing.class, context(), true)).contains(ANSWER);
        assertThat(engine.asked).containsExactly(Thing.class);
    }

    @Test
    @DisplayName("the default ScrubbingEngine.describe by name is empty for an engine that describes nothing")
    void defaultIsEmptyForEngineThatDescribesNothing() {
        ScrubbingEngine none = (source, ctx) -> {
            throw new AssertionError();
        };

        assertThat(none.describe("anything", Thing.class, context(), true)).isEmpty();
    }

    @Test
    @DisplayName("ContextOrchestrator's default by-name describe is empty, so test doubles do not change")
    void orchestratorDefaultIsEmpty() {
        ContextOrchestrator stub = (request, privacy, caller) -> {
            throw new AssertionError();
        };

        assertThat(stub.describe("source-a", context(), true)).isEmpty();
    }

    @Test
    @DisplayName("a known name is described by its scrubber, given the name and the adapter's response type")
    void knownNameIsDescribedByTheScrubber() {
        NameFormEngine engine = new NameFormEngine();

        Optional<ModelDescription> described = orchestrator(engine).describe("source-a", context(), true);

        assertThat(described).contains(ANSWER);
        assertThat(engine.askedNames).containsExactly("source-a");
        assertThat(engine.askedModels).containsExactly(Thing.class);
        assertThat(engine.lastLinks).isTrue();
    }

    @Test
    @DisplayName("a Java-first scrubber answers a known name through the Class form")
    void javaFirstScrubberAnswersKnownName() {
        ClassFormEngine engine = new ClassFormEngine();

        assertThat(orchestrator(engine).describe("source-a", context(), false)).contains(ANSWER);
        assertThat(engine.asked).containsExactly(Thing.class);
    }

    @Test
    @DisplayName("an unknown name is empty and the scrubber is not asked")
    void unknownNameIsEmpty() {
        NameFormEngine engine = new NameFormEngine();

        assertThat(orchestrator(engine).describe("elsewhere", context(), true)).isEmpty();
        assertThat(engine.askedNames).isEmpty();
    }
}
