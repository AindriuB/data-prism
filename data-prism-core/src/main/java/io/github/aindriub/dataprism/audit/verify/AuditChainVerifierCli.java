package io.github.aindriub.dataprism.audit.verify;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditEventHash;
import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Period;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier.AnomalyType;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier.Break;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier.CheckpointFinding;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier.StructuralAnomaly;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier.VerificationReport;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier.WriterResult;

/**
 * The offline entry point for {@link AuditChainVerifier}.
 *
 * <p>Deliberately a command-line tool and nothing else: not a Spring
 * Actuator endpoint, not a startup check. An in-process endpoint would
 * conflate "the running instance says its own log is fine" with independent
 * verification, which is a weaker compliance story than a separate process
 * reading the file from outside the application that wrote it.
 *
 * <p>Never run against {@code Slf4jAuditSink} output: log infrastructure
 * reorders, compresses and ships lines outside this application's control,
 * so verifying that would prove less than an operator would assume. Only
 * {@link FileAuditSink}'s own file has the byte-for-byte shape this class
 * relies on.
 */
public final class AuditChainVerifierCli {

    /** Every writer's chain verified: no break, no structural anomaly, no possibly-in-flight tail. */
    public static final int EXIT_INTACT = 0;
    /** The input file could not be opened or read, or the invocation was malformed. */
    public static final int EXIT_UNREADABLE_INPUT = 1;
    /** At least one writer's chain has a break, or a line could not be ruled out as tampering. */
    public static final int EXIT_BREAK_DETECTED = 2;
    /** No break, but the final record has no terminating newline: possibly in flight. */
    public static final int EXIT_POSSIBLY_IN_FLIGHT = 3;
    /** No break, but a known non-tampering structural anomaly was found. Never returned with a break. */
    public static final int EXIT_STRUCTURAL_ANOMALY = 4;
    /**
     * Given {@code --checkpoints}: a writer's tail was deleted back past a checkpoint, or a
     * checkpointed writer has no surviving records. Only returned when no break was found.
     */
    public static final int EXIT_CHECKPOINT_MISMATCH = 5;

    private static final String LIMITATION_HEAD =
            "LIMITATION: this verifier detects an edit or a deletion of a record already written, replayed "
                    + "independently per writer -- but ONLY for the fields joined into AuditEventHash's "
                    + "chained hash: eventId, timestamp, instanceId, sequence, principalId, clientId, tool, "
                    + "entityType, subjectPseudonym, parameterFingerprint, privacyProfile, scopeId, purpose, "
                    + "caseId, policyDecision, correlationId, sourceSystems, rejectedArguments and previousHash, and for version 2 records "
                    + "also recordVersion, fieldDispositions, approvalId and approverId. "
                    + "On its own it cannot detect truncation of a writer's most recent records: deleting "
                    + "the tail of an append-only file leaves a chain that verifies perfectly end to end. "
                    + "Given --checkpoints it detects truncation back past a checkpoint and a deleted boot "
                    + "that had checkpointed records, but records written after a writer's last checkpoint "
                    + "remain undetectable if deleted, and a checkpoint only helps if whoever can edit the "
                    + "audit file cannot also edit the checkpoint file. Neither file resists tampering by anyone who can write to it. This "
                    + "check also "
                    + "cannot resist an adversary who can write to this file directly";

    /** Unkeyed runs only: the chain is plain SHA-256, so a writer can recompute it. */
    private static final String UNKEYED_RESISTANCE =
            ": AuditEventHash is "
                    + "unkeyed SHA-256 over the joined record body, so anyone able to delete or alter a record "
                    + "can simply recompute every hash after it and the resulting chain verifies perfectly; "
                    + "resisting that needs a keyed MAC, which this run was not given a key for, or a checkpoint "
                    + "file the adversary cannot also rewrite.";

    private static final String LIMITATION_TAIL =
            " Read nothing above as a guarantee that this file is whole, that every "
                    + "field of every record is unaltered, or that it can never be altered without this check "
                    + "noticing -- only that no edit or deletion of a hashed field was found within the records "
                    + "this check could see, by someone who did not also recompute the chain that follows it.";

