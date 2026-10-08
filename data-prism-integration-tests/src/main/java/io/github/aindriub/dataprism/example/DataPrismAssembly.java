package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the fixture-only stdio pipeline by hand.
 *
 * <p>The protected HTTP application consumes the Spring Boot starter instead.
 * This assembly remains only because stdio deliberately starts no Spring
 * context: framework banners and console logging would corrupt its protocol.
 */
public final class DataPrismAssembly {

    /**
     * Development key only, and the reason it can be a constant is that nothing
     * it protects is real. A production deployment supplies key material through
     * a {@link SecretKeyProvider} backed by its own secret manager; a key in
     * source is a key in version control.
     */
    private static final String DEV_KEY = "development-only-key-not-for-any-real-data";

    private final ContextOrchestrator orchestrator;
    private final PrivacyContext privacyContext;
    private final InvestigationContext investigationContext;
    private final PseudonymisationVersion pseudonymisationVersion;
    private final Clock clock;
    private final ParameterFingerprinter fingerprinter;

    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink) {
        this(adapters, clock, sink, "DEFAULT", "en");
    }

    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink,
                             String profile) {
        this(adapters, clock, sink, profile, "en");
    }

    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink,
                             String profile, String localeTag) {
        this(adapters, clock, sink, profile, localeTag, defaultProfiles());
    }

    /** The default profile and locale, with {@code mdc} opened on each source's fetch thread. */
    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink,
                             CorrelationMdc mdc) {
        this(adapters, clock, sink, "DEFAULT", "en", defaultProfiles(), mdc);
    }

    /** As above, with the named profiles supplied rather than loaded from the shipped defaults. */
    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink,
                             String profile, String localeTag,
                             Map<String, PrivacyProfile> profiles) {
        this(adapters, clock, sink, profile, localeTag, profiles, CorrelationMdc.off());
    }

    /**
     * As above, with {@code mdc} opened on each source's fetch thread, so a test can install a recording
     * {@code MDCAdapter} through {@link CorrelationMdc#of(String, org.slf4j.spi.MDCAdapter)}.
     */
    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink,
                             String profile, String localeTag,
                             Map<String, PrivacyProfile> profiles, CorrelationMdc mdc) {
        SecretKeyProvider keys = StaticSecretKeyProvider.of(DEV_KEY);
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve(localeTag);
        SyntheticValueSource synthetics = new HmacSyntheticGenerator(keys, vocabulary);
        PrivacyPolicyResolver policies = new ProfilePrivacyPolicyResolver(profiles);
        ValueTokenSource tokens = new HmacValueTokenSource(keys);
        ScrubbingEngine scrubber = new JsonTreeScrubbingEngine(resolver, policies, synthetics, tokens);
        LlmResponseValidator validator = new RawValueLeakValidator();

        this.fingerprinter = new ParameterFingerprinter(keys);

        // The standard pipeline, spelled out so the fan-out can carry the MDC.
        this.orchestrator = new DefaultContextOrchestrator(adapters, scrubber, resolver,
                List.of(validator, new SensitivePatternValidator()), synthetics, fingerprinter,
                new AuditRecorder(sink, clock, "example-1"), new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults().withMdc(mdc)),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(tokens), PrivacyMetrics.none());

        this.pseudonymisationVersion =
                PseudonymisationVersion.HMAC_SHA256_V1.withVocabulary(vocabulary.id());

        // S0 hardcodes the scope. In production every field here derives from the
        // authenticated session, and a caller that supplies its own is ignored
        // and audited — that is what stops one investigation reading another's
        // pseudonyms.
        this.privacyContext = new PrivacyContext(
                "CASE-DEMO-1",
                PrivacyScopeType.INVESTIGATION,
                profile,
                "demonstration",
                Instant.now(clock).plus(8, ChronoUnit.HOURS),
                this.pseudonymisationVersion);

        // The single-principal development mode the plan settled on: one caller
        // for every request, until a real session exists to derive it from
        // (task 06). No EXPOSE_SOURCE_NAMES: this factory hands the context to
        // anything that asks (WorkedExampleTest, EndToEndTest), so it mints the
        // same masked default a real deployment ships, rather than an unmasking
        // grant nothing here re-checks who is asking for. GET_ENTITY_CONTEXT and
        // COMPARE_ENTITY_SOURCES only, mirroring ExampleApplication's shipped
        // developer role.
        this.investigationContext = new InvestigationContext(
                "stdio-development", "stdio-development", "CASE-DEMO-1",
                Set.of(Capability.GET_ENTITY_CONTEXT, Capability.COMPARE_ENTITY_SOURCES));

        this.clock = clock;
    }

    private static Map<String, PrivacyProfile>
            defaultProfiles() {
        try (var in = DataPrismAssembly.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return PrivacyProfiles.fromYaml(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("default privacy profiles could not be loaded", e);
        }
    }

    public static DataPrismAssembly standard() {
        return new DataPrismAssembly(List.of(new StubCustomerAdapter(),
                        new StubAccountAdapter(), new StubOrderAdapter()),
                Clock.systemUTC(), new Slf4jAuditSink());
    }

    public ContextOrchestrator orchestrator() {
        return orchestrator;
    }

    public PrivacyContext privacyContext() {
        return privacyContext;
    }

    /** The single caller of the development mode described on the constructor. */
    public InvestigationContext investigationContext() {
        return investigationContext;
    }

    public PseudonymisationVersion pseudonymisationVersion() {
        return pseudonymisationVersion;
    }

    public Clock clock() {
        return clock;
    }

    /** The fingerprinter the orchestrator uses; a tool's approval binding must use the same key. */
    public ParameterFingerprinter parameterFingerprinter() {
        return fingerprinter;
    }
}
