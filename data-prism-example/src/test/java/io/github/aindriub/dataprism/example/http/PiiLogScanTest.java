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
import java.util.regex.Matcher;
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
 *
 * <p>{@link #findLeaked} — the original {@code \b}-bounded matcher — stays,
 * unchanged, and still runs: on the log's ordinary application lines (never
 * shaped like a {@code dataprism.audit} record), and as a safety net under
 * every audit line too, so nothing this class already caught is lost. What is
 * new is {@link #findLeakedAcrossLines}, which additionally parses each
 * {@code dataprism.audit} record into its own named fields — the same
 * sequence {@code Slf4jAuditSink} emits them in — and scans each field's value
 * on its own, unbounded by {@code \b}. That is what closes the gap: a banned
 * value glued to word characters, such as a pseudonymiser bug that prefixes
 * rather than replaces a raw subject id, is invisible to a whole-line
 * {@code \b} scan but not to a scan of the isolated field it landed in. The
 * fields known to be nothing but random hex, a UUID, or a system timestamp by
 * construction — the ones a coincidental collision could occur in — are
 * skipped, but only when the field's whole value matches that field's pinned
 * shape; a value that does not is scanned like any other.
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

    /**
     * {@code Slf4jAuditSink}'s twenty placeholders, in the order it renders
     * them. Used two ways: to recognise a line as an audit record at all (any
     * one of these markers is enough), and to split a recognised line into its
     * own fields, each scanned on its own.
     */
    private static final List<String> AUDIT_KEYS = List.of(
            "event", "seq", "ts", "principal", "client", "tool", "entityType", "subject",
            "params", "profile", "scope", "purpose", "case", "decision", "sources",
            "rejected", "correlation", "hash", "prev");

    private static final Pattern AUDIT_KEY_MARKER =
            Pattern.compile("\\b(" + String.join("|", AUDIT_KEYS) + ")=");

    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX24_SHAPE = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern HEX64_SHAPE = Pattern.compile("[0-9a-f]{64}");

    /**
     * {@code Instant.toString()}'s own shape: a fractional second, when
     * present, is always 3, 6 or 9 digits, never any other width. Pinned this
     * precisely — not merely "digits after a dot" — for the same reason every
     * other shape here is pinned precisely: a loose pattern would exempt a
     * glued leak that happened to sit next to something date-shaped.
     */
    private static final Pattern INSTANT_SHAPE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.(\\d{3}|\\d{6}|\\d{9}))?Z");

    /**
     * The fields {@code Slf4jAuditSink} fills with nothing but a UUID, a run
     * of hex, or a system timestamp: {@code event} and {@code correlation}
     * (UUIDs), {@code params} (a twelve-byte HMAC fingerprint), {@code hash}
     * and {@code prev} (a SHA-256 hex digest), and {@code ts} — the wall clock
     * at the moment of the call, via {@code Clock.systemUTC()} in
     * {@code DataPrismAssembly.standard()}, so its nanosecond digits are as
     * good as random and were observed, empirically, to coincidentally spell
     * a banned digit run and redden this test with no leak anywhere near it.
     * Every other field — {@code subject} above all, the field a leak
     * actually lands in — is never exempted, whatever it looks like.
     */
    private static final Map<String, Pattern> EXEMPT_SHAPES = Map.of(
            "event", UUID_SHAPE,
            "correlation", UUID_SHAPE,
            "params", HEX24_SHAPE,
            "hash", HEX64_SHAPE,
            "prev", HEX64_SHAPE,
            "ts", INSTANT_SHAPE);

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

        String stripped = withoutTimestamps(captured);
        List<String> lines = stripped.lines().toList();

        // The partition every captured line falls into, asserted rather than
        // assumed: a full run's captured output is not only dataprism.audit
        // records. runFullIntegrationRun also drives a caller-supplied reserved
        // argument through DefaultContextOrchestrator, which logs a plain
        // application warning carrying none of the audit keys — exactly the
        // shape of the residual this scan leaves on the raw-\b path. Both
        // classes must be non-empty for that path to be exercised at all, and
        // every line must land in exactly one of them, so a line that matches
        // neither cannot be silently dropped unscanned.
        long auditShapedLines = lines.stream().filter(PiiLogScanTest::isAuditShaped).count();
        long nonAuditLines = lines.stream().filter(line -> !isAuditShaped(line)).count();
        assertThat(auditShapedLines)
                .as("a real run must produce at least one dataprism.audit record line, or the field-aware "
                        + "path is untested")
                .isGreaterThan(0);
        assertThat(nonAuditLines)
                .as("a real run must also produce an ordinary application log line, or the residual left on "
                        + "the \\b path is untested")
                .isGreaterThan(0);
        assertThat(auditShapedLines + nonAuditLines)
                .as("every captured line must fall into exactly one scanning path")
                .isEqualTo((long) lines.size());

        List<String> leaked = findLeakedAcrossLines(stripped, BANNED_VALUES);

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
     * The gap {@code findLeaked} cannot see: a banned value glued to word
     * characters on both sides, exactly the shape a pseudonymiser bug that
     * prefixes rather than replaces a raw subject id would produce. Both
     * shapes are run against the unchanged {@code \b} matcher first — proving
     * it still misses them today — before {@link #findLeakedAcrossLines}, the
     * field-aware scanner item five adds, is shown to catch both.
     */
    @Test
    @DisplayName("a banned value glued to word characters is still caught")
    void scannerCatchesValuesGluedToWordCharacters() {
        String subjectLeak = "SUBJ-123a7f9";
        String subjectCaptured = captureLogOutput(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: subject={}", subjectLeak));
        String subjectStripped = withoutTimestamps(subjectCaptured);

        assertThat(findLeaked(subjectStripped, List.of("123")))
                .as("today's \\b matcher cannot see a value glued to word characters")
                .isEmpty();
        assertThat(findLeakedAcrossLines(subjectStripped, List.of("123")))
                .containsExactly("123");

        String idLeak = "id_456_x";
        String idCaptured = captureLogOutput(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: rejected={}", idLeak));
        String idStripped = withoutTimestamps(idCaptured);

        assertThat(findLeaked(idStripped, List.of("456")))
                .as("today's \\b matcher cannot see a value glued to word characters")
                .isEmpty();
        assertThat(findLeakedAcrossLines(idStripped, List.of("456")))
                .containsExactly("456");
    }

    /**
     * The other half of the exemption: a field that is normally skipped
     * because it is nothing but random hex is scanned like any other field
     * the moment its value does not, itself, match that field's pinned shape
     * whole. {@code hash} is one of the exempt-eligible fields; this value is
     * nowhere near sixty-four hex characters.
     */
    @Test
    @DisplayName("an exempt-eligible field whose value does not match its pinned shape is scanned like any other")
    void nonConformingValueInExemptEligibleFieldIsScanned() {
        String captured = captureLogOutput(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: hash={}", "SUBJ-123a7f9"));

        assertThat(findLeakedAcrossLines(withoutTimestamps(captured), List.of("123")))
                .containsExactly("123");
    }

    /**
     * The exemption actually earning its skip: a real sixty-four character
     * hex digest that happens, by construction of this fixture, to contain the
     * digit runs {@code "123"} and {@code "456"} is not reported, because the
     * whole value matches the pinned SHA-256 shape. Without this test, item
     * five could have quietly regressed to unbounded substring matching on
     * every field — exactly the ~25% false-positive rate word boundaries were
     * first added to fix.
     */
    @Test
    @DisplayName("a well-formed hex field is exempt even where it coincidentally contains a banned digit run")
    void wellFormedHexFieldIsExempt() {
        String hex64ContainingCoincidentalDigits = "0123456789abcdef".repeat(4);
        assertThat(hex64ContainingCoincidentalDigits).hasSize(64).containsPattern(HEX64_SHAPE);

        String captured = captureLogOutput(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: hash={}", hex64ContainingCoincidentalDigits));

        assertThat(findLeakedAcrossLines(withoutTimestamps(captured), List.of("123", "456")))
                .isEmpty();
    }

    /**
     * The bug this class actually reddened on while item five was being built:
     * {@code ts} is {@code Instant.now()} against the real system clock
     * {@code DataPrismAssembly.standard()} uses, so its nanosecond digits are
     * effectively random and, over enough runs, do coincidentally spell a
     * banned digit run with no leak anywhere near it. A synthetic value
     * engineered to contain both {@code "123"} and {@code "456"} in its
     * fraction, but otherwise a well-formed {@code Instant.toString()}, proves
     * the exemption actually covers it.
     */
    @Test
    @DisplayName("a well-formed timestamp field is exempt even where its nanoseconds coincidentally spell a banned digit run")
    void wellFormedTimestampFieldIsExempt() {
        String instantContainingCoincidentalDigits = "2026-09-09T16:36:44.123456900Z";
        assertThat(instantContainingCoincidentalDigits).containsPattern(INSTANT_SHAPE);

        String captured = captureLogOutput(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: ts={}", instantContainingCoincidentalDigits));

        assertThat(findLeakedAcrossLines(withoutTimestamps(captured), List.of("123", "456")))
                .isEmpty();
    }

    /**
     * {@code sources} and {@code rejected} are the two fields
     * {@code Slf4jAuditSink} fills from a {@code Set<String>}, rendered with
     * the space-and-comma {@code Set.toString()} really produces —
     * {@code [alpha, beta]}, never a single token. Field splitting has to keep
     * that whole bracketed value together rather than breaking on the internal
     * comma or space, and a banned value glued inside one of its elements
     * still has to be caught.
     */
    @Test
    @DisplayName("a banned value inside a multi-element collection field is still caught")
    void scannerCatchesValueInsideCollectionField() {
        String collection = "[alpha, beta_id_456_x]";
        String captured = captureLogOutput(() -> LoggerFactory.getLogger("dataprism.audit")
                .info("simulated leak, for this test only: sources={}", collection));
        String stripped = withoutTimestamps(captured);

        assertThat(findLeaked(stripped, List.of("456")))
                .as("today's \\b matcher cannot see a value glued to word characters")
                .isEmpty();
        assertThat(findLeakedAcrossLines(stripped, List.of("456")))
                .containsExactly("456");
    }

    /**
     * Three calls through the real pipeline — {@code DataPrismAssembly},
     * {@code GetEntityContextTool}, a real {@code AuditRecorder} over
     * {@link Slf4jAuditSink} — covering both fixture subjects and one refusal,
     * the same shape {@code EndToEndTest} and {@code WorkedExampleTest} already
     * drive the pipeline through, just captured rather than left to the
     * console. A fourth call supplies a reserved argument name, which
     * {@code DefaultContextOrchestrator} logs as a plain application warning —
     * never shaped like a {@code dataprism.audit} record — so the captured
     * output actually exercises both scanning paths rather than only the
     * audit one.
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
        // And a reserved-argument attempt, so the run's captured output also
        // carries an ordinary application log line and the partition below has
        // something on the non-audit path to assert against.
        tool.specification().callHandler().apply(exchange, new McpSchema.CallToolRequest(
                GetEntityContextTool.NAME,
                Map.of("entityType", "CUSTOMER", "subjectId", "123", "purpose", "not-the-callers-to-set")));
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
     *
     * <p>Unchanged since task 07. Used as-is on any line that is not shaped
     * like a {@code dataprism.audit} record, and as a safety net alongside
     * the field-aware scan on every line that is.
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

    /**
     * The field-aware scan, applied line by line so the partition between the
     * two paths is explicit rather than folded into one regex over the whole
     * log. A line shaped like a {@code dataprism.audit} record — carrying at
     * least one of {@link #AUDIT_KEYS} — is scanned per field by
     * {@link #findLeakedInAuditLine}; every other line is scanned exactly as
     * {@link #findLeaked} already scanned the whole log before this method
     * existed.
     */
    private static List<String> findLeakedAcrossLines(String log, List<String> bannedValues) {
        List<String> leaked = new ArrayList<>();
        for (String line : log.lines().toList()) {
            List<String> lineLeaks = isAuditShaped(line)
                    ? findLeakedInAuditLine(line, bannedValues)
                    : findLeaked(line, bannedValues);
            for (String value : lineLeaks) {
                if (!leaked.contains(value)) {
                    leaked.add(value);
                }
            }
        }
        return leaked;
    }

    /** A line is treated as an audit record once it carries at least one recognised {@code key=} marker. */
    private static boolean isAuditShaped(String line) {
        return AUDIT_KEY_MARKER.matcher(line).find();
    }

    /**
     * Scans one audit-shaped line field by field. A field whose name is one of
     * {@link #EXEMPT_SHAPES}' keys is skipped only when its whole value
     * matches that field's pinned shape; every other field, including one that
     * was exempt-eligible but did not conform, is scanned by plain
     * {@code contains} — safe once the field's own boundaries are known, which
     * is exactly what {@link #auditFields} establishes. {@link #findLeaked}
     * also runs across the whole line underneath, so nothing the old matcher
     * caught is lost to the new one having a gap of its own.
     */
    private static List<String> findLeakedInAuditLine(String line, List<String> bannedValues) {
        List<String> leaked = new ArrayList<>();
        for (String[] field : auditFields(line)) {
            String key = field[0];
            String value = field[1];
            Pattern exemptShape = EXEMPT_SHAPES.get(key);
            if (exemptShape != null && exemptShape.matcher(value).matches()) {
                continue;
            }
            for (String banned : bannedValues) {
                if (value.contains(banned) && !leaked.contains(banned)) {
                    leaked.add(banned);
                }
            }
        }
        for (String banned : findLeaked(line, bannedValues)) {
            if (!leaked.contains(banned)) {
                leaked.add(banned);
            }
        }
        return leaked;
    }

    /**
     * Splits one line into the audit fields it carries, in whatever order and
     * however many of {@link #AUDIT_KEYS} are actually present — a full
     * well-formed record carries all twenty placeholders, but the tests in
     * this class also push a single field on its own, in the same shape
     * {@link #scannerIsNotVacuous()} uses, and both have to parse. A field's
     * value runs from just after its own {@code key=} marker to just before
     * the next recognised marker, or to the end of the line for the last
     * field — never split on the internal space or comma a collection field
     * like {@code sources} renders with.
     */
    private static List<String[]> auditFields(String line) {
        Matcher marker = AUDIT_KEY_MARKER.matcher(line);
        List<String> keys = new ArrayList<>();
        List<Integer> valueStarts = new ArrayList<>();
        List<Integer> keyStarts = new ArrayList<>();
        while (marker.find()) {
            keys.add(marker.group(1));
            keyStarts.add(marker.start());
            valueStarts.add(marker.end());
        }
        List<String[]> fields = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            int valueEnd = (i + 1 < keys.size()) ? keyStarts.get(i + 1) : line.length();
            fields.add(new String[] {keys.get(i), line.substring(valueStarts.get(i), valueEnd).trim()});
        }
        return fields;
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
