package io.github.aindriub.dataprism.audit.verify;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditChainMode;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditEventHash;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.checkpoint.FileAuditCheckpointSink;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.retention.AuditRetention;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusals of {@code accept-crash-line} (task 293), one fixture per condition, so that each negative fails at
 * the check it names and not at an earlier one. {@link CrashLab} is shared with the retention test.
 */
public class AuditChainVerifierCliAcceptCrashLineTest {

    static final String D1 = "audit-2026-03-01.log";
    static final String D2 = "audit-2026-03-02.log";
    static final String D3 = "audit-2026-03-03.log";
    static final String D9 = "audit-2026-09-09.log";
    static final String SEP = "\u001f";
    static final String GENESIS = "0".repeat(64);

    @TempDir
    Path tempDir;

    /** How a forged line gets its eventHash. */
    public enum Hash { MODE, UNKEYED, KEEP }

    /** The outcome of one CLI run. */
    public record Run(int code, String out, String err) {
        public String all() {
            return out + "\n" + err;
        }
    }

    /** A directory of audit segments, a checkpoint store, and a way to run the CLI against them. */
    public static final class CrashLab {
        public static final Clock NOW = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

        public final Path root;
        public final Path dir;
        public final Path store;
        public final boolean keyed;
        public final AuditChainKey key = new AuditChainKey("k1", filled(1));
        public final AuditChainKey otherKey = new AuditChainKey("k0", filled(2));
        public final AuditChainMode mode;
        public final List<AuditChainKey> keys;
        public final Path keyFile;
        public final Path otherKeyFile;
        public SegmentedFileAuditSink sink;
        public final FileAuditCheckpointSink cps;
        public Instant at = Instant.parse("2026-03-01T00:00:00Z");
        public final Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return at;
            }
        };

        public CrashLab(Path root, boolean keyed) throws IOException {
            this.root = root;
            this.dir = root.resolve("seg");
            this.store = root.resolve("cp.jsonl");
            this.keyed = keyed;
            this.mode = keyed ? AuditChainMode.keyed(key) : AuditChainMode.unkeyed();
            this.keys = keyed ? List.of(key) : List.of();
            Files.createDirectories(root);
            this.keyFile = writeKey(root.resolve("k1.key"), key);
            this.otherKeyFile = writeKey(root.resolve("k0.key"), otherKey);
            this.sink = new SegmentedFileAuditSink(dir);
            this.cps = new FileAuditCheckpointSink(store, root.resolve("unused-audit.log"));
        }

        private static byte[] filled(int b) {
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, (byte) b);
            return bytes;
        }

        private static Path writeKey(Path file, AuditChainKey key) throws IOException {
            byte[] bytes = key.id().equals("k1") ? filled(1) : filled(2);
            Files.writeString(file, key.id() + "\n" + Base64.getEncoder().encodeToString(bytes) + "\n");
            return file;
        }

        /** A recorder; with {@code boot} it writes BOOT (and later checkpoints) into the store. */
        public AuditRecorder writer(String id, boolean boot) {
            return new AuditRecorder(sink, clock, id, boot ? cps : null, null, mode);
        }

        public AuditEvent rec(AuditRecorder w, String instant) {
            at = Instant.parse(instant);
            return w.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                    Set.of("a:ANSWERED"), Set.of(), "corr");
        }

        public List<AuditEvent> rec(AuditRecorder w, String... instants) {
            List<AuditEvent> out = new ArrayList<>();
            for (String instant : instants) {
                out.add(rec(w, instant));
            }
            return out;
        }

        /** A record that carries an external correlation id, so its last field is non-empty. */
        public AuditEvent recExt(AuditRecorder w, String instant) {
            at = Instant.parse(instant);
            return w.record(new AuditEntry("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                    Set.of("a:ANSWERED"), Set.of(), "corr", Map.of(), "", "", "ext-correlation-1234"));
        }

        public void closeSink() throws IOException {
            sink.close();
        }

        /** A new sink over the same directory, as a restarted process would have. */
        public void reopen() throws IOException {
            sink = new SegmentedFileAuditSink(dir);
        }

        public Path file(String name) {
            return dir.resolve(name);
        }

        public String read(String name) throws IOException {
            return Files.readString(file(name), StandardCharsets.UTF_8);
        }

        public void write(String name, String content) throws IOException {
            Files.writeString(file(name), content, StandardCharsets.UTF_8);
        }

        public void append(String name, String content) throws IOException {
            Files.writeString(file(name), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        public void appendStore(String content) throws IOException {
            Files.writeString(store, content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }

        public List<String> names() throws IOException {
            try (Stream<Path> s = Files.list(dir)) {
                return s.map(p -> p.getFileName().toString()).sorted().toList();
            }
        }

        /** Start offset of the segment's last line, terminated or not. */
        public long lastLineOffset(String name) throws IOException {
            byte[] b = Files.readAllBytes(file(name));
            int end = b.length > 0 && b[b.length - 1] == '\n' ? b.length - 1 : b.length;
            int i = end - 1;
            while (i >= 0 && b[i] != '\n') {
                i--;
            }
            return i + 1;
        }

        /** Start offset of line {@code index} (0-based). */
        public long lineOffset(String name, int index) throws IOException {
            byte[] b = Files.readAllBytes(file(name));
            long start = 0;
            int seen = 0;
            for (int i = 0; i < b.length && seen < index; i++) {
                if (b[i] == '\n') {
                    seen++;
                    start = i + 1;
                }
            }
            return start;
        }

        /** Replaces the last line (and its terminator) with {@code replacement}, verbatim. */
        public void replaceLastLine(String name, String replacement) throws IOException {
            write(name, read(name).substring(0, (int) lastLineOffset(name)) + replacement);
        }

        /** Replaces line {@code index} (0-based, with its terminator) with {@code replacement}. */
        public void replaceLine(String name, int index, String replacement) throws IOException {
            String content = read(name);
            int from = (int) lineOffset(name, index);
            int to = content.indexOf('\n', from);
            write(name, content.substring(0, from) + replacement + (to < 0 ? "" : content.substring(to + 1)));
        }

        /** Removes the first {@code n} lines. */
        public void dropFirstLines(String name, int n) throws IOException {
            write(name, read(name).substring((int) lineOffset(name, n)));
        }

        public String sha(String name, long offset) throws IOException {
            byte[] b = Files.readAllBytes(file(name));
            int end = (int) offset;
            while (end < b.length && b[end] != '\n') {
                end++;
            }
            return sha256(Arrays.copyOfRange(b, (int) offset, end));
        }

        public static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** {@code template}'s line with the given instance, sequence and previous hash, and the chosen hash. */
        public String forge(AuditEvent template, String instance, long seq, String prev, Hash hash) {
            String[] f = AuditRecordFormat.serialize(template).split(SEP, -1);
            f[16] = instance;
            f[17] = Long.toString(seq);
            f[18] = prev;
            if (hash != Hash.KEEP) {
                AuditEvent draft = AuditRecordFormat.parse(String.join(SEP, f));
                boolean useKey = hash == Hash.MODE && keyed;
                f[19] = useKey ? AuditEventHash.compute(draft, key) : AuditEventHash.compute(draft);
            }
            return String.join(SEP, f);
        }

        public String line(AuditEvent e) {
            return AuditRecordFormat.serialize(e);
        }

        public void checkpoint(AuditCheckpoint.Kind kind, String instance, long seq, String hash) {
            cps.record(new AuditCheckpoint(kind, instance, seq, hash, at));
        }

        public void checkpointAt(AuditCheckpoint.Kind kind, String instance, long seq, String hash,
                                 String recorded) {
            cps.record(new AuditCheckpoint(kind, instance, seq, hash, Instant.parse(recorded)));
        }

        public void boot(String instance) {
            checkpoint(AuditCheckpoint.Kind.BOOT, instance, 0, GENESIS);
        }

        public void anchor(String instance, long seq, String hash, String segmentDate, String recorded) {
            cps.record(new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, instance, seq, hash,
                    Instant.parse(recorded), LocalDate.parse(segmentDate)));
        }

        public List<String> keyFlags() {
            return keyed ? List.of("--hmac-key-file", keyFile.toString()) : List.of();
        }

        public Run cli(List<String> args) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = AuditChainVerifierCli.run(args.toArray(new String[0]),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        }

        /** The command with exactly these flags after the required ones. */
        public Run acceptRaw(String segment, long offset, String sha, List<String> flags) {
            List<String> args = new ArrayList<>(List.of("accept-crash-line", dir.toString(), "--checkpoints",
                    store.toString(), "--segment", segment, "--offset", Long.toString(offset), "--sha256", sha));
            args.addAll(flags);
            return cli(args);
        }

        /** The command with the line's real SHA-256 and this lab's key flags, plus {@code extra}. */
        public Run accept(String segment, long offset, String... extra) throws IOException {
            List<String> flags = new ArrayList<>(keyFlags());
            flags.addAll(List.of(extra));
            return acceptRaw(segment, offset, sha(segment, offset), flags);
        }

        /** Runs the CLI verify mode over the directory and store, with this lab's keys. */
        public Run verifyMode() {
            List<String> args = new ArrayList<>(List.of(dir.toString(), "--checkpoints", store.toString()));
            args.addAll(keyFlags());
            return cli(args);
        }

        /** Every file in the directory and the store, by name. */
        public Map<String, String> snapshot() throws IOException {
            Map<String, String> all = new HashMap<>();
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    all.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.ISO_8859_1));
                }
            }
            all.put("<store>", Files.readString(store, StandardCharsets.ISO_8859_1));
            return all;
        }

        public AuditRetention retention(Clock clock, List<AuditChainKey> chainKeys) {
            return new AuditRetention(dir, Period.ofMonths(6), cps, clock, false, chainKeys, false);
        }

        public AuditRetention retention() {
            return retention(NOW, keys);
        }

        /** Runs a purge and returns the exception it threw, or null. */
        public RuntimeException purgeThrown(AuditRetention r) {
            try {
                r.purge();
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        }

        /** Asserts the command refused with the new exit code, wrote nothing, and said {@code reasons}. */
        public Run refused(Run run, Map<String, String> before, String... reasons) throws IOException {
            assertThat(run.code()).as(run.all()).isEqualTo(AuditChainVerifierCli.EXIT_ACCEPT_REFUSED);
            for (String reason : reasons) {
                assertThat(run.all()).contains(reason);
            }
            assertThat(snapshot()).as("a refusal writes nothing").isEqualTo(before);
            return run;
        }

        public void close() throws IOException {
            sink.close();
            cps.close();
        }
    }

    // -- fixtures -------------------------------------------------------------------------------------

    /** One instance's only line is its first record, ended CR LF, on D1; another instance writes on D2 and D9. */
    record Genesis(CrashLab lab, AuditEvent event, String instance) {
    }

    private Genesis genesisLab(boolean keyed, boolean boot) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder c = lab.writer("c", boot);
        AuditEvent e = lab.rec(c, "2026-03-01T01:00:00Z");
        AuditRecorder o = lab.writer("o", false);
        lab.rec(o, "2026-03-02T01:00:00Z", "2026-03-02T02:00:00Z");
        lab.rec(o, "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.replaceLastLine(D1, lab.line(e) + "\r\n");
        return new Genesis(lab, e, e.instanceId());
    }

    /** w's records 1..n on D1, the last ended CR LF as a crash line; returns the events. */
    record Chain(CrashLab lab, List<AuditEvent> events, AuditRecorder writer) {
        String instance() {
            return events.get(0).instanceId();
        }
    }

    private Chain chainLab(boolean keyed, int n, boolean boot) throws IOException {
        CrashLab lab = new CrashLab(tempDir, keyed);
        AuditRecorder w = lab.writer("w", boot);
        String[] times = new String[n];
        for (int i = 0; i < n; i++) {
            times[i] = "2026-03-01T0" + (i + 1) + ":00:00Z";
        }
        List<AuditEvent> events = lab.rec(w, times);
        lab.rec(lab.writer("o", false), "2026-03-02T01:00:00Z");
        lab.rec(lab.writer("o2", false), "2026-09-09T01:00:00Z");
        lab.closeSink();
        lab.replaceLastLine(D1, lab.line(events.get(n - 1)) + "\r\n");
        return new Chain(lab, events, w);
    }

    /**
     * w's records 1..{@code n} on D1; D1 then holds only the last one, ended CR LF, and the store holds the
     * anchor {w, n-1, hash(n-1)} for D1, as if the earlier records had been purged.
     */
    private Chain anchoredLab(boolean keyed, int n) throws IOException {
        Chain c = chainLab(keyed, n, true);
        c.lab().dropFirstLines(D1, n - 1);
        c.lab().anchor(c.instance(), n - 1, c.events().get(n - 2).eventHash(), "2026-03-01", "2026-09-02T00:00:00Z");
        return c;
    }

    // -- conditions 1 to 4 ----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aWrongSha256IsRefusedAtCondition4EvenWithTheBootFlag(boolean keyed) throws IOException {
        CrashLab lab = genesisLab(keyed, true).lab();
        Map<String, String> before = lab.snapshot();
        String wrong = "0".repeat(64);

        lab.refused(lab.acceptRaw(D1, 0, wrong, lab.keyFlags()), before, "condition 4");
        List<String> withFlag = new ArrayList<>(lab.keyFlags());
        withFlag.add("--no-boot-checkpoint");
        lab.refused(lab.acceptRaw(D1, 0, wrong, withFlag), before, "condition 4");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLineWhoseInstanceHasALaterRecordIsRefusedAtCondition3(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 7, true);
        CrashLab lab = c.lab();
        // shape D: record 6 ended CR LF with record 7 after it
        lab.write(D1, String.join("", c.events().stream().limit(5).map(e -> lab.line(e) + "\n").toList())
                + lab.line(c.events().get(5)) + "\r\n" + lab.line(c.events().get(6)) + "\n");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, lab.lineOffset(D1, 5)), before, "condition 3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aHashedLineIsRefusedAtCondition2(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 3, true);
        Map<String, String> before = c.lab().snapshot();

        c.lab().refused(c.lab().accept(D1, c.lab().lineOffset(D1, 1)), before, "condition 2");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLineThatDoesNotParseIsRefusedAtCondition2WithNoHashReport(boolean keyed) throws IOException {
        Genesis g = genesisLab(keyed, true);
        g.lab().replaceLastLine(D1, g.lab().line(g.event()).substring(0, 60) + "\r\n");
        Map<String, String> before = g.lab().snapshot();

        Run run = g.lab().refused(g.lab().accept(D1, 0), before, "condition 2");
        assertThat(run.all()).doesNotContain("KEYED_MATCH").doesNotContain("NO_MATCH_LOOKS_CUT")
                .doesNotContain("NO_UNKEYED_MATCH");
    }

    @Test
    void aParsedNewlineTerminatedEditedRecordIsRefusedAtCondition2BeforeAnyBranch() throws IOException {
        Genesis g = genesisLab(true, true);
        g.lab().replaceLastLine(D1, g.lab().line(g.event()).replace("ALLOW", "DENY") + "\n");
        Map<String, String> before = g.lab().snapshot();

        Run run = g.lab().refused(g.lab().accept(D1, 0), before, "condition 2");
        assertThat(run.all()).doesNotContain("condition 5");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anOffsetThatIsNotALineStartIsRefusedAtCondition1(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 3, true);
        CrashLab lab = c.lab();
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.acceptRaw(D1, lab.lineOffset(D1, 2) + 5, "0".repeat(64), lab.keyFlags()), before,
                "condition 1");
        lab.refused(lab.acceptRaw(D1, 1_000_000, "0".repeat(64), lab.keyFlags()), before, "condition 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../seg/audit-2026-03-01.log", "audit-notes.log", "audit-2026-04-01.log",
            "sub/audit-2026-03-01.log"})
    void aSegmentOutsideTheDirectoryOrNotMatchingThePatternIsRefusedAtCondition1(String segment)
            throws IOException {
        Genesis g = genesisLab(false, true);
        Map<String, String> before = g.lab().snapshot();

        g.lab().refused(g.lab().acceptRaw(segment, 0, "0".repeat(64), List.of()), before, "condition 1");
    }

    // -- condition 5, branch (i) ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIRefusesAnInstanceBrokenBeforeTheLineAtCheck1(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.replaceLine(D1, 1, lab.line(c.events().get(1)).replace("ALLOW", "DENY") + "\n");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 5 (i) check 1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIRefusesANonGenesisStartAtCheck1WhenOnlyTheFindingRefusesIt(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        // a fragment line (a structural anomaly), then w's record 3 as its first line, then 4, then crash line 5
        String body = lab.line(c.events().get(2)) + "\n" + lab.line(c.events().get(3)) + "\n"
                + lab.line(c.events().get(4)) + "\r\n";
        lab.write(D1, "fragment\r\n" + body);
        Map<String, String> before = lab.snapshot();
        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(lab.dir, lab.store,
                Period.ofMonths(6), lab.keys, false);
        assertThat(report.writers()).filteredOn(w -> w.instanceId().equals(c.instance()))
                .allSatisfy(w -> {
                    assertThat(w.broken()).isFalse();
                    assertThat(w.nonGenesisStart()).isPresent();
                });

        lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 5 (i) check 1");
    }

    @Test
    void branchIOfAKeyedChainIsRefusedAtCheck1WithNoKeyAndWithAWrongKey() throws IOException {
        Chain c = chainLab(true, 5, true);
        CrashLab lab = c.lab();
        Map<String, String> before = lab.snapshot();
        long offset = lab.lastLineOffset(D1);

        lab.refused(lab.acceptRaw(D1, offset, lab.sha(D1, offset), List.of()), before, "condition 5 (i) check 1");
        lab.refused(lab.acceptRaw(D1, offset, lab.sha(D1, offset),
                List.of("--hmac-key-file", lab.otherKeyFile.toString())), before, "condition 5 (i) check 1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIRefusesAnInstanceWithNoHashedRecordBeforeTheLineAtCheck2(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 2, true);
        CrashLab lab = c.lab();
        lab.write(D1, lab.line(c.events().get(0)) + "\r\n" + lab.line(c.events().get(1)) + "\r\n");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 5 (i) check 2");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIRefusesAPreviousHashThatIsNotTheLastHashedEventHashAtCheck3(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 4, true);
        CrashLab lab = c.lab();
        lab.replaceLastLine(D1, lab.forge(c.events().get(3), c.instance(), 4, "ab".repeat(32), Hash.MODE) + "\r\n");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 5 (i) check 3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIRefusesASequenceThatSkipsByTwoAtCheck4(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 4, true);
        CrashLab lab = c.lab();
        lab.replaceLastLine(D1, lab.forge(c.events().get(3), c.instance(), 5, c.events().get(2).eventHash(),
                Hash.MODE) + "\r\n");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 5 (i) check 4");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aGenesisLineOfAnInstanceWithOtherRecordsIsRefusedBySelectionAtBranchICheck3(boolean keyed)
            throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.write(D1, lab.read(D1).replace("\r\n", "\n") + lab.line(c.events().get(0)) + "\r\n");
        Map<String, String> before = lab.snapshot();
        assertThat(lab.cps.crashLinesAccepted()).isEmpty();

        Run run = lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 5 (i) check 3");
        assertThat(run.all()).doesNotContain("condition 5 (ii)");
    }

    // -- condition 5, branch (ii) ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIRefusesASoleLineWhosePreviousHashIsNotGenesisAtCheck1(boolean keyed) throws IOException {
        Genesis g = genesisLab(keyed, true);
        g.lab().replaceLastLine(D1, g.lab().forge(g.event(), g.instance(), 1, "ab".repeat(32), Hash.MODE) + "\r\n");
        Map<String, String> before = g.lab().snapshot();

        g.lab().refused(g.lab().accept(D1, 0), before, "condition 5 (ii) check 1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIRefusesASoleGenesisLineAtSequence51AtCheck2BeforeCondition7WouldPassIt(boolean keyed)
            throws IOException {
        Genesis g = genesisLab(keyed, true);
        g.lab().replaceLastLine(D1, g.lab().forge(g.event(), g.instance(), 51, GENESIS, Hash.MODE) + "\r\n");
        g.lab().checkpoint(AuditCheckpoint.Kind.PERIODIC, g.instance(), 50, "ab".repeat(32));
        Map<String, String> before = g.lab().snapshot();

        g.lab().refused(g.lab().accept(D1, 0), before, "condition 5 (ii) check 2");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void branchIIRefusesASoleGenesisLineAtSequenceZeroOrBelow(long sequence) throws IOException {
        Genesis g = genesisLab(false, true);
        String[] f = g.lab().forge(g.event(), g.instance(), 1, GENESIS, Hash.MODE).split(SEP, -1);
        f[17] = Long.toString(sequence);
        String line = String.join(SEP, f);
        g.lab().replaceLastLine(D1, line + "\r\n");
        Map<String, String> before = g.lab().snapshot();
        String expected = parses(line) ? "condition 5 (ii) check 2" : "condition 2";

        g.lab().refused(g.lab().accept(D1, 0), before, expected);
    }

    private static boolean parses(String line) {
        try {
            AuditRecordFormat.parse(line);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // -- condition 5, branch (iii) --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLineAfterAnAnchorIsAcceptedWhenItContinuesTheAnchorExactly(boolean keyed) throws IOException {
        Chain c = anchoredLab(keyed, 4);
        CrashLab lab = c.lab();

        Run run = lab.accept(D1, 0);

        assertThat(run.code()).as(run.all()).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIIRefusesTwoAnchorsAtTheHighestSequenceWithDifferentHashesAtCheck1(boolean keyed)
            throws IOException {
        Chain c = anchoredLab(keyed, 4);
        c.lab().anchor(c.instance(), 3, "cd".repeat(32), "2026-03-01", "2026-09-02T00:00:00Z");
        Map<String, String> before = c.lab().snapshot();

        c.lab().refused(c.lab().accept(D1, 0), before, "condition 5 (iii) check 1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIIRefusesAGenesisLineOfAnInstanceThatHasAnAnchorAtCheck2(boolean keyed) throws IOException {
        Chain c = anchoredLab(keyed, 4);
        c.lab().write(D1, c.lab().forge(c.events().get(3), c.instance(), 1, GENESIS, Hash.MODE) + "\r\n");
        Map<String, String> before = c.lab().snapshot();

        c.lab().refused(c.lab().accept(D1, 0), before, "condition 5 (iii) check 2");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIIRefusesASequenceTwoAboveTheAnchorAtCheck3(boolean keyed) throws IOException {
        Chain c = anchoredLab(keyed, 4);
        c.lab().write(D1, c.lab().forge(c.events().get(3), c.instance(), 5, c.events().get(2).eventHash(),
                Hash.MODE) + "\r\n");
        Map<String, String> before = c.lab().snapshot();

        c.lab().refused(c.lab().accept(D1, 0), before, "condition 5 (iii) check 3");
    }

    @Test
    void branchIIIUsesOnlyTheHighestAnchorSoALowerAnchorsSuccessorIsRefusedAtCheck3() throws IOException {
        Chain c = anchoredLab(true, 4);
        CrashLab lab = c.lab();
        lab.anchor(c.instance(), 1, c.events().get(0).eventHash(), "2026-03-01", "2026-09-02T00:00:00Z");
        // w's genuine record 2, with a valid HMAC, follows the lower anchor exactly
        lab.write(D1, lab.line(c.events().get(1)) + "\r\n");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 5 (iii) check 3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIIRefusesAPreviousHashThatIsNotTheAnchorsHeadHashAtCheck4(boolean keyed) throws IOException {
        Chain c = anchoredLab(keyed, 4);
        c.lab().write(D1, c.lab().forge(c.events().get(3), c.instance(), 4, "ab".repeat(32), Hash.MODE) + "\r\n");
        Map<String, String> before = c.lab().snapshot();

        c.lab().refused(c.lab().accept(D1, 0), before, "condition 5 (iii) check 4");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIIRefusesAnAnchorThatIsNotOldEnoughAtCheck5(boolean keyed) throws IOException {
        Chain c = anchoredLab(keyed, 4);
        Map<String, String> before = c.lab().snapshot();

        c.lab().refused(c.lab().accept(D1, 0, "--min-retention", "P2Y"), before, "condition 5 (iii) check 5");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIIRefusesAnAnchorContradictedByALowerCheckpointRecordedLaterAtCheck6(boolean keyed)
            throws IOException {
        Chain c = anchoredLab(keyed, 4);
        c.lab().checkpointAt(AuditCheckpoint.Kind.PERIODIC, c.instance(), 2, "ab".repeat(32),
                "2026-03-05T00:00:00Z");
        Map<String, String> before = c.lab().snapshot();

        c.lab().refused(c.lab().accept(D1, 0), before, "condition 5 (iii) check 6");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void branchIIIRefusesALineDatedBeforeTheAnchorsSegmentAtCheck7(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 4, true);
        CrashLab lab = c.lab();
        lab.dropFirstLines(D1, 3);
        lab.anchor(c.instance(), 3, c.events().get(2).eventHash(), "2026-03-02", "2026-09-02T00:00:00Z");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 5 (iii) check 7");
    }

    // -- condition 6 ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStoreWhoseLastByteIsNotANewlineIsRefusedAtCondition6AndLeftAlone(boolean keyed) throws IOException {
        CrashLab lab = genesisLab(keyed, true).lab();
        lab.appendStore("{\"kind\":\"PERIO");
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 6");
    }

    // -- condition 7 (b) ------------------------------------------------------------------------------

    private static String hash(int i) {
        return String.format("%02x", i).repeat(32);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void condition7bRefusesALineAtOrBelowAPeriodicCheckpointOnBranchII(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.write(D1, lab.line(c.events().get(0)) + "\r\n");
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, c.events().get(4).eventHash());
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 7 (b)", "a PERIODIC checkpoint at sequence 5");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void condition7bRefusesALineAtOrBelowAShutdownCheckpointOnBranchII(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.write(D1, lab.line(c.events().get(0)) + "\r\n");
        lab.checkpoint(AuditCheckpoint.Kind.SHUTDOWN, c.instance(), 5, c.events().get(4).eventHash());
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 7 (b)", "a SHUTDOWN checkpoint at sequence 5");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void condition7bRefusesOnBranchIWhenLaterRecordsAreDeleted(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 3, true);
        CrashLab lab = c.lab();
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, hash(5));
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 7 (b)",
                "a PERIODIC checkpoint at sequence 5");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void condition7bRefusesALineAtTheCheckpointsOwnSequence(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, c.events().get(4).eventHash());
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, lab.lastLineOffset(D1)), before, "condition 7 (b)",
                "a PERIODIC checkpoint at sequence 5");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void condition7bNamesTheHighestCheckpointOnBranchIIIWhateverTheStoreOrder(boolean keyed) throws IOException {
        Chain c = anchoredLab(keyed, 4);
        CrashLab lab = c.lab();
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, hash(5));
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 6, hash(6));
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 7 (b)", "a PERIODIC checkpoint at sequence 6");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void condition7bNamesAShutdownAtTheHighestSequenceOnBranchIII(boolean keyed) throws IOException {
        Chain c = anchoredLab(keyed, 4);
        CrashLab lab = c.lab();
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, hash(5));
        lab.checkpoint(AuditCheckpoint.Kind.SHUTDOWN, c.instance(), 6, hash(6));
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 7 (b)", "a SHUTDOWN checkpoint at sequence 6");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void condition7bPrefersShutdownOverPeriodicAtTheSameSequence(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.write(D1, lab.line(c.events().get(0)) + "\r\n");
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, c.events().get(4).eventHash());
        lab.checkpoint(AuditCheckpoint.Kind.SHUTDOWN, c.instance(), 5, c.events().get(4).eventHash());
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 7 (b)", "a SHUTDOWN checkpoint at sequence 5");
    }

    // -- condition 7 (a) ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRenamedInstanceWithNoBootIsRefusedAtCondition7aAfterTheHashReport(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, c.events().get(4).eventHash());
        lab.write(D1, lab.forge(c.events().get(0), "ghost/00000000-0000-0000-0000-000000000000", 1, GENESIS,
                Hash.KEEP) + "\r\n");
        Map<String, String> before = lab.snapshot();

        Run run = lab.refused(lab.accept(D1, 0), before, "condition 7 (a)", "ghost/00000000",
                "Without a BOOT, condition 7 cannot be applied");
        assertThat(run.all()).contains(keyed ? "NO_MATCH_LOOKS_CUT" : "NO_UNKEYED_MATCH");
        assertThat(run.all().indexOf("strong sign of tampering"))
                .isLessThan(run.all().indexOf("Without a BOOT, condition 7 cannot be applied"));
        assertThat(run.all().indexOf("Without a BOOT, condition 7 cannot be applied"))
                .isLessThan(run.all().indexOf("--no-boot-checkpoint"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aGenuineCrashWithNoBootIsRefusedAtCondition7aWithoutTheFlag(boolean keyed) throws IOException {
        CrashLab lab = genesisLab(keyed, false).lab();
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0), before, "condition 7 (a)");
    }

    // -- the flag waives 7 (a) and nothing else -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theBootFlagDoesNotWaiveCondition7bForAnInstanceThatHasABoot(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, true);
        CrashLab lab = c.lab();
        lab.write(D1, lab.line(c.events().get(0)) + "\r\n");
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, c.events().get(4).eventHash());
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0, "--no-boot-checkpoint"), before, "condition 7 (b)",
                "a PERIODIC checkpoint at sequence 5");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theBootFlagDoesNotWaiveCondition7bForAnInstanceWithNoBoot(boolean keyed) throws IOException {
        Chain c = chainLab(keyed, 5, false);
        CrashLab lab = c.lab();
        lab.write(D1, lab.line(c.events().get(0)) + "\r\n");
        lab.checkpoint(AuditCheckpoint.Kind.PERIODIC, c.instance(), 5, c.events().get(4).eventHash());
        Map<String, String> before = lab.snapshot();

        lab.refused(lab.accept(D1, 0, "--no-boot-checkpoint"), before, "condition 7 (b)",
                "a PERIODIC checkpoint at sequence 5");
    }

    @Test
    void theBootFlagDoesNotWaiveCondition8() throws IOException {
        Genesis g = genesisLab(true, false);
        g.lab().replaceLastLine(D1, g.lab().forge(g.event(), g.instance(), 1, GENESIS, Hash.UNKEYED) + "\r\n");
        Map<String, String> before = g.lab().snapshot();

        g.lab().refused(g.lab().accept(D1, 0, "--no-boot-checkpoint"), before, "condition 8",
                "UNKEYED_ONLY_FORGER_SHAPE");
    }

    // -- condition 8 ----------------------------------------------------------------------------------

    @Test
    void anUnkeyedOnlyHashInAKeyedRunIsRefusedAtCondition8() throws IOException {
        Genesis g = genesisLab(true, true);
        g.lab().replaceLastLine(D1, g.lab().forge(g.event(), g.instance(), 1, GENESIS, Hash.UNKEYED) + "\r\n");
        Map<String, String> before = g.lab().snapshot();

        g.lab().refused(g.lab().accept(D1, 0), before, "condition 8", "UNKEYED_ONLY_FORGER_SHAPE");
    }

    @Test
    void anUnkeyedOnlyHashIsAcceptedWhenUnkeyedWritersAreAllowed() throws IOException {
        Genesis g = genesisLab(true, true);
        g.lab().replaceLastLine(D1, g.lab().forge(g.event(), g.instance(), 1, GENESIS, Hash.UNKEYED) + "\r\n");

        Run run = g.lab().accept(D1, 0, "--allow-unkeyed-writers");

        assertThat(run.code()).as(run.all()).isEqualTo(0);
        assertThat(run.out()).contains("UNKEYED_MATCH");
    }
}
