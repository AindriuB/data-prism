package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
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

    @Test
    void packagedServerStartsWithAConfiguredJsonSourceExtensionOnTheLoaderPath() throws Exception {
        Path jsonSources = validJsonSourcesFile();
        ProcessResult result = run(jsonSources);

        assertThat(result.exited()).as("packaged server did not complete its non-web startup: %s", result.output())
                .isTrue();
        assertThat(result.exitCode()).as(result.output()).isZero();
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
        Path connectorsRestJar = connectorsRestJarPath();
        Path identityResolverExtension = Files.createTempFile("task20-identity-resolver-extension-", ".jar");
        tempFiles.add(identityResolverExtension);
        writeIdentityResolverExtension(identityResolverExtension);

        Path serverArtifact = Path.of("target", "data-prism-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();

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
        Process process = builder.start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(exited, exited ? process.exitValue() : -1, output);
    }

    /**
     * A minimal, self-contained catalogue: base URL and path are never dialled
     * in this test, only parsed and wired, so an unreachable host is fine. The
     * base URL here must be identical to {@code
     * dataprism.sources.packaging-test-api.base-url} in {@link #validArguments()}
     * — {@link ConfiguredJsonSourcesInitializer} refuses startup if the two
     * disagree, and it must, since a mismatch between them is exactly the
     * hazard of stating one source's transport twice.
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
     * generic JSON later"), but the base distribution's own contract validator
     * still requires every {@code DataSourceAdapter} bean's name to appear
     * there too, regardless of which mechanism supplied the adapter. Naming the
     * configured source here as well is what satisfies that check for this
     * test; it states no transport details {@code
     * ConfiguredJsonSourcesAutoConfiguration} does not already own, since the
     * base validator does not read past the key's presence for a name it
     * cannot otherwise resolve.
     */
    private static String[] validArguments() {
        return new String[] {
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
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
                "--dataprism.hazelcast.topology=single-node",
                "--dataprism.sources.packaging-test-api.base-url=https://packaging-test.example",
                "--dataprism.sources.packaging-test-api.timeout=2s"
        };
    }

    private static Path connectorsRestJarPath() {
        Path jar = Path.of("..", "data-prism-connectors-rest", "target",
                "data-prism-connectors-rest-0.1.0-SNAPSHOT.jar").toAbsolutePath().normalize();
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
