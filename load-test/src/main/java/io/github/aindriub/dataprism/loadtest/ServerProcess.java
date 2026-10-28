package io.github.aindriub.dataprism.loadtest;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The real {@code data-prism-server} jar, started as a child process with the configured-JSON-sources
 * extension on its loader path, authentication on, and the scenario's {@code dataprism.limits.*}.
 *
 * <p>The HMAC key is random per run and reaches the server only through the child's environment.
 */
final class ServerProcess implements AutoCloseable {

    /** The privacy scope lifetime the server is started with; a long run rotates its scope before this ends. */
    static final Duration SCOPE_LIFETIME = Duration.ofHours(8);

    static final String HMAC_ENV = "DATAPRISM_LOADTEST_HMAC_KEY";

    private final Process process;
    private final int port;
    private final Path logFile;
    private final Path consoleFile;

    private final Path resultsDir;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ServerProcess(Process process, int port, Path logFile, Path consoleFile, Path resultsDir) {
        this.consoleFile = consoleFile;
        this.resultsDir = resultsDir;
        this.process = process;
        this.port = port;
        this.logFile = logFile;
    }

    static ServerProcess start(Path repoRoot, Path workDir, Path resultsDir, Path catalogue, TokenIssuer issuer,
            Scenario scenario, RunCleanup cleanup)
            throws IOException, InterruptedException {
        Path serverJar = single(repoRoot.resolve("data-prism-server/target"), "data-prism-server-*.jar");
        Path restJar = single(repoRoot.resolve("data-prism-connectors-rest/target"), "data-prism-connectors-rest-*.jar");
        int port = freePort();
        Path log = workDir.resolve("server.log");

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dloader.path=" + restJar,
                "-Ddataprism.json-sources.config-location=file:" + catalogue.toAbsolutePath(),
                "-Djavax.net.ssl.trustStore=" + issuer.trustStore(),
                "-Djavax.net.ssl.trustStorePassword=" + issuer.trustStorePassword(),
                "-Djavax.net.ssl.trustStoreType=PKCS12",
                "-jar", serverJar.toString(),
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--dataprism.transport.mode=http",
                "--dataprism.transport.http.path=/mcp",
                "--dataprism.security.jwt.issuer=" + TokenIssuer.ISSUER,
                "--dataprism.security.jwt.audience=" + TokenIssuer.AUDIENCE,
                "--dataprism.security.jwt.jwk-set-uri=" + issuer.jwksUri(),
                "--dataprism.security.caller-claims.principal=sub",
                "--dataprism.security.caller-claims.roles=roles",
                "--dataprism.security.caller-claims.investigation=case_id",
                // A real deployment supplies a CaseAuthorizer bean and lists its allowed clients; this one trusts the token.
                "--dataprism.security-policy.case-authorisation=token-trusted",
                "--dataprism.security-policy.client-allowlist=unrestricted",
                "--dataprism.security-policy.purposes[0]=" + TokenIssuer.PURPOSE,
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.identity.resolver=pass-through",
                "--dataprism.privacy.profile=DEFAULT",
                "--dataprism.privacy.scope-lifetime=" + SCOPE_LIFETIME.toHours() + "h",
                "--dataprism.privacy.hmac-key.key-id=loadtest-v1",
                "--dataprism.privacy.hmac-key.environment-variable=" + HMAC_ENV,
                "--dataprism.audit.sink=slf4j",
                "--dataprism.audit.writer-id=load-test",
                "--dataprism.audit.entity-types[0]=" + SourceGenerator.ENTITY_TYPE,
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node"));
        command.addAll(loggingArguments(log));
        scenario.limits().asProperties().forEach((key, value) -> command.add("--" + key + "=" + value));

        // The JVM's own stdout and stderr (an uncaught exception, an OutOfMemoryError) go to a file of their own:
        // the server's logger owns and rolls server.log, and must never share a path with this redirect.
        Path console = consoleFileOf(log);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(console.toFile()));
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        builder.environment().put(HMAC_ENV, Base64.getEncoder().encodeToString(key));
        // A closer is tracked before the launch, so a signal in the gap between start() and tracking the
        // server still stops the child: whichever of the two happens second kills the process.
        LaunchGuard guard = new LaunchGuard();
        cleanup.track(guard);
        Process process = null;
        try {
            process = builder.start();
        } finally {
            if (process != null) {
                guard.launched(process);
            } else {
                guard.failed();
            }
        }
        ServerProcess server = new ServerProcess(process, port, log, console, resultsDir);
        cleanup.track(server);
        try {
            server.awaitHealthy(Duration.ofSeconds(180));
        } catch (IOException | InterruptedException | RuntimeException failed) {
            Path kept = server.keepLog();
            server.close();
            if (failed instanceof IOException io && kept != null) {
                throw new IOException(io.getMessage().replace(server.logFile.toString(), kept.toString()), io);
            }
            throw failed;
        }
        return server;
    }

    /**
     * Kills a child process whether cleanup runs before, during or after its launch. A guard starts out
     * "launching"; {@link #close()} waits (bounded) for the launch to settle so the shutdown hook cannot let
     * the JVM halt while a child is about to exist.
     */
    static final class LaunchGuard implements AutoCloseable {
        private final long boundNanos;
        private Process process;
        private boolean launching = true;
        private boolean closed;

        LaunchGuard() {
            this(Duration.ofSeconds(10));
        }

        LaunchGuard(Duration bound) {
            this.boundNanos = bound.toNanos();
        }

        synchronized void launched(Process launched) {
            process = launched;
            launching = false;
            notifyAll();
            if (closed) {
                launched.destroyForcibly();
            }
        }

        synchronized void failed() {
            launching = false;
            notifyAll();
        }

        @Override
        public synchronized void close() {
            closed = true;
            long remaining = boundNanos;
            long deadline = System.nanoTime() + remaining;
            while (launching && remaining > 0) {
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                remaining = deadline - System.nanoTime();
            }
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    String mcpBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    Path logFile() {
        return logFile;
    }

    /**
     * The server's own log, capped so a 24 h soak cannot fill the disk: a rolling file of at most 10 MB per file,
     * 2 rolled files and 30 MB in all, so the newest output (the tail) is what survives. Its console output is
     * switched off ({@code logging.threshold.console=OFF}; an empty console pattern makes Logback report a status
     * error), so the console file holds only what the JVM itself prints, such as a crash.
     */
    static List<String> loggingArguments(Path log) {
        return List.of(
                "--logging.file.name=" + log,
                "--logging.threshold.console=OFF",
                "--logging.logback.rollingpolicy.max-file-size=10MB",
                "--logging.logback.rollingpolicy.max-history=2",
                "--logging.logback.rollingpolicy.total-size-cap=30MB");
    }

    /** Where the child's stdout and stderr go: beside the server log, never the same file. */
    static Path consoleFileOf(Path log) {
        return log.resolveSibling("server-console.log");
    }

    /** The last {@code lines} lines of the child's stdout and stderr, or a note when there are none. */
    String consoleTail(int lines) {
        try {
            List<String> all = Files.readAllLines(consoleFile);
            return all.isEmpty() ? "(console empty)"
                    : String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException | RuntimeException unreadable) {
            return "(console unreadable)";
        }
    }

    /** The live log and the files rolled from it ({@code server.log.*}). */
    static List<Path> logFilesOf(Path log) {
        try (Stream<Path> siblings = Files.list(log.toAbsolutePath().getParent())) {
            String name = log.getFileName().toString();
            return siblings.filter(p -> p.getFileName().toString().equals(name)
                    || p.getFileName().toString().startsWith(name + ".")).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static ServerProcess forTest(Process process, Path logFile, Path resultsDir) {
        return new ServerProcess(process, 0, logFile, consoleFileOf(logFile), resultsDir);
    }

    /** The exit code if the server has exited, otherwise {@code null}. */
    Integer exitCode() {
        return process.isAlive() ? null : process.exitValue();
    }

    /**
     * Copies the server log (live and rolled files) out of the temp dir (deleted on exit) to the git-ignored
     * results dir. Returns the copy of the live log, or null if there is none.
     */
    Path keepLog() {
        try {
            Files.createDirectories(resultsDir);
            String stamp = String.valueOf(System.currentTimeMillis());
            Path kept = null;
            for (Path file : logFilesOf(logFile)) {
                String suffix = file.getFileName().toString().substring(logFile.getFileName().toString().length());
                Path copy = Files.copy(file, resultsDir.resolve("server-failure-" + stamp + ".log" + suffix));
                if (suffix.isEmpty()) {
                    kept = copy;
                }
            }
            if (Files.exists(consoleFile)) {
                Files.copy(consoleFile, resultsDir.resolve("server-failure-" + stamp + ".console.log"));
            }
            return kept;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void awaitHealthy(Duration timeout) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest health = HttpRequest.newBuilder(URI.create(mcpBaseUrl() + "/health"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IOException("server exited with " + process.exitValue() + " before it was healthy; see "
                        + logFile + "\n" + tail(40));
            }
            try {
                // A 200 counts only from our own child: the port was free when chosen, but another process
                // could have taken it, so the child must be alive and its log must say it bound this port.
                if (client.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
                        && process.isAlive() && logShowsPort(logFile, port)) {
                    return;
                }
            } catch (IOException notYet) {
                // Not listening yet.
            }
            Thread.sleep(250);
        }
        throw new IOException("server not healthy within " + timeout + "; see " + logFile + "\n" + tail(40));
    }

    private static final Pattern STARTED = Pattern.compile("started on ports?\\s+([^\\n]*)");
    private static final Pattern CONNECTOR_PORT = Pattern.compile("(\\d+)\\s*\\(");

    /**
     * Whether the server log says the web server started on {@code port}. Spring Boot lists every connector on
     * one line ({@code started on ports 8443 (https), 41234 (http)}), so a server with more than one connector
     * is matched on any of them.
     */
    static boolean logShowsPort(Path log, int port) {
        try {
            Matcher started = STARTED.matcher(Files.readString(log));
            while (started.find()) {
                Matcher connector = CONNECTOR_PORT.matcher(started.group(1));
                while (connector.find()) {
                    if (connector.group(1).equals(String.valueOf(port))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private String tail(int lines) {
        String log;
        try {
            List<String> all = Files.readAllLines(logFile);
            log = String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException | RuntimeException unreadable) {
            log = "(log unreadable)";
        }
        return log + "\n--- console (stdout and stderr) ---\n" + consoleTail(lines);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path single(Path dir, String glob) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException(dir + " does not exist; build the server first (run.sh does)");
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> matches = files
                    .filter(p -> p.getFileName().toString().matches(glob.replace(".", "\\.").replace("*", ".*")))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar")
                            && !p.getFileName().toString().endsWith("-javadoc.jar")
                            && !p.getFileName().toString().endsWith("-tests.jar"))
                    .toList();
            if (matches.size() != 1) {
                throw new IOException("expected exactly one " + glob + " in " + dir + ", found " + matches);
            }
            return matches.get(0).toAbsolutePath();
        }
    }
}
