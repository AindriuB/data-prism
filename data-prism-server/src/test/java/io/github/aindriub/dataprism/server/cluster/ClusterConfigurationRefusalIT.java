package io.github.aindriub.dataprism.server.cluster;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.server.operator.FixtureCustomer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Task 134: misconfigured clustering refuses startup through the packaged server's failure
 * analyzer, in the pattern of {@code ConfigurationRefusalMessageIT}. Each case is otherwise a
 * complete deployment, so the cluster refusal is the first one reached.
 */
class ClusterConfigurationRefusalIT {

    private static final String VERSION = requireVersion();

    @TempDir
    Path tempDir;

    private static String requireVersion() {
        String v = System.getProperty("project.version");
        if (v == null || v.isBlank() || v.contains("${")) {
            throw new IllegalStateException("System property project.version was not passed by the build");
        }
        return v;
    }

    @Test
    void embeddedWithNoClusterNameIsRefused() throws Exception {
        assertRefused("MISSING_CLUSTER_NAME", "--dataprism.hazelcast.join.mode=none");
    }

    @Test
    void embeddedWithTheDefaultClusterNameIsRefused() throws Exception {
        assertRefused("RESERVED_CLUSTER_NAME", "--dataprism.hazelcast.cluster-name=dev",
                "--dataprism.hazelcast.join.mode=none");
    }

    @Test
    void embeddedWithNoJoinModeIsRefused() throws Exception {
        assertRefused("MISSING_CLUSTER_JOIN", "--dataprism.hazelcast.cluster-name=refusal-test-cluster");
    }

    private void assertRefused(String code, String... clusterArguments) throws Exception {
        Path extension = tempDir.resolve("identity-resolver-extension.jar");
        writeIdentityResolverExtension(extension);
        List<String> args = new ArrayList<>(List.of(
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
                "--dataprism.privacy.hmac-key.environment-variable=DATAPRISM_TASK134_TEST_KEY",
                "--dataprism.audit.sink=slf4j", "--dataprism.audit.writer-id=cluster-refusal-test",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s",
                "--dataprism.hazelcast.topology=embedded"));
        args.addAll(List.of(clusterArguments));

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dloader.path=" + extension, "-jar",
                Path.of("target", "data-prism-server-" + VERSION + ".jar").toAbsolutePath().toString()));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("DATAPRISM_TASK134_TEST_KEY",
                "cluster-refusal-test-key-material-longer-than-thirty-two-bytes");
        Process process = builder.start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(exited).as("packaged server did not exit: %s", output).isTrue();
        assertThat(process.exitValue()).as(output).isNotZero();
        assertThat(output).as(output)
                .contains("APPLICATION FAILED TO START")
                .contains("DataPrismConfigurationException: " + code)
                .doesNotContain("\tat io.github.aindriub.dataprism")
                .doesNotContain("\tat org.springframework");
    }

    private static void writeIdentityResolverExtension(Path extension) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(extension))) {
            output.putNextEntry(new ZipEntry(
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
            output.write((IdentityResolverExtension.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (Class<?> type : List.of(IdentityResolverExtension.class, FixtureCustomer.class,
                    FixtureAdapter.class)) {
                String resource = type.getName().replace('.', '/') + ".class";
                output.putNextEntry(new ZipEntry(resource));
                try (var input = ClusterConfigurationRefusalIT.class.getClassLoader().getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IOException("missing test extension class " + resource);
                    }
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
    }

    /**
     * A source adapter, so the shared budget bean exists. Without one the budget is never created
     * and the build-time preflight reports {@code MISSING_SHARED_BUDGET} before the cluster
     * settings are validated.
     */
    public static class FixtureAdapter implements DataSourceAdapter<FixtureCustomer> {
        @Override public String sourceName() { return "customer"; }
        @Override public Class<FixtureCustomer> responseType() { return FixtureCustomer.class; }
        @Override public FixtureCustomer fetch(DataRequest request) {
            return new FixtureCustomer(request.subjectId(), "Fixture Person", "ACTIVE");
        }
    }

    @AutoConfiguration
    public static class IdentityResolverExtension {
        @Bean
        IdentityResolver identityResolver() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        FixtureAdapter fixtureAdapter() {
            return new FixtureAdapter();
        }
    }
}
