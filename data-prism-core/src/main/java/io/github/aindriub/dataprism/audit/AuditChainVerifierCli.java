package io.github.aindriub.dataprism.audit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import io.github.aindriub.dataprism.audit.AuditChainVerifier.AnomalyType;
import io.github.aindriub.dataprism.audit.AuditChainVerifier.Break;
import io.github.aindriub.dataprism.audit.AuditChainVerifier.StructuralAnomaly;
import io.github.aindriub.dataprism.audit.AuditChainVerifier.VerificationReport;
import io.github.aindriub.dataprism.audit.AuditChainVerifier.WriterResult;

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

    private static final String LIMITATION =
            "LIMITATION: this verifier detects an edit or a deletion of a record already written, replayed "
                    + "independently per writer. It cannot detect truncation of a writer's most recent "
                    + "records: deleting the tail of an append-only file leaves a chain that verifies "
                    + "perfectly end to end. Detecting that needs an external checkpoint held outside "
                    + "operator control, which this release does not build. Read nothing above as a guarantee "
                    + "that this file is whole, or that it can never be altered without this check noticing -- "
                    + "only that no edit or deletion was found within the records this check could see.";

    private AuditChainVerifierCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printHelp(out);
            return EXIT_INTACT;
        }
        if (args.length != 1) {
            err.println("Usage: AuditChainVerifierCli <audit-log-file>  (or --help for exit codes)");
            out.println(LIMITATION);
            return EXIT_UNREADABLE_INPUT;
        }

        Path path = Path.of(args[0]);
        VerificationReport report;
        try {
            report = AuditChainVerifier.verify(path);
        } catch (IOException e) {
            err.println("UNREADABLE INPUT: could not read " + path + ": " + e.getMessage());
            out.println(LIMITATION);
            return EXIT_UNREADABLE_INPUT;
        }

        printReport(out, report);
        out.println(LIMITATION);

        if (report.hasBreak()) {
            return EXIT_BREAK_DETECTED;
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
        if (report.writers().isEmpty() && report.anomalies().isEmpty() && report.tail().isEmpty()) {
            out.println("No records found.");
            return;
        }

        for (WriterResult writer : report.writers()) {
            out.println();
            out.println("Writer " + writer.instanceId() + ":");
            out.println("  sequence count: " + writer.sequenceCount());
            out.println("  head hash: " + writer.headHash());
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
            } else {
                out.println("  intact: every record in this writer's chain verified against the one before it.");
            }
        }

        for (StructuralAnomaly anomaly : report.anomalies()) {
            out.println();
            out.println(header(anomaly.type()) + ": " + anomaly.message());
        }

        report.tail().ifPresent(tail -> {
            out.println();
            out.println("POSSIBLY IN FLIGHT (not a break): " + tail.message());
        });
    }

    private static String header(AnomalyType type) {
        return switch (type) {
            case INTERRUPTED_WRITE_FRAGMENT -> "INTERRUPTED WRITE, not tampering";
            case DUPLICATE_SEQUENCE -> "SINK-CONTRACT VIOLATION, not tampering";
            case UNPARSEABLE_RECORD -> "UNPARSEABLE RECORD, possible tampering";
        };
    }

    private static void printHelp(PrintStream out) {
        out.println("Usage: java -cp <classpath> io.github.aindriub.dataprism.audit.AuditChainVerifierCli "
                + "<audit-log-file>");
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
        out.println("  " + EXIT_STRUCTURAL_ANOMALY + "  structural non-tampering anomaly -- an "
                + "interrupted-write fragment or a sink-contract duplicate-sequence violation was found; "
                + "never returned together with exit code " + EXIT_BREAK_DETECTED);
        out.println();
        out.println(LIMITATION);
    }
}
