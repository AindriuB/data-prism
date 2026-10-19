package io.github.aindriub.dataprism.loadtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code java -jar data-prism-load-test.jar <scenario.json> [--repo-root <dir>] [--results-dir <dir>]}.
 *
 * <p>Starts the synthetic sources, the token issuer and the real server, drives the scenario, writes the
 * result file, and stops everything it started, including when the run fails.
 */
public final class LoadTestMain {

    private LoadTestMain() { }

    public static void main(String[] args) throws Exception {
        Path scenarioFile = null;
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        Path resultsDir = Path.of("results").toAbsolutePath();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo-root" -> repoRoot = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--results-dir" -> resultsDir = Path.of(args[++i]).toAbsolutePath();
                default -> scenarioFile = Path.of(args[i]);
            }
        }
        if (scenarioFile == null) {
            System.err.println("usage: LoadTestMain <scenario.json> [--repo-root <dir>] [--results-dir <dir>]");
            System.exit(2);
        }
        Scenario scenario = Scenario.load(scenarioFile);
        List<SourceDefinition> sources = SourceGenerator.generate(scenario);
        Path workDir = Files.createTempDirectory("data-prism-load-test-");
        Instant startedAt = Instant.now();
        try (TokenIssuer issuer = new TokenIssuer(workDir);
                SyntheticSources fixtures = new SyntheticSources(sources, scenario.seed(), issuer.tls())) {
            Path catalogue = workDir.resolve("json-sources.yaml");
            Files.writeString(catalogue, SourceGenerator.catalogueYaml(sources, fixtures.baseUrl()));
            System.out.println("scenario " + scenario.name() + ": " + sources.size() + " sources, starting server");
            try (ServerProcess server = ServerProcess.start(repoRoot, workDir, catalogue, issuer, scenario)) {
                System.out.println("server healthy; driving " + scenario.load());
                String token = issuer.mintToken(Duration.ofHours(12));
                LoadDriver.Run run = new LoadDriver(server.mcpBaseUrl(), token, scenario).run();
                Path file = ResultWriter.write(resultsDir, scenario, startedAt, git(repoRoot, "rev-parse", "HEAD"),
                        !git(repoRoot, "status", "--porcelain").isBlank(), run);
                System.out.println("wrote " + file);
            }
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static String git(Path repoRoot, String... arguments) {
        try {
            String[] command = new String[arguments.length + 3];
            command[0] = "git";
            command[1] = "-C";
            command[2] = repoRoot.toString();
            System.arraycopy(arguments, 0, command, 3, arguments.length);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // Temporary files only; a leftover directory under the OS temp dir is harmless.
        }
    }
}
