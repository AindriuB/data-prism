package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
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
import java.util.Set;

/**
 * Builds a working pipeline by hand.
 *
 * <p>Wiring by constructor rather than by container, so the dependency direction
 * is visible in one place while it is still being argued about. The Spring Boot
 * starter that does this automatically is a later slice.
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

    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink) {
        this(adapters, clock, sink, "DEFAULT", "en");
    }

    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink,
                             String profile) {
        this(adapters, clock, sink, profile, "en");
    }

    public DataPrismAssembly(List<DataSourceAdapter<?>> adapters, Clock clock, AuditSink sink,
                             String profile, String localeTag) {
        SecretKeyProvider keys = StaticSecretKeyProvider.of(DEV_KEY);
        FieldMetadataResolver resolver = new DefaultFieldMetadataResolver();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve(localeTag);
        SyntheticValueSource synthetics = new HmacSyntheticGenerator(keys, vocabulary);
        PrivacyPolicyResolver policies = new ProfilePrivacyPolicyResolver(defaultProfiles());
        ValueTokenSource tokens = new HmacValueTokenSource(keys);
        ScrubbingEngine scrubber = new JsonTreeScrubbingEngine(resolver, policies, synthetics, tokens);
        LlmResponseValidator validator = new RawValueLeakValidator();

        this.orchestrator = new DefaultContextOrchestrator(adapters, scrubber, resolver, validator,
                synthetics, new ParameterFingerprinter(keys),
                new AuditRecorder(sink, clock, "example-1"),
                new SourceAliasing(tokens));

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
        // grant nothing here re-checks who is asking for.
        this.investigationContext = new InvestigationContext(
                "stdio-development", "stdio-development", "CASE-DEMO-1",
                Set.of(Capability.GET_ENTITY_CONTEXT));

        this.clock = clock;
    }

    private static java.util.Map<String, io.github.aindriub.dataprism.core.policy.PrivacyProfile>
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
}
