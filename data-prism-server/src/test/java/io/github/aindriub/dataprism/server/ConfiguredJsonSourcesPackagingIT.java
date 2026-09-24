package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the packaged {@code data-prism-server} jar accepts task 20's
 * configuration-driven JSON REST mode the same way it accepts any other
 * reviewed adapter: as a plain {@code -Dloader.path} extension, never as
 * something baked into the base distribution.
 *
 * <p>This runs the real {@code data-prism-connectors-rest} jar this reactor
 * build produces, not a synthetic stand-in — {@link
 * ServerPackagingIT#executableLoadsAReviewedAdapterExtensionFromLoaderPath}
 * proves the loader-path mechanism itself with a minimal test class; this
 * proves the specific extension this task built actually loads and starts
 * through it, and that an invalid catalogue at the configured location
 * refuses startup with a stable code, matching {@code
 * ConfiguredJsonSourcesTest} and {@code ConfiguredJsonSourcesAutoConfigurationTest}
 * in {@code data-prism-connectors-rest} rather than repeating their coverage.
 */
class ConfiguredJsonSourcesPackagingIT {

    private final List<Path> tempFiles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tempFiles.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best effort: a leftover temp file here is not a test failure.
            }
        });
    }

    /**
     * Runs as a genuine servlet web application ({@code server.port=0}), not {@code
     * web-application-type=none}, so the new preflight does not refuse it. Once Tomcat
     * logs its bound port — reachable only after every singleton, including the
     * adapter-dependent {@code DataPrismContractValidator}, constructs without error —
     * makes a real {@code GET /health} and asserts {@code 200}: proof {@code
     * ConfiguredJsonSourcesAutoConfiguration}/{@code ConfiguredJsonSourcesInitializer}
     * actually parsed the catalogue and registered the {@code packaging-test-api}
     * adapter. Unlike {@link ServerPackagingIT}'s marker, this module doesn't own the
     * adapter's construction code, so it can't print one of its own.
     */
    @Test
    void packagedServerStartsWithAConfiguredJsonSourceExtensionOnTheLoaderPath() throws Exception {
        Path jsonSources = validJsonSourcesFile();
        assertServerStartsAndServesHealth(jsonSources);
    }

    @Test
    void packagedServerRefusesAnInvalidJsonSourcesCatalogueAtTheConfiguredLocation() throws Exception {
        Path jsonSources = Files.createTempFile("task20-invalid-json-sources-", ".yaml");
        tempFiles.add(jsonSources);
        Files.writeString(jsonSources, "json-sources:\n  broken-api:\n    base-url: http://example.invalid\n");

        ProcessResult result = run(jsonSources);

        assertThat(result.exited()).as("packaged server did not complete: %s", result.output()).isTrue();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).as(result.output())
                .containsAnyOf("has no path", "has no timeout", "has no model-version");
    }

    private ProcessResult run(Path jsonSources) throws Exception {
        ProcessBuilder builder = buildProcess(jsonSources);
        Process process = builder.start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(exited, exited ? process.exitValue() : -1, output);
    }

    /**
     * Unlike {@link #run(Path)}, does not wait for the process to exit — a successful
     * servlet start keeps running. Waits for Tomcat's {@code Tomcat started on port ...}
     * line, parses the port, and asserts {@code GET /health} returns {@code 200}. Always
     * destroys the process before returning.
     */
    private void assertServerStartsAndServesHealth(Path jsonSources) throws Exception {
        ProcessBuilder builder = buildProcess(jsonSources);
        Process process = builder.start();
        try {
            String output = awaitOutputMatching(process, TOMCAT_STARTED_PORT, Duration.ofSeconds(20));
            Matcher matcher = TOMCAT_STARTED_PORT.matcher(output);
            assertThat(matcher.find())
                    .as("packaged server never logged a bound Tomcat port, so it never finished starting "
                            + "(the configured JSON source adapter may never have been registered): %s", output)
                    .isTrue();
            int port = Integer.parseInt(matcher.group(1));

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofSeconds(5))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).as("packaged server output: %s", output).isEqualTo(200);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static final Pattern TOMCAT_STARTED_PORT = Pattern.compile("Tomcat started on port (\\d+)");

    /**
     * Starts a daemon thread that drains {@code process}'s (merged) output into
     * a shared buffer, then polls that buffer against {@code pattern} until it
     * matches, {@code process} exits, or {@code timeout} elapses — whichever
     * comes first. Returns whatever was captured, for a diagnosable failure
     * message either way.
     */
    private static String awaitOutputMatching(Process process, Pattern pattern, Duration timeout)
            throws InterruptedException {
        StringBuilder captured = new StringBuilder();
        Thread pump = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (captured) {
                        captured.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // The process's output stream closed; nothing further to read.
            }
        }, "packaging-it-output-pump");
        pump.setDaemon(true);
        pump.start();

        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            synchronized (captured) {
                if (pattern.matcher(captured).find()) {
                    return captured.toString();
                }
            }
            if (!process.isAlive()) {
                break;
            }
            Thread.sleep(50);
        }
        // Give the pump a brief moment to flush any trailing output after exit.
        pump.join(Duration.ofSeconds(2).toMillis());
        synchronized (captured) {
            return captured.toString();
        }
    }

    private ProcessBuilder buildProcess(Path jsonSources) throws IOException {
        Path connectorsRestJar = connectorsRestJarPath();
        Path identityResolverExtension = Files.createTempFile("task20-identity-resolver-extension-", ".jar");
        tempFiles.add(identityResolverExtension);
        writeIdentityResolverExtension(identityResolverExtension);

        Path serverArtifact = Path.of("target", "data-prism-server-0.3.1.jar").toAbsolutePath();

        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-Dloader.path=" + connectorsRestJar + "," + identityResolverExtension);
        command.add("-Ddataprism.json-sources.config-location=file:" + jsonSources.toAbsolutePath());
        command.add("-jar");
        command.add(serverArtifact.toString());
        command.addAll(List.of(validArguments()));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("DATAPRISM_TASK17_TEST_KEY",
                "packaged-server-test-key-material-longer-than-thirty-two-bytes");
        builder.redirectErrorStream(true);
        return builder;
    }

    /**
     * A minimal, self-contained catalogue: base URL and path are never dialled
     * in this test, only parsed and wired, so an unreachable host is fine.
     * {@link #validArguments()} deliberately states no {@code
     * dataprism.sources.packaging-test-api} entry at all — this catalogue is
     * this source's only statement of its transport, proving the packaged
     * server starts and serves traffic without the duplicate task 54 removed
     * the requirement for.
     */
    private Path validJsonSourcesFile() throws IOException {
        Path file = Files.createTempFile("task20-valid-json-sources-", ".yaml");
        tempFiles.add(file);
        Files.writeString(file, """
                json-sources:
                  packaging-test-api:
                    base-url: https://packaging-test.example
                    path: /customers/{subject}
                    timeout: PT2S
                    model-version: customer-v1
                    subject-json-path: id
                    fields:
                      id:
                        identifier: true
                      name:
                        classifications: [PII]
                        namespace: PERSON_NAME
                        action: SYNTHESIZE
                """);
        return file;
    }

    /**
     * {@code dataprism.sources} is the Java-first vocabulary and this feature
     * deliberately never reads it (see docs/configuration.md, "Java-first now;
     * generic JSON later"). Deliberately no {@code
     * dataprism.sources.packaging-test-api} entry appears here: the base
     * distribution's contract validator no longer requires one for a {@code
     * DataSourceAdapter} bean this mechanism supplies (task 54), so this test
     * proves the packaged server starts and serves {@code /health} with {@code
     * packaging-test-api}'s transport stated exactly once, in the catalogue
     * {@link #validJsonSourcesFile()} writes.
     *
     * <p>{@code server.port=0} (an ephemeral port), not {@code
     * spring.main.web-application-type=none}: task 39 refuses the latter at
     * this class's default {@code dataprism.transport.mode=HTTP}, so a servlet
     * web application is what every test using these arguments now exercises.
     */
    private static String[] validArguments() {
        return new String[] {
                "--server.port=0", "--spring.main.banner-mode=off",
                "--dataprism.security.jwt.issuer=https://issuer.example",
                "--dataprism.security.jwt.audience=mcp",
                "--dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "--dataprism.security.caller-claims.principal=sub",
                "--dataprism.security.caller-claims.roles=roles",
                "--dataprism.security.caller-claims.investigation=case_id",
                "--dataprism.security-policy.purposes[0]=investigation",
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.privacy.profile=DEFAULT", "--dataprism.privacy.scope-lifetime=8h",
                "--dataprism.privacy.hmac-key.key-id=v1",
                "--dataprism.privacy.hmac-key.environment-variable=DATAPRISM_TASK17_TEST_KEY",
                "--dataprism.audit.sink=slf4j", "--dataprism.audit.writer-id=packaging-test",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node"
        };
    }

    private static Path connectorsRestJarPath() {
        Path jar = Path.of("..", "data-prism-connectors-rest", "target",
                "data-prism-connectors-rest-0.3.1.jar").toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(jar))
                .as("data-prism-connectors-rest jar not found at %s; the reactor build must produce it "
                        + "before data-prism-server's integration tests run", jar)
                .isTrue();
        return jar;
    }

    private static void writeIdentityResolverExtension(Path extension) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(extension))) {
            output.putNextEntry(new ZipEntry(
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
            output.write((IdentityResolverExtension.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            addClass(output, IdentityResolverExtension.class);
        }
    }

    private static void addClass(JarOutputStream output, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        output.putNextEntry(new ZipEntry(resource));
        try (var input = ConfiguredJsonSourcesPackagingIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing test extension class " + resource);
            }
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @AutoConfiguration
    public static class IdentityResolverExtension {
        @Bean
        IdentityResolver identityResolver() {
            return new PassThroughIdentityResolver();
        }
    }

    private record ProcessResult(boolean exited, int exitCode, String output) { }
}
