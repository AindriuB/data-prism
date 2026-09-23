package io.github.aindriub.dataprism.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ServerPackagingIT {

    /**
     * Literal development key material this scan looks for, each documented with the
     * build artefact that actually emits it. A marker that no build artefact in this
     * repository ever emits does not belong here — it would let the scan pass by
     * construction (see {@code docs/conventions.md} on cannot-fail assertions).
     *
     * <p>This list currently holds exactly one literal. The scan matches on that literal
     * string alone: a future development key with different key material — a new fixture
     * assembly, a rotated literal in {@code DataPrismAssembly}, a key added to some other
     * example module — would produce no hit and no failure unless its literal is added
     * here too. Whoever adds a new development key to this repository must extend this
     * list, or this scan silently stops covering it.
     */
    private static final List<String> DEVELOPMENT_KEY_MARKERS = List.of(
            // DataPrismAssembly.DEV_KEY, data-prism-integration-tests/src/main/java/io/github/aindriub/
            // dataprism/example/DataPrismAssembly.java:55 — the fixture-only stdio assembly's
            // hardcoded development HMAC key material. data-prism-server does not depend on
            // data-prism-integration-tests today, so this marker should never appear in the packaged
            // jar; it exists so an accidental future dependency, or a copy of the key into
            // this module, is caught here rather than relying on the dependency graph never
            // changing.
            //
            // The quickstart Compose environment's HMAC key
            // (quickstart-demo-hmac-key-not-a-real-secret-32-bytes-long, task 18) is
            // deliberately NOT listed here. Task 20 added it once, on the reasoning that
            // whoever adds a development key must extend this list; a review of that change
            // found the addition itself violated this class's own opening rule two
            // paragraphs up: data-prism-server does not read the quickstart env file and
            // never compiles it in, by construction, so no build artefact this scan
            // inspects can ever emit that literal, and a marker that can never be found
            // is exactly the cannot-fail shape docs/conventions.md warns about, not a
            // second layer of protection. The obligation to extend this list applies to a
            // development key that can reach *this* artefact; the quickstart key's own
            // equivalent check, if one is warranted, belongs beside whatever inspects the
            // quickstart/Compose artefacts themselves, not here.
            "development-only-key-not-for-any-real-data"
    );

    /**
     * {@code StaticSecretKeyProvider} shipping inside {@code BOOT-INF/lib/} is expected
     * and is not itself a marker: the class is the key-provider mechanism task 17 requires
     * every environment, including this one, to be able to use. What must never ship is
     * development key material — the literal secret a fixture assembly hardcodes — so this
     * scans jar contents for {@link #DEVELOPMENT_KEY_MARKERS}, never for the provider's
     * class name.
     */
    @Test
    void executableUsesPropertiesLauncherAndContainsNoFixtureRuntime() throws IOException {
        Path artifact = Path.of("target", "data-prism-server-0.3.0.jar");
        assertThat(Files.isRegularFile(artifact)).isTrue();

        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                    .isEqualTo("org.springframework.boot.loader.launch.PropertiesLauncher");
            assertThat(jar.getManifest().getMainAttributes().getValue("Start-Class"))
                    .isEqualTo("io.github.aindriub.dataprism.server.DataPrismServerApplication");

            var names = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(names).noneMatch(name -> name.contains("data-prism-integration-tests")
                    || (name.startsWith("BOOT-INF/classes/") && name.contains("/Stub"))
                    || name.contains("ExampleApplication"));
        }

        List<String> hits = scanForDevelopmentKeyMaterial(artifact, DEVELOPMENT_KEY_MARKERS);
        assertThat(hits)
                .as("packaged server jar %s ships development key material: %s", artifact, hits)
                .isEmpty();
    }

    /**
     * Positive control for {@link #executableUsesPropertiesLauncherAndContainsNoFixtureRuntime}:
     * plants a development key marker inside an entry of a nested {@code BOOT-INF/lib/*.jar},
     * the exact shape a real leak would take, and asserts the same scanner function used above
     * reports it. If the scan's body were deleted (or its result hardcoded to empty), this test
     * fails — the main test alone cannot show that.
     */
    @Test
    void developmentKeyScanDetectsAPlantedMarkerInsideANestedLibraryJar() throws IOException {
        String marker = DEVELOPMENT_KEY_MARKERS.get(0);
        Path planted = buildJarWithPlantedMarkerInNestedLibrary(marker);
        try {
            List<String> hits = scanForDevelopmentKeyMaterial(planted, DEVELOPMENT_KEY_MARKERS);

            assertThat(hits)
                    .as("scanner must detect a development key marker planted inside a nested "
                            + "BOOT-INF/lib/*.jar entry, or it cannot detect a real leak there either")
                    .isNotEmpty();
            assertThat(hits.get(0)).contains(marker).contains("BOOT-INF/lib/");
        } finally {
            Files.deleteIfExists(planted);
        }
    }

    /**
     * Regression control for the exact defect a review found in this scan: {@code
     * JarInputStream} reads a nested jar's leading {@code META-INF/MANIFEST.MF} to
     * populate {@code getManifest()} and never returns it from {@code getNextEntry()},
     * so a marker planted specifically in a nested jar's manifest — rather than in an
     * ordinary resource entry, as {@link #developmentKeyScanDetectsAPlantedMarkerInsideANestedLibraryJar}
     * plants it — went undetected while the scan used {@code JarInputStream}. Measured
     * against the real packaged {@code data-prism-server} artifact, that bug left 3 of
     * 65 nested {@code META-INF/MANIFEST.MF} entries visible to the scan; with {@code
     * ZipInputStream} all 65 are visible. This test fails if the scan regresses to a
     * manifest-consuming reader.
     */
    @Test
    void developmentKeyScanDetectsAPlantedMarkerInsideANestedLibraryJarManifest() throws IOException {
        String marker = DEVELOPMENT_KEY_MARKERS.get(0);
        Path planted = buildJarWithPlantedMarkerInNestedLibraryManifest(marker);
        try {
            List<String> hits = scanForDevelopmentKeyMaterial(planted, DEVELOPMENT_KEY_MARKERS);

            assertThat(hits)
                    .as("scanner must detect a development key marker planted inside a nested "
                            + "BOOT-INF/lib/*.jar entry's META-INF/MANIFEST.MF, not just its ordinary "
                            + "resource entries, or a manifest-consuming reader silently narrows the scan")
                    .isNotEmpty();
            assertThat(hits.get(0)).contains(marker).contains("BOOT-INF/lib/").contains("META-INF/MANIFEST.MF");
        } finally {
            Files.deleteIfExists(planted);
        }
    }

    /**
     * Positive control for the non-nested branch of {@link #scanForDevelopmentKeyMaterial}
     * (the {@code else} taken for {@code BOOT-INF/classes/} resources and the outer jar's
     * own {@code META-INF/}, as opposed to the {@code BOOT-INF/lib/*.jar} branch above):
     * plants a development key marker directly in a {@code BOOT-INF/classes/} entry — no nested jar
     * involved — and asserts the scan reports it. Until this test existed, only the nested
     * {@code BOOT-INF/lib/*.jar} branch had a positive control; the non-nested branch could
     * have its body deleted with nothing in this suite noticing.
     */
    @Test
    void developmentKeyScanDetectsAPlantedMarkerInBootInfClasses() throws IOException {
        String marker = DEVELOPMENT_KEY_MARKERS.get(0);
        Path planted = buildJarWithPlantedMarkerInBootInfClasses(marker);
        try {
            List<String> hits = scanForDevelopmentKeyMaterial(planted, DEVELOPMENT_KEY_MARKERS);

            assertThat(hits)
                    .as("scanner must detect a development key marker planted directly in "
                            + "BOOT-INF/classes/, or the non-nested branch cannot detect a real leak there either")
                    .isNotEmpty();
            assertThat(hits.get(0)).contains(marker).contains("BOOT-INF/classes/");
        } finally {
            Files.deleteIfExists(planted);
        }
    }

    /**
     * Runs as a genuine servlet web application ({@code server.port=0}), not {@code
     * web-application-type=none}: at that type {@code dataPrismMcpTransportPreflight}
     * refuses before startup completes. Asserts two things separately, since one does
     * not imply the other: {@link ReviewedExtension#ADAPTER_LOADED_MARKER} (printed only
     * from inside the extension's own {@code customerAdapter()} bean method) proves the
     * loader-path extension's bean was constructed; the {@code Tomcat started on port}
     * line proves startup went on to actually complete.
     */
    @Test
    void executableLoadsAReviewedAdapterExtensionFromLoaderPath() throws Exception {
        Path artifact = Path.of("target", "data-prism-server-0.3.0.jar").toAbsolutePath();
        Path extension = Files.createTempFile("data-prism-reviewed-extension-", ".jar");
        Process process = null;
        try {
            writeExtension(extension, ReviewedExtension.class);
            List<String> command = new ArrayList<>();
            command.add(javaCommand());
            command.add("-Dloader.path=" + extension);
            command.add("-jar");
            command.add(artifact.toString());
            command.addAll(List.of(validArguments()));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("DATAPRISM_TASK17_TEST_KEY",
                    "packaged-server-test-key-material-longer-than-thirty-two-bytes");
            builder.redirectErrorStream(true);
            process = builder.start();

            String output = awaitOutputMatching(process,
                    captured -> captured.contains(ReviewedExtension.ADAPTER_LOADED_MARKER)
                            && TOMCAT_STARTED.matcher(captured).find(),
                    Duration.ofSeconds(20));

            assertThat(output)
                    .as("packaged server never printed %s, so the reviewed extension's DataSourceAdapter bean "
                            + "was never constructed: %s", ReviewedExtension.ADAPTER_LOADED_MARKER, output)
                    .contains(ReviewedExtension.ADAPTER_LOADED_MARKER);
            assertThat(TOMCAT_STARTED.matcher(output).find())
                    .as("packaged server never logged a bound Tomcat port, so it never finished starting: %s",
                            output)
                    .isTrue();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(extension);
        }
    }

    private static final Pattern TOMCAT_STARTED = Pattern.compile("Tomcat started on port \\d+");

    /**
     * Starts a daemon thread that drains {@code process}'s (merged) output into a shared
     * buffer, then polls that buffer against {@code condition} until it is satisfied,
     * {@code process} exits, or {@code timeout} elapses — whichever comes first. Unlike
     * {@code process.waitFor(...)}, this does not require the process to exit on its own:
     * a successfully started servlet web application keeps running. Returns whatever was
     * captured, for a diagnosable failure message either way.
     */
    private static String awaitOutputMatching(Process process, java.util.function.Predicate<String> condition,
            Duration timeout) throws InterruptedException {
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
                if (condition.test(captured.toString())) {
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

    @Test
    void executableRefusesAnExtensionWithoutAnIdentityResolver() throws Exception {
        Path artifact = Path.of("target", "data-prism-server-0.3.0.jar").toAbsolutePath();
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

    /**
     * Scans every entry of {@code jarPath} for the given markers, recursing one level
     * into nested {@code BOOT-INF/lib/*.jar} entries so the search space covers
     * {@code BOOT-INF/classes/}, {@code META-INF/} and packaged library jars alike,
     * rather than one path prefix. Returns a diagnosable hit description — the jar
     * entry name (nested entries as {@code outer.jar!/inner/path}) and the marker
     * found — for every match, so a real hit does not need a debugger to locate.
     */
    private static List<String> scanForDevelopmentKeyMaterial(Path jarPath, List<String> markers)
            throws IOException {
        List<String> hits = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (var entry : java.util.Collections.list(jar.entries())) {
                if (entry.isDirectory()) continue;
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                if (entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar")) {
                    hits.addAll(scanNestedJarForDevelopmentKeyMaterial(entry.getName(), bytes, markers));
                } else {
                    hits.addAll(matchMarkers(entry.getName(), bytes, markers));
                }
            }
        }
        return hits;
    }

    private static List<String> scanNestedJarForDevelopmentKeyMaterial(
            String outerEntryName, byte[] nestedJarBytes, List<String> markers) throws IOException {
        // ZipInputStream, not JarInputStream: JarInputStream reads and consumes a leading
        // META-INF/MANIFEST.MF internally to populate getManifest() and never surfaces it
        // from getNextEntry(), so a marker planted in a nested jar's manifest — the exact
        // shape the reviewed defect took — would silently never reach matchMarkers below.
        // Measured against the real packaged artifact: JarInputStream exposed 3 of 65
        // nested META-INF/MANIFEST.MF entries; ZipInputStream exposes all 65.
        List<String> hits = new ArrayList<>();
        try (ZipInputStream nested = new ZipInputStream(new ByteArrayInputStream(nestedJarBytes))) {
            ZipEntry entry;
            while ((entry = nested.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] bytes = nested.readAllBytes();
                hits.addAll(matchMarkers(outerEntryName + "!/" + entry.getName(), bytes, markers));
            }
        }
        return hits;
    }

    private static List<String> matchMarkers(String entryLabel, byte[] bytes, List<String> markers) {
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> hits = new ArrayList<>();
        for (String marker : markers) {
            if (content.contains(marker)) {
                hits.add(entryLabel + " contains development key marker \"" + marker + "\"");
            }
        }
        return hits;
    }

    /**
     * Builds a jar shaped like a packaged server distribution containing exactly one
     * nested library jar under {@code BOOT-INF/lib/}, with {@code marker} planted inside
     * one of that nested jar's entries — the shape a real leaked development key would
     * take if a fixture dependency ever ended up on the packaged classpath.
     */
    private static Path buildJarWithPlantedMarkerInNestedLibrary(String marker) throws IOException {
        Path nestedLib = Files.createTempFile("data-prism-nested-lib-", ".jar");
        try (JarOutputStream nestedOutput = new JarOutputStream(Files.newOutputStream(nestedLib))) {
            nestedOutput.putNextEntry(new ZipEntry("io/github/aindriub/dataprism/example/PlantedKey.properties"));
            nestedOutput.write(("dev.key=" + marker).getBytes(StandardCharsets.UTF_8));
            nestedOutput.closeEntry();
        }

        Path outer = Files.createTempFile("data-prism-packaging-positive-control-", ".jar");
        try (JarOutputStream outerOutput = new JarOutputStream(Files.newOutputStream(outer))) {
            outerOutput.putNextEntry(new ZipEntry("BOOT-INF/lib/data-prism-integration-tests-0.3.0.jar"));
            outerOutput.write(Files.readAllBytes(nestedLib));
            outerOutput.closeEntry();
        } finally {
            Files.deleteIfExists(nestedLib);
        }
        return outer;
    }

    /**
     * Builds a jar shaped like a packaged server distribution containing exactly one
     * nested library jar under {@code BOOT-INF/lib/}, with {@code marker} planted inside
     * that nested jar's own {@code META-INF/MANIFEST.MF} — the entry {@code
     * JarInputStream} consumes internally and never returns from {@code getNextEntry()},
     * which is the shape the reviewed defect took.
     */
    private static Path buildJarWithPlantedMarkerInNestedLibraryManifest(String marker) throws IOException {
        Path nestedLib = Files.createTempFile("data-prism-nested-lib-manifest-", ".jar");
        var nestedManifest = new java.util.jar.Manifest();
        nestedManifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        nestedManifest.getMainAttributes().putValue("Dev-Key", marker);
        try (JarOutputStream nestedOutput = new JarOutputStream(Files.newOutputStream(nestedLib), nestedManifest)) {
            nestedOutput.putNextEntry(new ZipEntry("io/github/aindriub/dataprism/example/Placeholder.class"));
            nestedOutput.write(new byte[] {0});
            nestedOutput.closeEntry();
        }

        Path outer = Files.createTempFile("data-prism-packaging-manifest-positive-control-", ".jar");
        try (JarOutputStream outerOutput = new JarOutputStream(Files.newOutputStream(outer))) {
            outerOutput.putNextEntry(new ZipEntry("BOOT-INF/lib/data-prism-integration-tests-0.3.0.jar"));
            outerOutput.write(Files.readAllBytes(nestedLib));
            outerOutput.closeEntry();
        } finally {
            Files.deleteIfExists(nestedLib);
        }
        return outer;
    }

    /**
     * Builds a jar shaped like a packaged server distribution with {@code marker} planted
     * directly in a {@code BOOT-INF/classes/} entry — the non-nested branch of {@link
     * #scanForDevelopmentKeyMaterial}, exercised without any {@code BOOT-INF/lib/*.jar}.
     */
    private static Path buildJarWithPlantedMarkerInBootInfClasses(String marker) throws IOException {
        Path outer = Files.createTempFile("data-prism-packaging-classes-positive-control-", ".jar");
        try (JarOutputStream outerOutput = new JarOutputStream(Files.newOutputStream(outer))) {
            outerOutput.putNextEntry(new ZipEntry(
                    "BOOT-INF/classes/io/github/aindriub/dataprism/server/PlantedKey.properties"));
            outerOutput.write(("dev.key=" + marker).getBytes(StandardCharsets.UTF_8));
            outerOutput.closeEntry();
        }
        return outer;
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * {@code server.port=0} (an ephemeral port), not {@code
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
                "--dataprism.hazelcast.topology=single-node",
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s"
        };
    }

    @AutoConfiguration
    public static class ReviewedExtension {
        /**
         * Printed only from inside {@link #customerAdapter()}'s body, so it can
         * appear in the packaged process's stdout only if Spring actually
         * constructed that bean — proof the extension loaded from {@code
         * -Dloader.path} and registered a working {@code DataSourceAdapter}, not
         * merely that the packaged server started successfully for some other
         * reason. See {@link ServerPackagingIT#executableLoadsAReviewedAdapterExtensionFromLoaderPath()}.
         */
        static final String ADAPTER_LOADED_MARKER =
                "DATAPRISM_TEST_MARKER::REVIEWED_EXTENSION_ADAPTER_CONSTRUCTED";

        @Bean
        IdentityResolver identityResolver() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        DataSourceAdapter<String> customerAdapter() {
            System.out.println(ADAPTER_LOADED_MARKER);
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
