package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkAttemptGate;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.LinkSettings;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.orchestration.links.FollowRequest;
import io.github.aindriub.dataprism.orchestration.links.KeyResolvers;
import io.github.aindriub.dataprism.orchestration.links.ResolverInvoker;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.security.AdmissionDecision;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code follow} as a tool, over a real transport: JSON-RPC lines through {@link StdioServerTransportProvider}, so
 * the SDK's own {@code tools/call} wrapper runs. A direct handler call bypasses it.
 */
class FollowToolTest {

    static final Set<String> WITH_FOLLOW = Set.of("GET_ENTITY_CONTEXT", "FOLLOW");
    static final String VALID_SHAPE = "AAAAAAAAAAAAAAAAAAAAAAAA";

    // ---- a JSON-RPC peer over stdio ----

    private static final class QueueInput extends InputStream {
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int position;

        void feed(String line) {
            chunks.add((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void end() {
            chunks.add(new byte[0]);
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            int first = read();
            if (first < 0) {
                return -1;
            }
            into[offset] = (byte) first;
            int n = 1;
            while (n < length && position < current.length) {
                into[offset + n++] = current[position++];
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            while (position >= current.length) {
                try {
                    current = chunks.take();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                if (current.length == 0) {
                    chunks.add(current);
                    return -1;
                }
                position = 0;
            }
            return current[position++] & 0xFF;
        }
    }

    private static final class LineOutput extends OutputStream {
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                lines.add(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            } else {
                buffer.write(b);
            }
        }
    }

    /** What a tool call answered, as the client received it. */
    record Answer(boolean isError, String text, String correlationId, ObjectNode result) {

        /** The whole result with its correlation id removed: what two refusals are compared on. */
        ObjectNode withoutCorrelation() {
            ObjectNode copy = result.deepCopy();
            copy.remove("_meta");
            return copy;
        }
    }

    static final class Peer implements AutoCloseable {
        private final QueueInput in = new QueueInput();
        private final LineOutput out = new LineOutput();
        private final McpSyncServer server;
        private final tools.jackson.databind.json.JsonMapper mapper;
        private int nextId = 1;

        Peer(DataPrismMcpServer.Wiring wiring) {
            mapper = wiring.mapper();
            server = DataPrismMcpServer.syncServer(wiring,
                    new StdioServerTransportProvider(wiring.json(), in, out));
            request("initialize", Map.of("protocolVersion", "2025-06-18",
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "follow-test", "version", "1")));
            in.feed("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        JsonNode request(String method, Object params) {
            int id = nextId++;
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            in.feed(mapper.writeValueAsString(message));
            try {
                while (true) {
                    String line = out.lines.poll(15, TimeUnit.SECONDS);
                    assertThat(line).as("a response to " + method).isNotNull();
                    JsonNode node = mapper.readTree(line);
                    if (node.has("id") && node.get("id").asInt() == id) {
                        return node;
                    }
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }

        /** The tools the server lists, by name. */
        List<String> toolNames() {
            List<String> names = new ArrayList<>();
            request("tools/list", Map.of()).get("result").get("tools")
                    .forEach(tool -> names.add(tool.get("name").asString()));
            return names;
        }

        JsonNode tool(String name) {
            for (JsonNode tool : request("tools/list", Map.of()).get("result").get("tools")) {
                if (name.equals(tool.get("name").asString())) {
                    return tool;
                }
            }
            throw new AssertionError("no tool " + name);
        }

        Answer call(String tool, Map<String, Object> arguments) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", tool);
            if (arguments != null) {
                params.put("arguments", arguments);
            }
            JsonNode response = request("tools/call", params);
            assertThat(response.has("error")).as("a tools/call that reaches a handler is not a protocol error")
                    .isFalse();
            ObjectNode result = (ObjectNode) response.get("result");
            String text = result.get("content").get(0).get("text").asString();
            JsonNode meta = result.get("_meta");
            String correlation = meta == null ? null : meta.get(ToolCalls.CORRELATION_ID_META_KEY).asString();
            return new Answer(result.path("isError").asBoolean(false), text, correlation, result);
        }

        Answer follow(Object link) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("link", link);
            return call("follow", arguments);
        }

        @Override
        public void close() {
            in.end();
            server.closeGracefully();
        }
    }

    // ---- the rig ----

    /** Answers a follow with a fixed envelope and remembers what it was asked. */
    static final class SpyOrchestrator implements ContextOrchestrator {
        final List<FollowRequest> follows = new CopyOnWriteArrayList<>();
        final List<PrivacyContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public ContextResponse buildContext(ContextRequest request, PrivacyContext privacy,
                                            InvestigationContext caller) {
            throw new AssertionError("follow never builds context");
        }

        @Override
        public ContextResponse follow(FollowRequest request, PrivacyContext privacy, InvestigationContext caller) {
            follows.add(request);
            contexts.add(privacy);
            return new ContextResponse("PARCEL", "SUBJ-FOLLOW", Map.of("label-1",
                    SourceResult.answered(DataPrismObjectMapper.create().createObjectNode().put("note", "alpha"))),
                    List.of(), Map.of(), "corr-from-orchestrator");
        }
    }

    /** The real orchestrator with follow on, no source and no record: every link it is given is refused. */
    static DefaultContextOrchestrator realOrchestrator(AuditRecorder audit, LinkAttemptLimiter limiter) {
        LinkKeys keys = new LinkKeys(new ListSourcesToolTest.MapKeys(), "link-key", "pseudo-key");
        LinkCodec codec = new LinkCodec(
                new LinkSettings("link-key", java.util.Optional.empty(), 1024, true, Duration.ofSeconds(30)), keys,
                ListSourcesToolTest.FIXED);
        var resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (source, ctx) -> {
            throw new AssertionError("a refused link never scrubs");
        };
        OrchestrationOptions options = OrchestrationOptions.defaults().withFollow(
                new OrchestrationOptions.FollowSettings(new LinkAttemptGate(codec, limiter), keys,
                        new KeyResolvers(List.of()),
                        new ResolverInvoker(PrivacyMetrics.none()),
                        5, Duration.ofSeconds(2), 1));
        return new DefaultContextOrchestrator(List.of(), scrubber, resolver,
                List.of(new SensitivePatternValidator()), (subjectId, namespace, ctx) -> "SUBJ-1",
                new ParameterFingerprinter(ListSourcesToolTest.KEYS), audit, new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), java.time.Clock.systemUTC(),
                        SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), RequestLimits.DEFAULT, new NamespaceCorrelationService(resolver),
                new SourceAliasing(new HmacValueTokenSource(ListSourcesToolTest.KEYS)), PrivacyMetrics.none(),
                options);
    }

    static LinkAttemptLimiter limiter(int limit) {
        return new InMemoryLinkAttemptLimiter(limit, Duration.ofMinutes(10), ListSourcesToolTest.FIXED);
    }

    /** The server over one orchestrator and one audit sink; the sink may be made to throw. */
    static final class Rig {
        final List<AuditEvent> audited = new CopyOnWriteArrayList<>();
        final ListSourcesToolTest.CountingMetrics metrics = new ListSourcesToolTest.CountingMetrics();
        final DataPrismMcpServer.Wiring wiring;

        Rig(Set<String> capabilities, ToolOptions options, boolean linksEnabled,
            java.util.function.BiFunction<AuditRecorder, LinkAttemptLimiter, ContextOrchestrator> orchestrator,
            LinkAttemptLimiter limiter, AuthenticatedCaller caller, boolean auditFails) {
            AuditRecorder audit = new AuditRecorder(event -> {
                if (auditFails) {
                    throw new IllegalStateException(AUDIT_SENTINEL);
                }
                audited.add(event);
            }, ListSourcesToolTest.FIXED, "test-mcp");
            SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                    Map.of("investigator", capabilities));
            AuthorizationService authz = new AuthorizationService(security, "DEFAULT",
                    PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
            ScopeResolver scopes = new ScopeResolver(
                    PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                    Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")),
                    CaseAuthorizer.tokenTrusted());
            wiring = DataPrismMcpServer.Wiring.of(orchestrator.apply(audit, limiter), authz, scopes, metrics, audit,
                    ListSourcesToolTest.FIXED, caller, options, null, 1024, linksEnabled);
        }
    }

    static final String AUDIT_SENTINEL = "sentinel-sink-failure-/var/audit/secret.log";

    static Rig rig(Set<String> capabilities, ToolOptions options, boolean linksEnabled,
                   java.util.function.BiFunction<AuditRecorder, LinkAttemptLimiter, ContextOrchestrator> orchestrator,
                   LinkAttemptLimiter limiter, AuthenticatedCaller caller) {
        return new Rig(capabilities, options, linksEnabled, orchestrator, limiter, caller, false);
    }

    // ---- registration ----

    @Test
    @DisplayName("with links disabled tools/list does not contain follow; enabled, it does, with the served schema")
    void registeredOnlyWhenLinksAreEnabled() {
        ToolOptions options = ToolOptions.defaults().noAdmission().build();
        SpyOrchestrator spy = new SpyOrchestrator();
        try (Peer off = new Peer(rig(WITH_FOLLOW, options, false, (audit, limiter) -> spy, limiter(5),
                ListSourcesToolTest.CALLER).wiring);
             Peer on = new Peer(rig(WITH_FOLLOW, options, true, (audit, limiter) -> spy, limiter(5),
                     ListSourcesToolTest.CALLER).wiring)) {
            assertThat(off.toolNames()).doesNotContain("follow").contains("get_entity_context");
            assertThat(on.toolNames()).contains("follow");

            JsonNode schema = on.tool("follow").get("inputSchema");
            assertThat(schema.get("required").get(0).asString()).isEqualTo("link");
            assertThat(schema.get("properties").get("link").get("maxLength").asInt()).isEqualTo(1024);
            assertThat(schema.get("additionalProperties").asBoolean(true)).isFalse();
        }
    }

    // ---- the answer ----

    @Test
    @DisplayName("a valid link returns the orchestrator's envelope through the shared mapper, with its correlation id")
    void validLinkReturnsTheEnvelope() {
        SpyOrchestrator spy = new SpyOrchestrator();
        Rig rig = rig(WITH_FOLLOW, ToolOptions.defaults().noAdmission().build(), true, (audit, limiter) -> spy,
                limiter(5), ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(rig.wiring)) {
            Answer answer = peer.follow(VALID_SHAPE);

            assertThat(answer.isError()).isFalse();
            assertThat(answer.correlationId()).isEqualTo("corr-from-orchestrator");
            JsonNode body = answer.result().get("structuredContent");
            assertThat(body.get("sources").propertyNames()).containsExactly("label-1");
            assertThat(body.get("sources").get("label-1").get("status").asString()).isEqualTo("ANSWERED");
            assertThat(body.get("entityType").asString()).isEqualTo("PARCEL");
            assertThat(body.get("subject").asString()).isEqualTo("SUBJ-FOLLOW");
            assertThat(body.has("correlationId")).isFalse();
            assertThat(answer.text()).contains("label-1").contains("alpha");
        }
        assertThat(spy.follows).hasSize(1);
        assertThat(spy.follows.get(0).link()).isEqualTo(VALID_SHAPE);
        assertThat(spy.follows.get(0).toolName()).isEqualTo("follow");
        assertThat(rig.metrics.counts.get(Metric.MCP_REQUESTS)).isEqualTo(1);
    }

    @Test
    @DisplayName("reserved arguments are stripped before the schema check and never read")
    void reservedArgumentsAreStripped() {
        SpyOrchestrator spy = new SpyOrchestrator();
        Rig rig = rig(WITH_FOLLOW, ToolOptions.defaults().noAdmission().build(), true, (audit, limiter) -> spy,
                limiter(5), ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(rig.wiring)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("link", VALID_SHAPE);
            arguments.put("principalId", "someone-else");
            arguments.put("approvalId", "forged-approval");

            assertThat(peer.call("follow", arguments).isError()).isFalse();
        }
        assertThat(spy.follows).hasSize(1);
        assertThat(spy.follows.get(0).approvalId()).isEmpty();
        assertThat(spy.follows.get(0).approverId()).isEmpty();
    }

    @Test
    @DisplayName("a refusal that cannot be audited by the tool still names the reserved arguments it carried")
    void reservedArgumentsAreAuditedOnATool_DeniedCall() {
        SpyOrchestrator spy = new SpyOrchestrator();
        Rig rig = rig(Set.of("GET_ENTITY_CONTEXT"), ToolOptions.defaults().noAdmission().build(), true,
                (audit, limiter) -> spy, limiter(5), ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(rig.wiring)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("link", VALID_SHAPE);
            arguments.put("principalId", "someone-else");
            assertThat(peer.call("follow", arguments).text()).isEqualTo("TOOL_NOT_PERMITTED");
        }
        assertThat(rig.audited).singleElement().satisfies(event ->
                assertThat(event.rejectedArguments()).containsExactly("principalId"));
    }

    // ---- authorisation ----

    @Test
    @DisplayName("a role without FOLLOW gets TOOL_NOT_PERMITTED, audited under follow, and nothing is followed")
    void withoutTheCapabilityIsRefused() {
        SpyOrchestrator spy = new SpyOrchestrator();
        Rig rig = rig(Set.of("GET_ENTITY_CONTEXT"), ToolOptions.defaults().noAdmission().build(), true,
                (audit, limiter) -> spy, limiter(5), ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(rig.wiring)) {
            for (Object link : new Object[] {VALID_SHAPE, 7, ""}) {
                Answer answer = peer.follow(link);
                assertThat(answer.isError()).isTrue();
                assertThat(answer.text()).isEqualTo("TOOL_NOT_PERMITTED");
            }
        }
        assertThat(spy.follows).isEmpty();
        assertThat(rig.audited).hasSize(3).allSatisfy(event -> {
            assertThat(event.tool()).isEqualTo("follow");
            assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_NOT_PERMITTED");
        });
    }

    // ---- admission ----

    /** Records what admission was asked and answers {@code answer}. */
    static final class RecordingAdmission extends ToolAdmission {
        final List<String> tools = new ArrayList<>();
        final List<String> bindings = new ArrayList<>();
        AdmissionDecision answer = AdmissionDecision.admit();

        RecordingAdmission() {
            super(new InMemoryOversightState(), new InMemoryApprovalStore(), new InMemoryCallerRateLimiter(),
                    OversightPolicy.none(), ListSourcesToolTest.FIXED);
        }

        @Override
        public AdmissionDecision admit(AuthenticatedCaller caller, String tool, String scopeId, String binding) {
            tools.add(tool);
            bindings.add(binding);
            return answer;
        }
    }

    private static ToolOptions options(RecordingAdmission admission) {
        return ToolOptions.defaults().admission(admission, new ParameterFingerprinter(ListSourcesToolTest.KEYS))
                .build();
    }

    @Test
    @DisplayName("admission is asked under the tool name follow, and a refusal reaches the client and the audit")
    void admissionAppliesUnderTheToolName() {
        RecordingAdmission admission = new RecordingAdmission();
        admission.answer = AdmissionDecision.refuse("PAUSED");
        SpyOrchestrator spy = new SpyOrchestrator();
        Rig rig = rig(WITH_FOLLOW, options(admission), true, (audit, limiter) -> spy, limiter(5),
                ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(rig.wiring)) {
            Answer answer = peer.follow(VALID_SHAPE);
            assertThat(answer.isError()).isTrue();
            assertThat(answer.text()).isEqualTo("PAUSED");
        }
        assertThat(admission.tools).containsExactly("follow");
        assertThat(spy.follows).isEmpty();
        assertThat(rig.audited).singleElement().satisfies(event -> {
            assertThat(event.tool()).isEqualTo("follow");
            assertThat(event.policyDecision()).isEqualTo("DENY:PAUSED");
        });
        assertThat(rig.metrics.counts).doesNotContainKey(Metric.MCP_REQUESTS);
    }

    @Test
    @DisplayName("an approval is bound to the actual link through the keyed fingerprint, never the raw text")
    void theApprovalBindsTheToken() {
        RecordingAdmission admission = new RecordingAdmission();
        SpyOrchestrator spy = new SpyOrchestrator();
        Rig rig = rig(WITH_FOLLOW, options(admission), true, (audit, limiter) -> spy, limiter(5),
                ListSourcesToolTest.CALLER);
        String other = "BBBBBBBBBBBBBBBBBBBBBBBB";

        try (Peer peer = new Peer(rig.wiring)) {
            peer.follow(VALID_SHAPE);
            peer.follow(other);
            peer.follow(VALID_SHAPE);
        }
        assertThat(admission.bindings).hasSize(3);
        assertThat(admission.bindings.get(0)).isNotBlank().isEqualTo(admission.bindings.get(2))
                .isNotEqualTo(admission.bindings.get(1));
        assertThat(admission.bindings).allSatisfy(binding ->
                assertThat(binding).doesNotContain(VALID_SHAPE).doesNotContain(other));
    }

    @Test
    @DisplayName("an admitted decision's approval and approver ids reach the orchestrator, never a caller-sent one")
    void approvalIdsComeFromTheAdmittedDecision() {
        RecordingAdmission admission = new RecordingAdmission();
        admission.answer = new AdmissionDecision(true, null, "approval-1", "approver-1");
        SpyOrchestrator spy = new SpyOrchestrator();
        Rig rig = rig(WITH_FOLLOW, options(admission), true, (audit, limiter) -> spy, limiter(5),
                ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(rig.wiring)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("link", VALID_SHAPE);
            arguments.put("approvalId", "forged-approval");
            arguments.put("approverId", "forged-approver");
            assertThat(peer.call("follow", arguments).isError()).isFalse();
        }
        assertThat(spy.follows).singleElement().satisfies(request -> {
            assertThat(request.approvalId()).isEqualTo("approval-1");
            assertThat(request.approverId()).isEqualTo("approver-1");
        });
    }

    @Test
    @DisplayName("the approval id reaches the audit of a call the real orchestrator then refuses")
    void approvalIdsReachTheAudit() {
        RecordingAdmission admission = new RecordingAdmission();
        admission.answer = new AdmissionDecision(true, null, "approval-1", "approver-1");
        Rig rig = rig(WITH_FOLLOW, options(admission), true, FollowToolTest::realOrchestrator, limiter(5),
                ListSourcesToolTest.CALLER);

        try (Peer peer = new Peer(rig.wiring)) {
            assertThat(peer.follow(VALID_SHAPE).text()).isEqualTo("The link is not valid.");
        }
        assertThat(rig.audited).singleElement().satisfies(event -> {
            assertThat(event.tool()).isEqualTo("follow");
            assertThat(event.approvalId()).isEqualTo("approval-1");
            assertThat(event.approverId()).isEqualTo("approver-1");
        });
    }
}
