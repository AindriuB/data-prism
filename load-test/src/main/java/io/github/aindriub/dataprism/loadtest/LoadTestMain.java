package io.github.aindriub.dataprism.loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code java -jar data-prism-load-test.jar <scenario.json> [--repo-root <dir>] [--results-dir <dir>]}.
 *
 * <p>Starts the synthetic sources, the token issuer and the real server, drives the scenario, writes the
 * result file, and stops everything it started, including when the run fails.
 */
public final class LoadTestMain {

    private LoadTestMain() { }

    static final String USAGE = "usage: LoadTestMain <scenario.json> [--repo-root <dir>] [--results-dir <dir>]";

    record Arguments(Path scenarioFile, Path repoRoot, Path resultsDir) { }

    /** @throws IllegalArgumentException with a message naming the problem when the arguments are not usable */
    static Arguments parse(String[] args) {
        Path scenarioFile = null;
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        Path resultsDir = Path.of("results").toAbsolutePath();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo-root", "--results-dir" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " needs a directory");
                    }
                    Path value = Path.of(args[++i]).toAbsolutePath();
                    if (args[i - 1].equals("--repo-root")) {
                        repoRoot = value.normalize();
                    } else {
                        resultsDir = value;
                    }
                }
                default -> scenarioFile = Path.of(args[i]);
            }
        }
        if (scenarioFile == null) {
            throw new IllegalArgumentException("no scenario file given");
        }
        return new Arguments(scenarioFile, repoRoot, resultsDir);
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed;
        try {
            parsed = parse(args);
        } catch (IllegalArgumentException usage) {
            System.err.println(usage.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Path scenarioFile = parsed.scenarioFile();
        Path repoRoot = parsed.repoRoot();
        Path resultsDir = parsed.resultsDir();
        Scenario scenario = Scenario.load(scenarioFile);
        List<SourceDefinition> sources = SourceGenerator.generate(scenario);
        Path workDir = Files.createTempDirectory("data-prism-load-test-");
        Instant startedAt = Instant.now();
        // Ctrl-C and SIGTERM end the JVM without running finally blocks: the hook stops the server child and
        // removes the keystores. The finally path runs the same idempotent cleanup.
        RunCleanup cleanup = new RunCleanup(workDir);
        Thread hook = new Thread(cleanup, "load-test-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
        try (TokenIssuer issuer = new TokenIssuer(workDir);
                SyntheticSources fixtures = new SyntheticSources(sources, scenario.seed(), issuer.tls())) {
            Path catalogue = workDir.resolve("json-sources.yaml");
            Files.writeString(catalogue, SourceGenerator.catalogueYaml(sources, fixtures.baseUrl()));
            System.out.println("scenario " + scenario.name() + ": " + sources.size() + " sources, starting server");
            try (ServerProcess server = ServerProcess.start(repoRoot, workDir, resultsDir, catalogue, issuer, scenario, cleanup)) {
                System.out.println("server healthy; driving " + scenario.load());
                CredentialRotation credentials = new CredentialRotation(issuer::mintToken,
                        TokenIssuer.TOKEN_LIFETIME, ServerProcess.SCOPE_LIFETIME, System::nanoTime);
                LoadDriver.Run run = new LoadDriver(server.mcpBaseUrl(), credentials, scenario,
                        server::exitCode).run();
                Path file = ResultWriter.write(resultsDir, scenario, startedAt, git(repoRoot, "rev-parse", "HEAD"),
                        !git(repoRoot, "status", "--porcelain").isBlank(), run);
                System.out.println("wrote " + file);
                if (run.serverExitCode() != null) {
                    Path kept = server.keepLog();
                    throw new IllegalStateException("the server exited with " + run.serverExitCode()
                            + " during the run, which was stopped; the result so far is " + file
                            + (kept == null ? "" : " and the server log is kept at " + kept)
                            + "\nserver console tail:\n" + server.consoleTail(40));
                }
            }
        } finally {
            cleanup.run();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // The hook is the one running us; nothing to remove.
            }
        }
    }

    private static String git(Path repoRoot, String... arguments) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repoRoot.toString()));
        command.addAll(List.of(arguments));
        return boundedOutput(command, Duration.ofSeconds(10));
    }

    /**
     * The trimmed output of a command that exits 0 within {@code bound}, otherwise {@code "unknown"}. The output
     * is drained on another thread so that a command that never ends is bounded too, and is then killed.
     */
    static String boundedOutput(List<String> command, Duration bound) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(running.getInputStream().readAllBytes()).trim();
                } catch (IOException e) {
                    return "unknown";
                }
            });
            String text = output.get(bound.toNanos(), TimeUnit.NANOSECONDS);
            return process.waitFor(bound.toNanos(), TimeUnit.NANOSECONDS) && process.exitValue() == 0 ? text : "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
