package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
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
 * Proves task 57's operator-facing presentation against the packaged distribution itself,
 * not a hand-built exception: {@code docker run ghcr.io/aindriub/data-prism-server:0.3.1} with
 * no configuration must print a block naming the stable code, what to supply, {@code
 * docs/configuration.md} and the runnable demo — and no Java stack frame — while still exiting
 * non-zero. This is the same failure {@code .github/workflows/publish-image.yml}'s "Verify the
 * image refuses to start with no configuration" step gates the image push on; that step's own
 * {@code grep -oE 'DataPrismConfigurationException: MISSING_[A-Z_]+'} still matches the block
 * this analyzer prints (see {@code DataPrismConfigurationFailureAnalyzer}), so this test and
 * that workflow step are proven against the same literal text, not two different contracts.
 */
class ConfigurationRefusalMessageIT {

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
     * The exact contract the distribution image's first-contact failure gives an operator
     * running it with zero configuration: {@code MISSING_IDENTITY_RESOLVER}, from the {@code
     * BeanFactoryPostProcessor} preflight, fires before any other check gets a chance.
     */
    @Test
    void packagedServerWithNoConfigurationPrintsAnOperatorFacingBlockAndNoStackFrame() throws Exception {
        ProcessResult result = runPackagedServer(List.of());

        assertThat(result.exited()).as("packaged server did not exit: %s", result.output()).isTrue();
        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).as(result.output())
                .contains("APPLICATION FAILED TO START")
                .contains("DataPrismConfigurationException: MISSING_IDENTITY_RESOLVER")
                .contains("docs/configuration.md")
                .contains("docs/quickstart.md")
                .doesNotContain("\tat io.github.aindriub.dataprism")
                .doesNotContain("\tat org.springframework");
    }

    /**
     * The contract validator family, {@link io.github.aindriub.dataprism.spring.boot
     * .DataPrismContractValidator}, driven by the real packaged process with a real {@link
     * IdentityResolver} supplied and no {@code DataSourceAdapter} and no {@code
     * dataprism.sources} entry — the shape {@code MISSING_SOURCE_ADAPTER} actually refuses,
     * not a hand-built exception passed to the analyzer.
     */
    @Test
    void packagedServerRefusesWithNoSourceAdapterConfigured() throws Exception {
        Path identityExtension = Files.createTempFile("task57-identity-resolver-extension-", ".jar");
        tempFiles.add(identityExtension);
        writeIdentityResolverExtension(identityExtension);

        // The same fully-configured deployment ServerPackagingIT's reviewed-extension tests use,
        // minus any dataprism.sources entry and minus a DataSourceAdapter bean: everything else
        // this contract requires is present, so DataPrismProperties.validate() and every other
        // refusal pass, and DataPrismContractValidator.validateIntegrations() is what's left to
        // refuse with MISSING_SOURCE_ADAPTER.
        List<String> args = List.of(
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
                "--dataprism.privacy.hmac-key.environment-variable=DATAPRISM_TASK57_TEST_KEY",
                "--dataprism.audit.sink=slf4j", "--dataprism.audit.writer-id=configuration-refusal-test",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node");

        ProcessResult result = runPackagedServer(List.of("-Dloader.path=" + identityExtension), args);

        assertThat(result.exited()).as("packaged server did not exit: %s", result.output()).isTrue();
        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).as(result.output())
                .contains("APPLICATION FAILED TO START")
                .contains("DataPrismConfigurationException: MISSING_SOURCE_ADAPTER")
                .contains("docs/configuration.md")
                .contains("docs/quickstart.md")
                .doesNotContain("\tat io.github.aindriub.dataprism")
                .doesNotContain("\tat org.springframework");
    }

    private static ProcessResult runPackagedServer(List<String> extraJvmArgs) throws Exception {
        return runPackagedServer(extraJvmArgs, List.of("--server.port=0", "--spring.main.banner-mode=off"));
    }

    private static ProcessResult runPackagedServer(List<String> extraJvmArgs, List<String> programArgs)
            throws Exception {
        Path artifact = Path.of("target", "data-prism-server-0.3.1.jar").toAbsolutePath();
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.addAll(extraJvmArgs);
        command.add("-jar");
        command.add(artifact.toString());
        command.addAll(programArgs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("DATAPRISM_TASK57_TEST_KEY",
                "configuration-refusal-test-key-material-longer-than-thirty-two-bytes");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(exited, exited ? process.exitValue() : -1, output);
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
        try (var input = ConfigurationRefusalMessageIT.class.getClassLoader().getResourceAsStream(resource)) {
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
