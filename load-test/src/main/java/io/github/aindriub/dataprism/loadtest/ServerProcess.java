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
import java.util.stream.Stream;

/**
 * The real {@code data-prism-server} jar, started as a child process with the configured-JSON-sources
 * extension on its loader path, authentication on, and the scenario's {@code dataprism.limits.*}.
 *
 * <p>The HMAC key is random per run and reaches the server only through the child's environment.
 */
final class ServerProcess implements AutoCloseable {

    static final String HMAC_ENV = "DATAPRISM_LOADTEST_HMAC_KEY";

    private final Process process;
    private final int port;
    private final Path logFile;

    private final Path resultsDir;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ServerProcess(Process process, int port, Path logFile, Path resultsDir) {
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
                "--dataprism.security-policy.purposes[0]=" + TokenIssuer.PURPOSE,
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.identity.resolver=pass-through",
                "--dataprism.privacy.profile=DEFAULT",
                "--dataprism.privacy.scope-lifetime=8h",
                "--dataprism.privacy.hmac-key.key-id=loadtest-v1",
                "--dataprism.privacy.hmac-key.environment-variable=" + HMAC_ENV,
                "--dataprism.audit.sink=slf4j",
                "--dataprism.audit.writer-id=load-test",
                "--dataprism.audit.entity-types[0]=" + SourceGenerator.ENTITY_TYPE,
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node"));
        scenario.limits().asProperties().forEach((key, value) -> command.add("--" + key + "=" + value));

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
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
        ServerProcess server = new ServerProcess(process, port, log, resultsDir);
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

    /** Copies the server log out of the temp dir (deleted on exit) to the git-ignored results dir. */
    private Path keepLog() {
        try {
            Files.createDirectories(resultsDir);
            return Files.copy(logFile, resultsDir.resolve("server-failure-" + System.currentTimeMillis() + ".log"));
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
                if (client.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // Not listening yet.
            }
            Thread.sleep(250);
        }
        throw new IOException("server not healthy within " + timeout + "; see " + logFile + "\n" + tail(40));
    }

    private String tail(int lines) throws IOException {
        List<String> all = Files.readAllLines(logFile);
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
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
