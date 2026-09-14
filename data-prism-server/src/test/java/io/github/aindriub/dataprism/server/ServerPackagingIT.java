package io.github.aindriub.dataprism.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

class ServerPackagingIT {
    @Test
    void executableUsesPropertiesLauncherAndContainsNoFixtureRuntime() throws IOException {
        Path artifact = Path.of("target", "data-prism-server-0.1.0-SNAPSHOT.jar");
        assertThat(Files.isRegularFile(artifact)).isTrue();

        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                    .isEqualTo("org.springframework.boot.loader.launch.PropertiesLauncher");
            assertThat(jar.getManifest().getMainAttributes().getValue("Start-Class"))
                    .isEqualTo("io.github.aindriub.dataprism.server.DataPrismServerApplication");

            var names = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(names).noneMatch(name -> name.contains("data-prism-example")
                    || (name.startsWith("BOOT-INF/classes/") && name.contains("/Stub"))
                    || name.contains("ExampleApplication"));

            for (var entry : java.util.Collections.list(jar.entries())) {
                if (!entry.getName().startsWith("BOOT-INF/classes/") || entry.isDirectory()) continue;
                String content = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                assertThat(content).doesNotContain("test-only-key").doesNotContain("DEV_KEY");
            }
        }
    }

    @Test
    void executableLoadsAReviewedAdapterExtensionFromLoaderPath() throws Exception {
        Path artifact = Path.of("target", "data-prism-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        Path extension = Files.createTempFile("data-prism-reviewed-extension-", ".jar");
        try {
            writeExtension(extension, ReviewedExtension.class);
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(javaCommand());
            command.add("-Dloader.path=" + extension);
            command.add("-jar");
            command.add(artifact.toString());
            command.addAll(java.util.List.of(validArguments()));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("DATAPRISM_TASK17_TEST_KEY",
                    "packaged-server-test-key-material-longer-than-thirty-two-bytes");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean exited = process.waitFor(20, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(exited).as("packaged server did not complete its non-web startup: %s", output).isTrue();
            assertThat(process.exitValue()).as(output).isZero();
        } finally {
            Files.deleteIfExists(extension);
        }
    }

    @Test
    void executableRefusesAnExtensionWithoutAnIdentityResolver() throws Exception {
        Path artifact = Path.of("target", "data-prism-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        Path extension = Files.createTempFile("data-prism-adapter-only-extension-", ".jar");
        try {
            writeExtension(extension, AdapterOnlyExtension.class);
            ProcessResult result = runPackagedServer(artifact, extension);

            assertThat(result.exited()).as("packaged server did not refuse startup: %s", result.output()).isTrue();
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.output()).contains("MISSING_IDENTITY_RESOLVER");
        } finally {
            Files.deleteIfExists(extension);
        }
    }

    private static ProcessResult runPackagedServer(Path artifact, Path extension) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaCommand());
        command.add("-Dloader.path=" + extension);
        command.add("-jar");
        command.add(artifact.toString());
        command.addAll(java.util.List.of(validArguments()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("DATAPRISM_TASK17_TEST_KEY",
                "packaged-server-test-key-material-longer-than-thirty-two-bytes");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(exited, exited ? process.exitValue() : -1, output);
    }

    private static void writeExtension(Path extension, Class<?> configuration) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(extension))) {
            output.putNextEntry(new ZipEntry(
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
            output.write((configuration.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            addClass(output, configuration);
            addClass(output, configuration.getName() + "$1");
        }
    }

    private static void addClass(JarOutputStream output, Class<?> type) throws IOException {
        addClass(output, type.getName());
    }

    private static void addClass(JarOutputStream output, String className) throws IOException {
        String resource = className.replace('.', '/') + ".class";
        output.putNextEntry(new ZipEntry(resource));
        try (var input = ServerPackagingIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing test extension class " + resource);
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

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
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s"
        };
    }

    @AutoConfiguration
    public static class ReviewedExtension {
        @Bean
        IdentityResolver identityResolver() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(DataRequest request) { return null; }
            };
        }
    }

    @AutoConfiguration
    public static class AdapterOnlyExtension {
        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(DataRequest request) { return null; }
            };
        }
    }

    private record ProcessResult(boolean exited, int exitCode, String output) { }
}
