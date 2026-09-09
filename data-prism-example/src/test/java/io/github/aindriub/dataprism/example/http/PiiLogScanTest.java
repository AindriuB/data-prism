package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.Slf4jAuditSink;
import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.example.DataPrismAssembly;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The observability claim, held to a run that can fail: a full integration
 * run's captured log output — the application log and {@code dataprism.audit}
 * both, since both go through the same SLF4J provider this module pins to
 * stderr — contains none of the stub fixtures' identifying values.
 *
 * <p>Per docs/conventions.md, a leak test that asserts on absence is easy to
 * write vacuously. {@link #scannerIsNotVacuous()} is the companion proof:
 * it pushes a known fixture value through the very same logger and asserts
 * the same scanning method reports it. That test is mutation-shaped rather
 * than a mutation itself — nothing here needs the source to be edited and
 * reverted to prove it can fail, so a reviewer can read it directly rather
 * than needing {@code git show} for this one file. The separate requirement,
 * that every test in this task is provably able to fail by mutating source in
 * a scratchpad clone, still applies to {@link #fullIntegrationRunLeaksNoPii()}
 * itself and is recorded in this task's close-out rather than in this file.
 */
class PiiLogScanTest {

    /**
     * The stub fixtures' own identifying values (StubCustomerAdapter,
     * StubAccountAdapter): the customer names, the email addresses, and the
     * raw subject ids task 07's acceptance criteria name explicitly. A literal
     * list — {@link #findLeaked} matches each value whole, at its own
     * boundaries, never by a pattern loose enough to be satisfied vacuously.
     *
     * <p>The bare ids {@code "123"} and {@code "456"} stay in this list on
     * purpose: {@code "123"} is the stub subject id, exactly the kind of value
     * that must never appear in a log line. Audit lines also carry random hex
     * — event id, correlation id, the parameter fingerprint, the hash chain —
     * and a hex run can contain the digits {@code "123"} or {@code "456"} by
     * pure coincidence with no id anywhere near it. Matching each id only when
     * it stands as its own token, bounded by a non-hex character (or the ends
     * of the line) on both sides, tells the two apart: no field emitted by
     * {@code Slf4jAuditSink} — the UUID event and correlation ids (fixed
     * hyphen-delimited group widths, none three characters), the fingerprint,
     * or the SHA-256 hash chain (both a single unbroken run of hex) — has a
     * three-character token sitting at one of those boundaries, so a
     * coincidental hex collision can no longer read as a leak.
     */
    private static final List<String> BANNED_VALUES = List.of(
            "Patrick Murphy", "Aoife Byrne",
            "patrick.murphy@example.invalid", "aoife.byrne@example.invalid",
            "123", "456");

    @Test
    @DisplayName("a full integration run's log output contains none of the stub fixtures' identifying values")
    void fullIntegrationRunLeaksNoPii() throws IOException {
        String captured = captureLogOutput(PiiLogScanTest::runFullIntegrationRun);

        Path logFile = writeLogFile("pii-log-scan", captured);

        // A known-safe marker straight from Slf4jAuditSink's message format —
        // never parameterised, so it carries no fixture value — proves the run
        // actually reached the audit logger. Without this, a run that silently
        // stopped logging would pass the scan below for the wrong reason: there
        // is nothing left to leak.
        assertThat(captured)
                .as("captured log output written to %s must contain audit output, or this scan proves nothing",
                        logFile)
                .contains("event=");

        List<String> leaked = findLeaked(withoutTimestamps(captured), BANNED_VALUES);

        assertThat(leaked)
                .as("captured log output written to %s must not contain a stub fixture identifying value", logFile)
                .isEmpty();
    }

    @Test
    @DisplayName("the scanner is not vacuous: a known fixture value pushed through the same logger is caught")
    void scannerIsNotVacuous() {
        String distinctiveLeak = "Patrick Murphy";
        String captured = captureLogOutput(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: subject={}", distinctiveLeak));

        List<String> leaked = findLeaked(withoutTimestamps(captured), List.of(distinctiveLeak));

        assertThat(leaked).containsExactly(distinctiveLeak);
    }

    /**
     * Three calls through the real pipeline — {@code DataPrismAssembly},
     * {@code GetEntityContextTool}, a real {@code AuditRecorder} over
     * {@link Slf4jAuditSink} — covering both fixture subjects and one refusal,
     * the same shape {@code EndToEndTest} and {@code WorkedExampleTest} already
     * drive the pipeline through, just captured rather than left to the console.
     */
    private static void runFullIntegrationRun() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), java.time.ZoneOffset.UTC);
        String purpose = "demonstration";
        String role = "investigator";

        DataPrismAssembly assembly = DataPrismAssembly.standard();
        AuditRecorder toolAudit = new AuditRecorder(new Slf4jAuditSink(), clock, "pii-scan-mcp");
        SecurityPolicy policy =
                new SecurityPolicy(Set.of(purpose), Map.of(role, Set.of(Capability.GET_ENTITY_CONTEXT)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(purpose)));
        GetEntityContextTool tool = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, clock);

        AuthenticatedCaller caller = new AuthenticatedCaller(
                "pii-scan-principal", "pii-scan-client", Set.of(role), purpose, "CASE-PII-SCAN-1", null);
        McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                "pii-scan-session", null, null, null,
                McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));

        for (String subjectId : List.of("123", "456")) {
            tool.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                    GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", subjectId)));
        }
        // A refusal too, so the run's log carries a denial line alongside the
        // two successes rather than only ever the happy path.
        tool.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                GetEntityContextTool.NAME, Map.of("entityType", "CUSTOMER", "subjectId", "does-not-exist")));
    }

    /** Runs {@code action} with both {@link System#out} and {@link System#err} captured and restored. */
    private static String captureLogOutput(Runnable action) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream redirect = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(redirect);
        System.setErr(redirect);
        try {
            action.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * {@code simplelogger.properties} stamps every line with the wall-clock
     * millisecond ({@code dateTimeFormat}'s {@code .SSS}), bounded by a
     * literal {@code .} before it and a space after — a boundary on both
     * sides, exactly what {@link #findLeaked} looks for, so a millisecond
     * field that happens to read {@code "123"} would still pass as a token
     * match. Stripping the leading timestamp before scanning removes that
     * source of flakiness at its origin rather than asking the matcher to
     * tell a clock from a leak.
     */
    private static String withoutTimestamps(String log) {
        return log.lines()
                .map(line -> line.replaceFirst("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ", ""))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /**
     * A banned value is reported only when it appears as a whole token —
     * bounded by a non-word character, or the start or end of the log, on
     * both sides — never as a fragment inside a longer run of word
     * characters. Plain {@code String.contains} would treat the digits
     * {@code "123"} inside an unrelated hex id the same as the digits
     * {@code "123"} standing alone as the stub subject id; {@code \b} tells
     * them apart, because a hex id has no reason to break stride exactly at
     * the three characters that spell a banned value.
     */
    private static List<String> findLeaked(String log, List<String> bannedValues) {
        List<String> leaked = new ArrayList<>();
        for (String value : bannedValues) {
            if (Pattern.compile("\\b" + Pattern.quote(value) + "\\b").matcher(log).find()) {
                leaked.add(value);
            }
        }
        return leaked;
    }

    private static Path writeLogFile(String name, String content) throws IOException {
        Path dir = repoRoot().resolve("logs");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + "-" + Instant.now().toEpochMilli() + ".log");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** Walks up from the working directory to the checkout's own root — a worktree's {@code .git} is a file, not a directory, so this checks for existence rather than {@code isDirectory}. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "could not find a repository root above " + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