    /** Printed on unkeyed runs only (no {@code --hmac-key-file}). */
    private static final String LIMITATION = LIMITATION_HEAD + UNKEYED_RESISTANCE + LIMITATION_TAIL;

    /** Printed instead of {@link #LIMITATION} on keyed runs: the scope caveats, without the unkeyed claim. */
    private static final String KEYED_SCOPE = LIMITATION_HEAD + "." + LIMITATION_TAIL;

    /** Printed on every run given a key: what a keyed chain does and does not resist. */
    public static final String KEYED_LIMITATION =
            "KEYED CHAIN: a keyed chain resists a writer who lacks the key, not anyone who holds it, including "
                    + "the operator and the process that writes the file.";
    /** Prefix of the line printed, with their count, when {@code --allow-unkeyed-writers} was given. */
    public static final String UNKEYED_WRITERS_PREFIX = "UNKEYED WRITERS ALLOWED: ";

    private static String limitation(boolean keyed) {
        return keyed ? KEYED_SCOPE : LIMITATION;
    }

    private AuditChainVerifierCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        System.exit(code);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printHelp(out);
            return EXIT_INTACT;
        }
        Path path = null;
        Path checkpoints = null;
        Period minimumRetention = AuditChainVerifier.DEFAULT_MINIMUM_RETENTION;
        boolean minimumGiven = false;
        boolean malformed = false;
        boolean allowUnkeyed = false;
        List<Path> keyFiles = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--checkpoints".equals(args[i]) && checkpoints == null && i + 1 < args.length) {
                checkpoints = Path.of(args[++i]);
            } else if ("--min-retention".equals(args[i]) && !minimumGiven && i + 1 < args.length) {
                minimumGiven = true;
                try {
                    minimumRetention = Period.parse(args[++i]);
                } catch (java.time.format.DateTimeParseException e) {
                    malformed = true;
                }
            } else if ("--hmac-key-file".equals(args[i]) && i + 1 < args.length) {
                keyFiles.add(Path.of(args[++i]));
            } else if ("--allow-unkeyed-writers".equals(args[i]) && !allowUnkeyed) {
                allowUnkeyed = true;
            } else if (path == null && !args[i].startsWith("--")) {
                path = Path.of(args[i]);
            } else {
                malformed = true;
            }
        }
        if (malformed || path == null) {
            err.println("Usage: AuditChainVerifierCli <audit-log-file> [--checkpoints <checkpoint-file>] "
                    + "[--min-retention <ISO-8601 period>] [--hmac-key-file <path>]... [--allow-unkeyed-writers]  "
                    + "(or --help for exit codes)");
            out.println(limitation(!keyFiles.isEmpty()));
            return EXIT_UNREADABLE_INPUT;
        }
        List<AuditChainKey> keys = new ArrayList<>();
        for (Path keyFile : keyFiles) {
            AuditChainKey key = readKey(keyFile);
            if (key == null) {
                err.println("UNREADABLE INPUT: --hmac-key-file could not be read or is malformed; expected two "
                        + "lines, the key id then the base64 key (at least " + AuditChainKey.MIN_KEY_BYTES
                        + " bytes), and ids must be distinct");
                out.println(limitation(!keyFiles.isEmpty()));
                return EXIT_UNREADABLE_INPUT;
            }
            if (keys.stream().anyMatch(k -> k.id().equals(key.id()))) {
                err.println("UNREADABLE INPUT: --hmac-key-file gave two keys with the same id");
                out.println(limitation(!keyFiles.isEmpty()));
                return EXIT_UNREADABLE_INPUT;
            }
            keys.add(key);
        }

        VerificationReport report;
        try {
            report = !keys.isEmpty() || allowUnkeyed
                    ? AuditChainVerifier.verify(path, checkpoints, minimumRetention, keys, allowUnkeyed)
                    : checkpoints == null
                            ? AuditChainVerifier.verify(path)
                            : AuditChainVerifier.verify(path, checkpoints, minimumRetention);
        } catch (IOException e) {
            err.println("UNREADABLE INPUT: could not read " + path + ": " + e.getMessage());
            out.println(limitation(!keyFiles.isEmpty()));
            return EXIT_UNREADABLE_INPUT;
        }

        printReport(out, report);
        out.println(limitation(!keys.isEmpty()));
        if (!keys.isEmpty()) {
            out.println(KEYED_LIMITATION);
        }
        if (allowUnkeyed) {
            out.println(UNKEYED_WRITERS_PREFIX + report.unkeyedWriterCount() + " writer(s) verified only as an "
                    + "unkeyed chain, which anyone able to write the file can forge.");
        }

        if (report.hasBreak()) {
            return EXIT_BREAK_DETECTED;
        }
        if (report.hasCheckpointFinding()) {
            return EXIT_CHECKPOINT_MISMATCH;
        }
        if (report.hasStructuralAnomaly()) {
            return EXIT_STRUCTURAL_ANOMALY;
        }
        if (report.tail().isPresent()) {
            return EXIT_POSSIBLY_IN_FLIGHT;
        }
        return EXIT_INTACT;
    }

    private static void printReport(PrintStream out, VerificationReport report) {
        if (report.writers().isEmpty() && report.anomalies().isEmpty() && report.tail().isEmpty()
                && report.checkpointFindings().isEmpty()) {
            out.println("No records found.");
            return;
        }

        for (WriterResult writer : report.writers()) {
            out.println();
            out.println("Writer " + writer.instanceId() + ":");
            out.println("  first sequence seen: " + writer.firstSequence());
            out.println("  sequence count: " + writer.sequenceCount());
            out.println("  head hash: " + writer.headHash());
            writer.nonGenesisStart().ifPresent(ngs -> out.println("  " + ngs.message()));
            if (writer.broken()) {
                Break brk = writer.firstBreak().orElseThrow();
                out.println("  CHAIN BREAK at sequence " + brk.sequence() + ", byte offset " + brk.byteOffset()
                        + ": " + brk.reason() + ". This is evidence the record was edited or deleted after "
                        + "being written -- investigate immediately.");
                if (writer.afterBreakCount() > 0) {
                    out.println("  " + writer.afterBreakCount() + " later record(s) in this writer's chain "
                            + "follow the break above and are reported as after the break, not as separate "
                            + "breaks.");
                }
            } else if (writer.retentionAnchor().isPresent()) {
                AuditCheckpoint a = writer.retentionAnchor().get();
                out.println("  intact: every surviving record chains from the retention anchor at sequence "
                        + a.sequence() + " (head hash " + a.headHash() + ", recorded " + a.recordedAt()
                        + "); earlier records were purged under retention and are not verified here.");
            } else if (writer.nonGenesisStart().isEmpty()) {
                out.println("  intact: every record in this writer's chain verified against the one before it.");
            }
            writer.keyId().ifPresent(id -> out.println("  verified under key id: " + id));
            if (writer.unkeyedAllowed()) {
                out.println("  verified as an UNKEYED chain only (allowed by --allow-unkeyed-writers).");
            }
        }

        for (StructuralAnomaly anomaly : report.anomalies()) {
            out.println();
            out.println(header(anomaly.type()) + ": " + anomaly.message());
        }

        for (CheckpointFinding finding : report.checkpointFindings()) {
            out.println();
            out.println(finding.message() + " This is evidence of deletion, judged against a checkpoint file "
                    + "that is itself only as trustworthy as its custody.");
        }

        report.tail().ifPresent(tail -> {
            out.println();
            out.println("POSSIBLY IN FLIGHT (not a break): " + tail.message());
        });
    }

    /**
     * Reads a key file: line one the key id, line two the base64 key. Returns {@code null} for anything
     * unreadable or malformed; the caller reports only the flag, never the file's contents.
     */
    private static AuditChainKey readKey(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (lines.size() != 2) {
                return null;
            }
            return new AuditChainKey(lines.get(0), Base64.getDecoder().decode(lines.get(1)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String header(AnomalyType type) {
        return switch (type) {
            case INTERRUPTED_WRITE_FRAGMENT -> "INTERRUPTED WRITE, not tampering";
            case DUPLICATE_SEQUENCE -> "SINK-CONTRACT VIOLATION, not tampering";
            case UNPARSEABLE_RECORD -> "UNPARSEABLE RECORD, possible tampering";
            case RETENTION_ANCHOR_REJECTED -> "RETENTION ANCHOR REJECTED, possible tampering";
            case VERSION_REGRESSION -> "VERSION_REGRESSION, possible tampering";
            case FIELD_COUNT_MISMATCH -> "FIELD_COUNT_MISMATCH, tampering is possible";
            case TORN_CHECKPOINT_LINE -> "TORN CHECKPOINT LINE, not tampering";
        };
    }

    private static void printHelp(PrintStream out) {
        out.println("Usage: java -cp <classpath> io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli "
                + "<audit-log-file> [--checkpoints <checkpoint-file>] [--min-retention <ISO-8601 period>] "
                + "[--hmac-key-file <path>]... [--allow-unkeyed-writers]");
        out.println();
        out.println("--hmac-key-file <path> (repeatable) names a keyed-chain key file: two lines, the key id");
        out.println("on the first and the base64 key (at least " + AuditChainKey.MIN_KEY_BYTES
                + " bytes once decoded) on the second. With keys, each writer must");
        out.println("verify under exactly one of them; a writer that verifies under none is a break, and so is one");
        out.println("that verifies only as an unkeyed chain unless --allow-unkeyed-writers is given. Key material");
        out.println("is never printed. The files do not say whether a chain is keyed; you do, by passing keys.");
        out.println();
        out.println("--min-retention is the shortest retention a retention anchor may stand for (default "
                + AuditChainVerifier.DEFAULT_MINIMUM_RETENTION + "); pass your own period if you run the purge with");
        out.println("the below-minimum override. An anchor over a segment younger than this is rejected.");
        out.println();
        out.println("<audit-log-file> may also be a directory of audit-YYYY-MM-DD.log segments written by");
        out.println("SegmentedFileAuditSink, read in date order.");
        out.println();
        out.println("Replays each writer's hash chain in a file written by FileAuditSink and reports either");
        out.println("an intact chain or the first edit or deletion detected, per writer.");
        out.println();
        out.println("Exit codes:");
        out.println("  " + EXIT_INTACT + "  intact -- every writer's chain verified: no break, no structural "
                + "anomaly, no in-flight tail");
        out.println("  " + EXIT_UNREADABLE_INPUT + "  unreadable input -- the file could not be opened or read, "
                + "or the invocation was malformed");
        out.println("  " + EXIT_BREAK_DETECTED + "  break detected -- an edit or deletion was found in at "
                + "least one writer's chain, or a line could not be ruled out as tampering");
        out.println("  " + EXIT_POSSIBLY_IN_FLIGHT + "  possibly-in-flight tail -- the final record has no "
                + "terminating newline; not a break");
        out.println("  " + EXIT_STRUCTURAL_ANOMALY + "  structural anomaly -- an interrupted-write "
                + "fragment, a sink-contract duplicate-sequence violation, or a writer's chain not "
                + "starting at GENESIS (every writer's own first record is GENESIS, including after an "
                + "ordinary restart, so this means deletion of that writer's earliest records cannot be "
                + "ruled out); never returned together with exit code " + EXIT_BREAK_DETECTED);
        out.println("  " + EXIT_CHECKPOINT_MISMATCH + "  checkpoint mismatch -- only with --checkpoints: a "
                + "writer's tail was deleted back past a checkpoint, or a checkpointed writer has no surviving "
                + "records; returned only when no break (exit code " + EXIT_BREAK_DETECTED + ") was found, "
                + "and takes precedence over exit codes " + EXIT_STRUCTURAL_ANOMALY + " and "
                + EXIT_POSSIBLY_IN_FLIGHT);
        out.println();
        out.println(LIMITATION);
    }
}
