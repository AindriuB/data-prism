package io.github.aindriub.dataprism.connectors.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.core.spi.ChainableField;
import io.github.aindriub.dataprism.core.spi.ChainableView;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.FollowRequest;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.orchestration.links.IssuedLink;
import io.github.aindriub.dataprism.orchestration.links.KeyResolvers;
import io.github.aindriub.dataprism.orchestration.links.ResolverInvoker;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Links from configured JSON sources (task 301), through the auto-configured context: the engine says which
 * field of a configured source's response holds a key, and {@code description.namespace-types} types a multi-type
 * target. The context is the real one; only the HTTP peer is a stub.
 */
class ConfiguredJsonLinksTest {

    private static final String LINK_KEY = "link-key";
    private static final Instant SCOPE_END = Instant.parse("2030-01-01T00:00:00Z");

    /** The source the mismatch adapter delegates to; set before each context starts. */
    private static volatile String delegateYaml;

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @TempDir
    Path dir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/parcels", exchange -> respond(exchange, "{\"parcel\":\"P-1\",\"state\":\"ACTIVE\"}"));
        server.createContext("/hub", exchange -> respond(exchange, "{\"hub\":\"H-1\",\"state\":\"OPEN\"}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, String json) throws IOException {
        requested.add(exchange.getRequestURI().getPath());
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String yaml() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return """
                json-sources:
                  parcel-service:
                    base-url: %1$s
                    path: /parcels/{subject}
                    timeout: PT2S
                    model-version: parcel-v1
                    subject-json-path: parcel
                    fields:
                      parcel:
                        identifier: true
                        chainable: true
                        keyNamespace: parcel-ref
                      state:
                        nonSensitive: "Enumerated lifecycle state"
                  hub-service:
                    base-url: %1$s
                    path: /hub/{subject}
                    timeout: PT2S
                    model-version: hub-v1
                    subject-json-path: hub
                    description:
                      entity-types: [DEPOT, PARCEL]
                      input-keys: [depot-ref, parcel-ref]
                      namespace-types:
                        parcel-ref: PARCEL
                        depot-ref: DEPOT
                    fields:
                      hub:
                        identifier: true
                      state:
                        nonSensitive: "Enumerated lifecycle state"
                """.formatted(base);
    }

    /** A source the engine was not built for, answering with the payload of one it was: the name does not match. */
    static final class Mismatched implements DataSourceAdapter<ConfiguredJsonPayload> {
        private final ConfiguredJsonDataSourceAdapter delegate;

        Mismatched() {
            ConfiguredJsonSource source = ConfiguredJsonSources
                    .fromYaml(new ByteArrayInputStream(delegateYaml.getBytes(StandardCharsets.UTF_8)), true)
                    .sources().get("parcel-service");
            this.delegate = new ConfiguredJsonDataSourceAdapter(source, RestClient.create());
        }

        @Override
        public String sourceName() {
            return "mismatch-api";
        }

        @Override
        public Class<ConfiguredJsonPayload> responseType() {
            return ConfiguredJsonPayload.class;
        }

        @Override
        public ConfiguredJsonPayload fetch(DataRequest request) {
            return (ConfiguredJsonPayload) delegate.fetch(request);
        }
    }

    @Configuration
    static class LinksOn {
        @Bean
        OrchestrationOptions orchestrationOptions(SecretKeyProvider secrets, Clock clock) {
            LinkKeys keys = new LinkKeys(id -> id.equals(LINK_KEY)
                    ? "link-key-material-0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8)
                    : secrets.secret(id), LINK_KEY, "pseudo-key");
            LinkCodec codec = new LinkCodec(new LinkSettings(LINK_KEY, Optional.of(Duration.ofMinutes(30)), 1024,
                    true, Duration.ofSeconds(5)), keys, clock);
            KeyResolvers resolvers = new KeyResolvers(List.of());
            return OrchestrationOptions.defaults()
                    .withLinks(new OrchestrationOptions.LinkIssuing(codec, 50, resolvers, Map.of(), clock))
                    .withFollow(OrchestrationOptions.FollowSettings.of(
                            new LinkAttemptGate(codec, new InMemoryLinkAttemptLimiter(3, Duration.ofMinutes(10), clock)),
                            keys, resolvers, new ResolverInvoker(PrivacyMetrics.none())));
        }

        @Bean
        DataSourceAdapter<ConfiguredJsonPayload> mismatchedAdapter() {
            return new Mismatched();
        }
    }

    private ApplicationContextRunner runner() throws IOException {
        delegateYaml = yaml();
        Path file = Files.writeString(dir.resolve("sources.yaml"), delegateYaml);
        return new ApplicationContextRunner()
                .withInitializer(new ConfiguredJsonSourcesInitializer())
                .withUserConfiguration(ConfiguredJsonSourcesAutoConfigurationTest.RequiredCollaborators.class,
                        LinksOn.class)
                .withConfiguration(AutoConfigurations.of(
                        ConfiguredJsonSourcesAutoConfigurationTest.BaselineContextOrchestratorAutoConfiguration.class,
                        ConfiguredJsonSourcesAutoConfiguration.class))
                .withPropertyValues("dataprism.json-sources.config-location=" + file.toUri(),
                        "dataprism.transport.fixture-development=true");
    }

    private static PrivacyContext scope() {
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation", SCOPE_END,
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    @Test
    @DisplayName("a chainable configured source links to a configured source that declares the namespace, at the path "
            + "describe gives, and following it reads the target and carries the mapped type")
    void linkIsIssuedAndFollowed() throws IOException {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);
            SecretKeyProvider secrets = context.getBean(SecretKeyProvider.class);
            String hubLabel = new SourceAliasing(new HmacValueTokenSource(secrets)).aliasFor("hub-service", scope());

            ContextResponse response = orchestrator.buildContext(ContextRequest.of("THING", "1"), scope(), caller());

            List<IssuedLink> links = response.sources().values().stream().flatMap(s -> s.links().stream()).toList();
            assertThat(links).hasSize(1);
            IssuedLink link = links.get(0);
            assertThat(link.target()).isEqualTo(hubLabel);
            assertThat(link.namespace()).isEqualTo("parcel-ref");
            String described = orchestrator.describe("parcel-service", scope(), true).orElseThrow().fields().stream()
                    .filter(f -> "parcel-ref".equals(f.keyNamespace())).map(FieldProtection::path).findFirst()
                    .orElseThrow();
            assertThat(link.field()).isEqualTo(described);

            ContextResponse followed = orchestrator.follow(FollowRequest.of(link.link()), scope(), caller());

            assertThat(requested).contains("/hub/P-1");
            SourceResult result = followed.sources().get(hubLabel);
            assertThat(result.status()).isEqualTo("ANSWERED");
            assertThat(followed.entityType()).isEqualTo("PARCEL");
        });
    }

    @Test
    @DisplayName("a payload named for another source issues no link, while the source it names still does")
    void aMismatchedPayloadNameIssuesNoLink() throws IOException {
        runner().run(context -> {
            ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);
            SecretKeyProvider secrets = context.getBean(SecretKeyProvider.class);
            String mismatchLabel = new SourceAliasing(new HmacValueTokenSource(secrets))
                    .aliasFor("mismatch-api", scope());

            ContextResponse response = orchestrator.buildContext(ContextRequest.of("THING", "1"), scope(), caller());

            assertThat(response.sources().get(mismatchLabel).status()).isEqualTo("ANSWERED");
            assertThat(response.sources().get(mismatchLabel).links()).isEmpty();
            assertThat(response.sources().values().stream().mapToInt(s -> s.links().size()).sum()).isEqualTo(1);
        });
    }

    private ConfiguredJsonScrubbingEngine engine() throws IOException {
        ConfiguredJsonSource source = ConfiguredJsonSources
                .fromYaml(new ByteArrayInputStream(yaml().getBytes(StandardCharsets.UTF_8)), true).sources()
                .get("parcel-service");
        return engineOver(Map.of("parcel-service", source));
    }

    private ConfiguredJsonScrubbingEngine engineOver(Map<String, ConfiguredJsonSource> sources) throws IOException {
        StaticSecretKeyProvider keys = StaticSecretKeyProvider.of("task-301-links-test-key-not-for-any-real-data-32b");
        ProfilePrivacyPolicyResolver policy;
        try (var in = ConfiguredJsonLinksTest.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            policy = new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(in));
        }
        return new ConfiguredJsonScrubbingEngine((o, c) -> {
            throw new AssertionError("java-first delegate must not be reached");
        }, sources, policy,
                new HmacSyntheticGenerator(keys, VocabularyRegistry.withBuiltIns().resolve("und")),
                new HmacValueTokenSource(keys));
    }

    @Test
    @DisplayName("the engine gives the named source's chainable fields over the body, and no fields for a mismatched "
            + "name or an unknown source")
    void viewByName() throws IOException {
        ConfiguredJsonScrubbingEngine engine = engine();
        ObjectNode body = (ObjectNode) JsonMapper.builder().build().readTree("{\"parcel\":\"P-1\"}");

        Optional<ChainableView> own = engine.chainableView("parcel-service",
                new ConfiguredJsonPayload("parcel-service", body));
        Optional<ChainableView> mismatched = engine.chainableView("other-service",
                new ConfiguredJsonPayload("parcel-service", body));
        Optional<ChainableView> unknown = engine.chainableView("ghost", new ConfiguredJsonPayload("ghost", body));

        assertThat(own.orElseThrow().fields())
                .containsExactly(new ChainableField("parcel", new KeyNamespace("parcel-ref"), false));
        assertThat(own.orElseThrow().tree()).isEqualTo(body);
        assertThat(mismatched.orElseThrow().fields()).isEmpty();
        assertThat(unknown.orElseThrow().fields()).isEmpty();
        assertThat(engine.chainableView("parcel-service", "not a payload")).isEmpty();
        assertThat(own.orElseThrow().toString()).doesNotContain("P-1");
    }

    @Test
    @DisplayName("a payload of one known source, asked for under another known source's name, gives no fields")
    void aKnownSourceNameDoesNotAnswerForAnotherKnownSourcesPayload() throws IOException {
        String twoChainable = """
                json-sources:
                  parcel-service:
                    base-url: http://127.0.0.1:1
                    path: /parcels/{subject}
                    timeout: PT2S
                    model-version: parcel-v1
                    subject-json-path: parcel
                    fields:
                      parcel:
                        identifier: true
                        chainable: true
                        keyNamespace: parcel-ref
                  hub-service:
                    base-url: http://127.0.0.1:1
                    path: /hub/{subject}
                    timeout: PT2S
                    model-version: hub-v1
                    subject-json-path: hub
                    fields:
                      hub:
                        identifier: true
                        chainable: true
                        keyNamespace: depot-ref
                """;
        Map<String, ConfiguredJsonSource> sources = ConfiguredJsonSources
                .fromYaml(new ByteArrayInputStream(twoChainable.getBytes(StandardCharsets.UTF_8)), true).sources();
        ConfiguredJsonScrubbingEngine engine = engineOver(sources);
        ObjectNode body = (ObjectNode) JsonMapper.builder().build().readTree("{\"parcel\":\"P-1\"}");

        Optional<ChainableView> crossed = engine.chainableView("hub-service",
                new ConfiguredJsonPayload("parcel-service", body));
        Optional<ChainableView> own = engine.chainableView("hub-service",
                new ConfiguredJsonPayload("hub-service", body));

        assertThat(crossed.orElseThrow().fields()).isEmpty();
        assertThat(own.orElseThrow().fields())
                .containsExactly(new ChainableField("hub", new KeyNamespace("depot-ref"), false));
    }
}
