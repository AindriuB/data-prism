package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
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
        ScrubbingEngine scrubber = new JsonTreeScrubbingEngine(resolver, policies, synthetics,
                new HmacValueTokenSource(keys));
        LlmResponseValidator validator = new RawValueLeakValidator();

        this.orchestrator = new DefaultContextOrchestrator(adapters, scrubber, resolver, validator,
                synthetics, new ParameterFingerprinter(keys),
                new AuditRecorder(sink, clock, "example-1"));

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
                PseudonymisationVersion.HMAC_SHA256_V1.withVocabulary(vocabulary.id()));
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
}
