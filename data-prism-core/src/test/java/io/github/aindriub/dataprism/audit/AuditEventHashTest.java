package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The version-2 length-prefixed hash encoding, its pinned vectors, and the v1 joining it leaves alone. */
class AuditEventHashTest {

    @TempDir
    Path tempDir;

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static Map<String, Map<String, String>> vectors() throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Map<String, String> current = null;
        try (var in = AuditEventHashTest.class.getResourceAsStream("/audit/hash-vectors.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[")) {
                    current = new LinkedHashMap<>();
                    out.put(line.substring(1, line.length() - 1), current);
                } else {
                    int eq = line.indexOf('=');
                    current.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        }
        return out;
    }

    private static Set<String> set(String csv) {
        return csv.isEmpty() ? Set.of() : new TreeSet<>(Arrays.asList(csv.split(",")));
    }

    private static Map<String, String> dispositions(String raw) {
        Map<String, String> m = new TreeMap<>();
        if (!raw.isEmpty()) {
            for (String pair : raw.split(";")) {
                int eq = pair.indexOf('=');
                m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return m;
    }

    private static String hashOf(int version, Map<String, String> v) {
        return AuditEventHash.compute(v.get("eventId"), Instant.parse(v.get("timestamp")), v.get("instanceId"),
                Long.parseLong(v.get("sequence")), v.get("principalId"), v.get("clientId"), v.get("tool"),
                v.get("entityType"), v.get("subjectPseudonym"), v.get("parameterFingerprint"),
                v.get("privacyProfile"), v.get("scopeId"), v.get("purpose"), v.get("caseId"),
                v.get("policyDecision"), v.get("correlationId"), set(v.get("sourceSystems")),
                set(v.get("rejectedArguments")), v.get("previousHash"), version,
                dispositions(v.getOrDefault("fieldDispositions", "")), v.getOrDefault("approvalId", ""),
                v.getOrDefault("approverId", ""), v.getOrDefault("externalCorrelationId", ""));
    }

    @Test
    void pinnedVectorsRecomputeForEveryVersion() throws IOException {
        Map<String, Map<String, String>> all = vectors();
        assertThat(all).containsKeys("v1", "v2", "v3");
        all.forEach((name, v) -> assertThat(hashOf(Integer.parseInt(name.substring(1)), v))
                .as(name).isEqualTo(v.get("expected")));
    }

    @Test
    void versionOneOverloadAndVersionOneOfTheLongOverloadAgreeWithTheGoldenVector() throws IOException {
        Map<String, String> v = vectors().get("v1");
        String viaShort = AuditEventHash.compute(v.get("eventId"), Instant.parse(v.get("timestamp")),
                v.get("instanceId"), Long.parseLong(v.get("sequence")), v.get("principalId"), v.get("clientId"),
                v.get("tool"), v.get("entityType"), v.get("subjectPseudonym"), v.get("parameterFingerprint"),
                v.get("privacyProfile"), v.get("scopeId"), v.get("purpose"), v.get("caseId"),
                v.get("policyDecision"), v.get("correlationId"), set(v.get("sourceSystems")),
                set(v.get("rejectedArguments")), v.get("previousHash"));
        assertThat(viaShort).isEqualTo(v.get("expected")).isEqualTo(hashOf(1, v));
    }

    /** Hash of an otherwise fixed record with the named overrides. */
    private static String hash(int version, Set<String> sources, Map<String, String> disp, String principal,
                               String client, String caseId) {
        return AuditEventHash.compute("e", FIXED.instant(), "i", 1, principal, client, "t", "ty", "s", "f", "p",
                "sc", "pu", caseId, "ALLOW", "co", sources, Set.of(), "prev", version, disp, "", "");
    }

    @Test
    void ambiguousPairsHashDifferentlyAtVersionTwo() {
        assertThat(hash(2, Set.of("a,b"), Map.of(), "p", "c", "x"))
                .isNotEqualTo(hash(2, new TreeSet<>(List.of("a", "b")), Map.of(), "p", "c", "x"));
        assertThat(hash(2, Set.of(), Map.of("p=MASK,q", "REDACT"), "p", "c", "x"))
                .isNotEqualTo(hash(2, Set.of(), Map.of("p", "MASK", "q", "REDACT"), "p", "c", "x"));
        assertThat(hash(2, Set.of(), Map.of(), "a|b", "c", "x"))
                .isNotEqualTo(hash(2, Set.of(), Map.of(), "a", "b|c", "x"));
        assertThat(hash(2, Set.of(), Map.of(), "p", "c", null))
                .isNotEqualTo(hash(2, Set.of(), Map.of(), "p", "c", "~"));
    }

    @Test
    void versionOneStillCollidesOnTheFirstPairAsAPinnedLimitation() {
        assertThat(hash(1, Set.of("a,b"), Map.of(), "p", "c", "x"))
                .isEqualTo(hash(1, new TreeSet<>(List.of("a", "b")), Map.of(), "p", "c", "x"));
    }

    @Test
    void currentVersionRecordEditedToVersionOneIsABreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "w");
            for (int i = 0; i < 2; i++) {
                recorder.record(new AuditEntry("p", "c", "t", "ty", "s", "f", "pr", "sc", "pu", "ca", "ALLOW",
                        Set.of("crm:ANSWERED"), Set.of(), "co", Map.of("crm:/email", "REDACT"), "a", "b"));
            }
        }
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThat(AuditChainVerifierCli.run(new String[] {path.toString()}, sink, sink))
                .isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
        String content = Files.readString(path, StandardCharsets.UTF_8);
        // recordVersion is the 21st unit-separator-delimited field of a version 3 line.
        StringBuilder edited = new StringBuilder();
        for (String line : content.split("\n")) {
            String[] fields = line.split("\u001f", -1);
            assertThat(fields).hasSize(25);
            assertThat(fields[20]).isEqualTo("3");
            fields[20] = "1";
            edited.append(String.join("\u001f", fields)).append('\n');
        }
        Files.writeString(path, edited.toString(), StandardCharsets.UTF_8);
        assertThat(AuditChainVerifierCli.run(new String[] {path.toString()}, sink, sink))
                .isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
    }
}
