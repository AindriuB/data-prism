package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile.UnclassifiedBehaviour;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An undeclared property's name is payload data and can be personal data. Task 118
 * kept it out of refusals, logs and audit; this keeps it out of a successful
 * result as well, under every setting but the one spelled UNSAFE (task 124).
 */
class UndeclaredNameToolResultScanTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
    private static final String TOKEN = UndeclaredKeyFixture.RESULT_TOKEN;

    private record Run(List<McpSchema.CallToolResult> results, String log, List<AuditEvent> events) {

        /** The serialised structured content and every text block of every result. */
        List<String> surfaces() {
            List<String> out = new ArrayList<>();
            for (McpSchema.CallToolResult result : results) {
                try {
                    out.add(DataPrismObjectMapper.create().writeValueAsString(result.structuredContent()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                for (McpSchema.Content c : result.content()) {
                    if (c instanceof McpSchema.TextContent text) {
                        out.add(text.text());
                    }
                }
            }
            return out;
        }

        String auditText() {
            return events.toString();
        }
    }

    private static Run run(UnclassifiedBehaviour behaviour) {
        List<AuditEvent> events = new ArrayList<>();
        AuditSink recording = event -> {
            events.add(event);
            new Slf4jAuditSink().record(event);
        };
        List<McpSchema.CallToolResult> results = new ArrayList<>();
        String log = capture(() -> results.addAll(UndeclaredKeyFixture.runBenign(behaviour, recording, CLOCK)));
        return new Run(results, log, events);
    }

    private static String capture(Runnable action) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream redirect = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        System.setOut(redirect);
        System.setErr(redirect);
        try {
            action.run();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("FAIL_REQUEST, REDACT_AND_WARN and DROP_AND_WARN: the key reaches no result, log or audit event")
    void keyNeverReachesAnySurface() {
        for (UnclassifiedBehaviour behaviour : List.of(UnclassifiedBehaviour.FAIL_REQUEST,
                UnclassifiedBehaviour.REDACT_AND_WARN, UnclassifiedBehaviour.DROP_AND_WARN)) {
            Run run = run(behaviour);

            assertThat(run.log()).as(behaviour + ": the run must reach the audit logger").contains("event=");
            assertThat(run.events()).as(behaviour + ": audit events").isNotEmpty();
            assertThat(run.surfaces()).as(behaviour + ": result surfaces").isNotEmpty()
                    .allSatisfy(s -> assertThat(s).doesNotContain(TOKEN));
            assertThat(run.log()).as(behaviour + ": log").doesNotContain(TOKEN);
            assertThat(run.auditText()).as(behaviour + ": audit events").doesNotContain(TOKEN);
            if (behaviour != UnclassifiedBehaviour.FAIL_REQUEST) {
                assertThat(run.results()).as(behaviour + " is a successful result")
                        .allSatisfy(r -> assertThat(r.isError()).isNotEqualTo(Boolean.TRUE));
            }
        }
    }

    @Test
    @DisplayName("REDACT_AND_WARN: the result carries the numbered placeholder")
    void redactCarriesPlaceholder() {
        Run run = run(UnclassifiedBehaviour.REDACT_AND_WARN);

        assertThat(run.surfaces()).anySatisfy(s -> assertThat(s).contains("<undeclared-1>"));
    }

    @Test
    @DisplayName("PASS_THROUGH_UNSAFE: the name reaches the result (pinned, documented); log and audit stay clean")
    void passThroughPinned() {
        Run run = run(UnclassifiedBehaviour.PASS_THROUGH_UNSAFE);

        assertThat(run.surfaces()).anySatisfy(s -> assertThat(s).contains(TOKEN));
        assertThat(run.log()).doesNotContain(TOKEN);
        assertThat(run.auditText()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("the scan is not vacuous: the token planted in a result, the log or an audit event is caught")
    void scanIsNotVacuous() {
        var planted = McpSchema.CallToolResult.builder()
                .addTextContent("note " + UndeclaredKeyFixture.RESULT_KEY).build();
        String log = capture(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: key={}", UndeclaredKeyFixture.RESULT_KEY));
        Run run = new Run(List.of(planted), log, List.of());

        assertThat(run.surfaces()).anySatisfy(s -> assertThat(s).contains(TOKEN));
        assertThat(run.log()).contains(TOKEN);
    }
}
