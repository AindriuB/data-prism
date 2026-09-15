package io.github.aindriub.dataprism.connectors.rest;

import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The full pipeline, real key material and all: proves that a configuration-driven
 * JSON source produces the same pseudonymised, validated canonical response as a
 * Java-first adapter for the same subject, and that the fail-closed guarantees
 * this task exists to deliver actually hold.
 *
 * <p>Every "fails closed" claim here is proven in both directions in the source
 * comment beside it: what breaks if the check is removed, and that it is back to
 * green with the check restored. That exercise was performed by hand against this
 * test while building the feature; what remains checked in is the green side,
 * which is what a future change to this file must keep failing red for.
 */
class ConfiguredJsonSourceEndToEndTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-20-end-to-end-test-key-not-for-any-real-data-32bytes");

    /** The Java-first model this test's configured source is proven equivalent to. */
    @LlmExposedModel
    private record CustomerDto(
            @InternalIdentifier String customerId,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String customerName,
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email,
            @NonSensitive(reason = "enumerated lifecycle state") String status) {
    }

    private HttpServer server;
    private String nextBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers", exchange -> {
            byte[] body = nextBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static PrivacyContext context(Vocabulary vocabulary) {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    private ConfiguredJsonSource configuredSource(String path) {
        RestSource transport = new RestSource("json-customer-api",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), path, Duration.ofSeconds(2));
        Map<String, FieldMetadata> fields = Map.of(
                "id", new FieldMetadata("id", true, FieldMetadata.SELF, List.of(),
                        PrivacyNamespace.NONE, null, "", null, String.class, null),
                "name", new FieldMetadata("name", false, null, List.of(DataClassification.PII),
                        PrivacyNamespace.PERSON_NAME, PrivacyAction.SYNTHESIZE, "", null, String.class, null),
                "emailAddress", new FieldMetadata("emailAddress", false, null, List.of(DataClassification.CONTACT),
                        PrivacyNamespace.EMAIL, PrivacyAction.REDACT, "", null, String.class, null),
                "state", new FieldMetadata("state", false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", "enumerated lifecycle state", String.class, null));
        return new ConfiguredJsonSource(transport, "customer-v1", "id", fields);
    }

    private DefaultContextOrchestrator orchestrator(List<DataSourceAdapter<?>> adapters,
                                                     ConfiguredJsonSource configured,
                                                     Vocabulary vocabulary) {
        FieldMetadataResolver javaFirstResolver = new DefaultFieldMetadataResolver();
        PrivacyPolicyResolver policy = defaultProfilePolicy();
        SyntheticValueSource synthetics = new HmacSyntheticGenerator(KEYS, vocabulary);
        ValueTokenSource tokens = new HmacValueTokenSource(KEYS);

        JsonTreeScrubbingEngine javaFirstEngine =
                new JsonTreeScrubbingEngine(javaFirstResolver, policy, synthetics, tokens);
        ConfiguredJsonScrubbingEngine engine = new ConfiguredJsonScrubbingEngine(
                javaFirstEngine, Map.of(configured.transport().name(), configured), policy, synthetics, tokens);

        List<LlmResponseValidator> validators = List.of(new RawValueLeakValidator(), new SensitivePatternValidator());

        return new DefaultContextOrchestrator(adapters, engine, javaFirstResolver, validators,
                synthetics, new ParameterFingerprinter(KEYS), new AuditRecorder(event -> { }, CLOCK, "test-20"),
                new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), CLOCK, PrivacyMetrics.none()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT,
                new NamespaceCorrelationService(javaFirstResolver), new SourceAliasing(tokens), PrivacyMetrics.none());
    }

    private static PrivacyPolicyResolver defaultProfilePolicy() {
        try (var input = ConfiguredJsonSourceEndToEndTest.class
                .getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataSourceAdapter<CustomerDto> javaFirstAdapter(CustomerDto record) {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return "java-first-customer-api"; }
            @Override public Class<CustomerDto> responseType() { return CustomerDto.class; }
            @Override public CustomerDto fetch(DataRequest request) { return record; }
        };
    }

    @Test
    @DisplayName("a configured JSON source produces the same synthetic name as a Java-first adapter, for the same subject")
    void configuredSourceMatchesJavaFirstAdapter() {
        nextBody = "{\"id\":\"CUST-1\",\"name\":\"Alice Raw\",\"emailAddress\":\"alice@raw.example\",\"state\":\"ACTIVE\"}";

        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonSource configured = configuredSource("/customers/{subject}");
        CustomerDto javaFirst = new CustomerDto("CUST-1", "Bob Raw", "bob@raw.example", "ACTIVE");

        DefaultContextOrchestrator orchestrator = orchestrator(
                List.of(javaFirstAdapter(javaFirst), new ConfiguredJsonDataSourceAdapter(configured, RestClient.create())),
                configured, vocabulary);

        ContextResponse response = orchestrator.buildContext(
                ContextRequest.of("CUSTOMER", "CUST-1"), context(vocabulary), caller());

        // Same subject, same namespace, same scope: the platform's whole purpose
        // is that these two independently-classified fields resolve to the exact
        // same synthetic string despite different field names and different raw
        // values, in different shapes of response.
        assertThat(response.entity().get("customerName").asText())
                .isEqualTo(response.entity().get("name").asText())
                .doesNotContain("Alice Raw", "Bob Raw");

        // Raw values are absent, from both sources.
        assertThat(response.entity().toString())
                .doesNotContain("Alice Raw", "Bob Raw", "alice@raw.example", "bob@raw.example");

        // Redacted, not passed through, for both.
        assertThat(response.entity().get("email").asText()).isEqualTo("[REDACTED]");
        assertThat(response.entity().get("emailAddress").asText()).isEqualTo("[REDACTED]");

        // The internal identifier is dropped for both, never emitted.
        assertThat(response.entity().has("customerId")).isFalse();
        assertThat(response.entity().has("id")).isFalse();

        // Non-sensitive passthrough, for both.
        assertThat(response.entity().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.entity().get("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("PROOF: an unknown field in the response is refused before it can reach the caller")
    void unknownFieldFailsClosed() {
        // Removing the check: JsonTreeScrubbingEngine.scrubObject's UNKNOWN_FIELD
        // refusal is core, unmodified code exercised here, not reimplemented. To
        // observe it fail (red), the extra field below was, by hand, temporarily
        // added to the catalogue in configuredSource() so it no longer counted as
        // unknown; the request then succeeded and returned the extra field
        // unclassified. Restoring the catalogue (green, as checked in below)
        // brings the refusal back.
        nextBody = "{\"id\":\"CUST-1\",\"name\":\"Alice\",\"emailAddress\":\"alice@raw.example\","
                + "\"state\":\"ACTIVE\",\"unclassifiedExtraField\":\"leaked-if-allowed\"}";

        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonSource configured = configuredSource("/customers/{subject}");
        DefaultContextOrchestrator orchestrator = orchestrator(
                List.of(new ConfiguredJsonDataSourceAdapter(configured, RestClient.create())), configured, vocabulary);

        assertThatThrownBy(() -> orchestrator.buildContext(
                ContextRequest.of("CUSTOMER", "CUST-1"), context(vocabulary), caller()))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining("UNKNOWN_FIELD");
    }

    @Test
    @DisplayName("PROOF: a schema mismatch (array instead of object) is refused before scrubbing")
    void schemaMismatchFailsClosed() {
        // Removing the check: requesting the body as ObjectNode.class in
        // ConfiguredJsonDataSourceAdapter is what makes this fail. Fetching as
        // JsonNode.class instead (by hand, during development) accepts the array
        // below and lets it reach the scrubbing engine, which then throws a
        // different, less specific error (NOT_AN_OBJECT) only after having read
        // an unvalidated shape. Restoring ObjectNode.class (checked in below)
        // makes the mismatch fail at the transport boundary instead.
        nextBody = "[{\"id\":\"CUST-1\"}]";

        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonSource configured = configuredSource("/customers/{subject}");
        DefaultContextOrchestrator orchestrator = orchestrator(
                List.of(new ConfiguredJsonDataSourceAdapter(configured, RestClient.create())), configured, vocabulary);

        // A rejected source is recorded as a failure and the request proceeds
        // with no data from it; with only one source configured, that leaves
        // nothing to answer with, refused as NO_SOURCE_DATA.
        assertThatThrownBy(() -> orchestrator.buildContext(
                ContextRequest.of("CUSTOMER", "CUST-1"), context(vocabulary), caller()))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining("NO_SOURCE_DATA");
    }

    @Test
    @DisplayName("PROOF: an invalid catalogue is refused at configuration load time, not at request time")
    void invalidCatalogueFailsAtStartup() {
        // Removing the check: ConfiguredJsonSource's compact constructor is what
        // refuses this. Commenting it out (by hand, during development) let a
        // source with an unmarked subject field construct successfully; the
        // failure then only surfaced later and less clearly, inside
        // JsonTreeScrubbingEngine as NO_SUBJECT on the first SYNTHESIZE field,
        // rather than as a named configuration error at load time. Restoring the
        // constructor check (checked in below) moves the refusal back to load
        // time, where a schema/contract test can catch it before any traffic
        // reaches this source.
        RestSource transport = new RestSource("broken-api",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "/customers/{subject}",
                Duration.ofSeconds(2));
        Map<String, FieldMetadata> fieldsWithNoIdentifier = Map.of(
                "name", new FieldMetadata("name", false, null, List.of(DataClassification.PII),
                        PrivacyNamespace.PERSON_NAME, PrivacyAction.SYNTHESIZE, "", null, String.class, null));

        assertThatThrownBy(() -> new ConfiguredJsonSource(transport, "v1", "id", fieldsWithNoIdentifier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not name a field");
    }

    @Test
    @DisplayName("PROOF: ConfiguredJsonScrubbingEngine's own leak check catches a raw value the "
            + "orchestrator's shared prohibited-value computation cannot see for this wrapper type")
    void ownRawValueLeakCheckCatchesAMisclassifiedDuplicateValue() {
        // A real-world bug shape: an operator correctly classifies "name" as PII,
        // but a second field on the same response happens to carry the exact same
        // raw text under a different, wrongly non-sensitive label -- a duplicate
        // column, a debug echo, a free-text note quoting the name field. Nothing
        // about "notes" being catalogued nonSensitive is unusual on its own; the
        // bug is that it is the same raw value that "name" was classified to
        // protect.
        //
        // Removing the check: commenting out the leak-check block in
        // ConfiguredJsonScrubbingEngine.scrub (by hand, during development) makes
        // this test fail: the response returns with "Duplicated Raw Name" sitting
        // unredacted in "notes" while "name" itself is correctly synthesised.
        // Restoring the block (checked in below) refuses the response instead.
        nextBody = "{\"id\":\"CUST-1\",\"name\":\"Duplicated Raw Name\","
                + "\"emailAddress\":\"alice@raw.example\",\"state\":\"ACTIVE\","
                + "\"notes\":\"Duplicated Raw Name\"}";

        RestSource transport = new RestSource("leaky-api",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "/customers/{subject}",
                Duration.ofSeconds(2));
        Map<String, FieldMetadata> fields = Map.of(
                "id", new FieldMetadata("id", true, FieldMetadata.SELF, List.of(),
                        PrivacyNamespace.NONE, null, "", null, String.class, null),
                "name", new FieldMetadata("name", false, null, List.of(DataClassification.PII),
                        PrivacyNamespace.PERSON_NAME, PrivacyAction.SYNTHESIZE, "", null, String.class, null),
                "emailAddress", new FieldMetadata("emailAddress", false, null, List.of(DataClassification.CONTACT),
                        PrivacyNamespace.EMAIL, PrivacyAction.REDACT, "", null, String.class, null),
                "state", new FieldMetadata("state", false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", "enumerated lifecycle state", String.class, null),
                "notes", new FieldMetadata("notes", false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", "free-text field, reviewed as inert", String.class, null));
        ConfiguredJsonSource configured = new ConfiguredJsonSource(transport, "customer-v1", "id", fields);

        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        DefaultContextOrchestrator orchestrator = orchestrator(
                List.of(new ConfiguredJsonDataSourceAdapter(configured, RestClient.create())), configured, vocabulary);

        assertThatThrownBy(() -> orchestrator.buildContext(
                ContextRequest.of("CUSTOMER", "CUST-1"), context(vocabulary), caller()))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining("RAW_SOURCE_VALUE");
    }
}
