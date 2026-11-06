package io.github.aindriub.dataprism.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.core.metrics.Metric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.aindriub.dataprism.mcp.FollowToolTest.Answer;
import static io.github.aindriub.dataprism.mcp.FollowToolTest.Peer;
import static io.github.aindriub.dataprism.mcp.FollowToolTest.Rig;
import static io.github.aindriub.dataprism.mcp.FollowToolTest.VALID_SHAPE;
import static io.github.aindriub.dataprism.mcp.FollowToolTest.WITH_FOLLOW;
import static io.github.aindriub.dataprism.mcp.FollowToolTest.limiter;
import static io.github.aindriub.dataprism.mcp.FollowToolTest.realOrchestrator;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refusals of {@code follow} over a real transport, against the real orchestrator and its link gate: one result for
 * every invalid link, the link in nothing that is written, and a denial whose audit fails.
 */
class FollowToolRefusalTest {

    static final String SENTINEL = "SENTINEL-d3adb33f";
    static final String INVALID = "The link is not valid.";

    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        // The SDK logs every inbound message at DEBUG on stdio; INFO and above is what is captured and checked.
        previousLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        root.setLevel(previousLevel);
    }

    private String loggedText() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            out.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                out.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return out.toString();
    }

    private static Rig realRig(int limit) {
        return FollowToolTest.rig(WITH_FOLLOW, ToolOptions.defaults().noAdmission().build(), true,
                FollowToolTest::realOrchestrator, limiter(limit), ListSourcesToolTest.CALLER);
    }

    @Test
    @DisplayName("every invalid link, however it is invalid, gives the one LINK_INVALID result")
    void everyInvalidLinkIsTheSameResult() {
        Rig rig = realRig(100);
        List<Object> invalid = new ArrayList<>();
        invalid.add(VALID_SHAPE);
        invalid.add("a".repeat(1025));
        invalid.add("a".repeat(100_000));
        invalid.add("");
        invalid.add("not a link!");
        invalid.add("AAAA.AAAA");
        invalid.add(7);
        invalid.add(null);
        invalid.add(List.of("x"));
        invalid.add(Map.of("x", "y"));

        try (Peer peer = new Peer(rig.wiring)) {
            Answer baseline = peer.follow(VALID_SHAPE);
            assertThat(baseline.isError()).isTrue();
            assertThat(baseline.text()).isEqualTo(INVALID);
            for (Object link : invalid) {
                int events = rig.audited.size();

                Answer answer = peer.follow(link);

                assertThat(answer.withoutCorrelation()).as(String.valueOf(link).length() + " chars")
                        .isEqualTo(baseline.withoutCorrelation());
                assertThat(answer.correlationId()).isNotBlank();
                assertThat(rig.audited).hasSize(events + 1);
                AuditEvent event = rig.audited.get(events);
                assertThat(event.tool()).isEqualTo("follow");
                assertThat(event.policyDecision()).isEqualTo("DENY:LINK_INVALID");
                assertThat(event.correlationId()).isEqualTo(answer.correlationId());
            }
        }
    }

    @Test
    @DisplayName("a fine link beside another member is a forged link, and is not opened")
    void aFineLinkBesideAnotherMemberIsInvalid() {
        Rig rig = realRig(100);

        try (Peer peer = new Peer(rig.wiring)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("link", VALID_SHAPE);
            arguments.put("extra", "x");
            assertThat(peer.call("follow", arguments).text()).isEqualTo(INVALID);
        }
        assertThat(rig.audited).singleElement().satisfies(event ->
                assertThat(event.fieldDispositions()).containsEntry("link", "REFUSED:LINK_MALFORMED"));
    }

    @Test
    @DisplayName("a call without a link is invalid input, not a link refusal")
    void noLinkIsInvalidInput() {
        Rig rig = realRig(100);

        try (Peer peer = new Peer(rig.wiring)) {
            assertThat(peer.call("follow", Map.of()).text()).isEqualTo("TOOL_INPUT_INVALID");
            assertThat(peer.call("follow", null).text()).isEqualTo("TOOL_INPUT_INVALID");
        }
        assertThat(rig.audited).hasSize(2).allSatisfy(event ->
                assertThat(event.policyDecision()).isEqualTo("DENY:TOOL_INPUT_INVALID"));
    }

    @Test
    @DisplayName("the failed-attempt limit applies: past it every call is LINK_ATTEMPTS_EXHAUSTED")
    void theLimiterApplies() {
        Rig rig = realRig(2);

        try (Peer peer = new Peer(rig.wiring)) {
            assertThat(peer.follow(VALID_SHAPE).text()).isEqualTo(INVALID);
            assertThat(peer.follow(7).text()).isEqualTo(INVALID);
            assertThat(peer.follow(VALID_SHAPE).text()).isEqualTo("LINK_ATTEMPTS_EXHAUSTED");
        }
        assertThat(rig.audited.get(2).policyDecision()).isEqualTo("DENY:LINK_ATTEMPTS_EXHAUSTED");
    }

    @Test
    @DisplayName("a sentinel link appears in no result, audit record, metric label or log line")
    void theLinkEscapesNowhere() {
        Rig rig = realRig(100);
        String longSentinel = SENTINEL + "-" + "z".repeat(2000);
        List<String> results = new ArrayList<>();

        try (Peer peer = new Peer(rig.wiring)) {
            for (Object link : new Object[] {SENTINEL, longSentinel, SENTINEL + " bad", Map.of("x", SENTINEL)}) {
                results.add(peer.follow(link).result().toString());
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("link", SENTINEL);
            arguments.put("other", SENTINEL);
            arguments.put("principalId", SENTINEL);
            results.add(peer.call("follow", arguments).result().toString());
        }

        assertThat(String.join("\n", results)).doesNotContain(SENTINEL);
        assertThat(rig.audited).isNotEmpty();
        assertThat(rig.audited.toString()).doesNotContain(SENTINEL);
        assertThat(rig.audited).allSatisfy(event -> assertThat(event.rejectedArguments()).doesNotContain(SENTINEL));
        assertThat(loggedText()).doesNotContain(SENTINEL);
    }

    @Test
    @DisplayName("no authenticated caller: NO_AUTHENTICATED_CALLER for a valid and an invalid link alike")
    void unauthenticatedIsNeverALinkRefusal() {
        Rig rig = FollowToolTest.rig(WITH_FOLLOW, ToolOptions.defaults().noAdmission().build(), true,
                FollowToolTest::realOrchestrator, limiter(100), null);

        try (Peer peer = new Peer(rig.wiring)) {
            assertThat(peer.follow(VALID_SHAPE).text()).isEqualTo("NO_AUTHENTICATED_CALLER");
            assertThat(peer.follow(7).text()).isEqualTo("NO_AUTHENTICATED_CALLER");
        }
        assertThat(rig.audited).hasSize(2).allSatisfy(event -> {
            assertThat(event.principalId()).isEqualTo("unauthenticated");
            assertThat(event.policyDecision()).isEqualTo("DENY:NO_AUTHENTICATED_CALLER");
            assertThat(event.fieldDispositions()).doesNotContainKey("link");
        });
    }

    @Test
    @DisplayName("a denial whose audit fails answers AUDIT_UNAVAILABLE and counts it, on every denial path")
    void auditFailureAnswersAuditUnavailable() {
        // The tool's own denial (no FOLLOW), the unauthenticated denial, and the orchestrator's link refusal.
        Rig noCapability = new Rig(java.util.Set.of("GET_ENTITY_CONTEXT"),
                ToolOptions.defaults().noAdmission().build(), true, FollowToolTest::realOrchestrator, limiter(100),
                ListSourcesToolTest.CALLER, true);
        Rig unauthenticated = new Rig(WITH_FOLLOW, ToolOptions.defaults().noAdmission().build(), true,
                FollowToolTest::realOrchestrator, limiter(100), null, true);
        Rig forged = new Rig(WITH_FOLLOW, ToolOptions.defaults().noAdmission().build(), true,
                FollowToolTest::realOrchestrator, limiter(100), ListSourcesToolTest.CALLER, true);

        for (Rig rig : List.of(noCapability, unauthenticated, forged)) {
            try (Peer peer = new Peer(rig.wiring)) {
                Answer answer = peer.follow(VALID_SHAPE);

                assertThat(answer.isError()).isTrue();
                assertThat(answer.text()).isEqualTo("refused: AUDIT_UNAVAILABLE");
                assertThat(answer.result().toString()).doesNotContain("secret.log");
                assertThat(rig.metrics.counts.get(Metric.DENIAL_AUDIT_UNAVAILABLE)).isEqualTo(1);
            }
        }
        assertThat(loggedText()).doesNotContain(VALID_SHAPE);
    }
}
