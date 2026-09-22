package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.FieldMetadata;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code ConfiguredJsonSources#assignNestedTokens} really is stable
 * across runs, not merely across two calls inside one JVM.
 *
 * <p>Attempt 2's own determinism test parsed the same catalogue twice inside a
 * single JVM process, which cannot observe the defect it was meant to catch:
 * before this task's fix, ordinal assignment iterated the {@code Map.copyOf}
 * of {@code nested-catalogues}, and that map's iteration order is re-randomised
 * once per JVM process (a fresh salt drawn at class-init time in {@code
 * java.util.ImmutableCollections}, to resist hash-flooding) -- stable within a
 * process, but not across two independent ones. Two parses in the same process
 * always see the same salt and so always agreed, regardless of whether the
 * ordinal assignment underneath was actually a function of a stable order.
 *
 * <p>This test instead launches the parser in two genuinely separate JVM
 * processes (same classpath, fresh process each time) and asserts they mint
 * the identical catalogue-name -> token mapping. It also asserts, directly,
 * that the mapping matches the catalogue names sorted into natural order --
 * i.e. that the assignment is a pure function of the declared name set, never
 * of declaration order or of any map's iteration order.
 */
class ConfiguredJsonNestedCatalogueOrdinalDeterminismTest {

    private static final String YAML = """
            json-sources:
              ord-test:
                base-url: https://ord.example
                path: /v1/x/{subject}
                timeout: PT2S
                model-version: v1
                subject-json-path: id
                fields:
                  id:
                    identifier: true
                  mike:
                    nested: mike
                  zulu:
                    nested: zulu
                  alpha:
                    nested: alpha
                nested-catalogues:
                  mike:
                    leaf:
                      nonSensitive: "inert"
                  zulu:
                    leaf:
                      nonSensitive: "inert"
                  alpha:
                    leaf:
                      nonSensitive: "inert"
            """;

    /**
     * Deliberately declared out of natural order ({@code mike}, {@code zulu},
     * {@code alpha}) so a test that accidentally depended on declaration order
     * rather than on a genuinely sorted assignment could not pass by accident.
     */
    private static final List<String> CATALOGUE_NAMES_IN_NATURAL_ORDER = List.of("alpha", "mike", "zulu");

    /** Invoked only in the forked child process; never called in-process. */
    public static final class Printer {
        private Printer() {
        }

        public static void main(String[] args) throws IOException {
            InputStream in = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
            ConfiguredJsonSourcesConfig config = ConfiguredJsonSources.fromYaml(in);
            ConfiguredJsonSource source = config.sources().get("ord-test");
            // TreeMap: sorts the *printed report* only, so the two runs' output
            // can be compared line by line regardless of any incidental
            // in-process map order -- this has nothing to do with the
            // production ordinal-assignment logic under test.
            TreeMap<String, FieldMetadata> byName = new TreeMap<>();
            for (String name : CATALOGUE_NAMES_IN_NATURAL_ORDER) {
                byName.put(name, source.fields().get(name));
            }
            for (var entry : byName.entrySet()) {
                System.out.println(entry.getKey() + "=" + entry.getValue().valueType().getName());
            }
        }
    }

    private static List<String> runInAFreshJvm() throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(
                javaBin, "-cp", classpath, Printer.class.getName());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        int exit = process.waitFor();
        assertThat(exit).as("child process output: %s", lines).isZero();
        return lines;
    }

    @Test
    @DisplayName("ordinal assignment mints the identical catalogue-name -> token mapping "
            + "across two genuinely separate JVM processes")
    void assignmentIsStableAcrossSeparateJvmProcesses() throws Exception {
        List<String> firstRun = runInAFreshJvm();
        List<String> secondRun = runInAFreshJvm();

        assertThat(firstRun).hasSize(3);
        assertThat(firstRun).isEqualTo(secondRun);
    }

    @Test
    @DisplayName("the mapping is a pure function of the catalogue names sorted into natural order, "
            + "not of the (deliberately out-of-order) declaration order in the YAML")
    void assignmentMatchesNaturalNameOrder() throws Exception {
        List<String> lines = runInAFreshJvm();

        // alpha < mike < zulu naturally, so alpha must get the lowest-ordinal
        // slot, mike the next, zulu the highest -- despite being declared
        // mike, zulu, alpha in the YAML above.
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("alpha=").contains("Slot0");
        assertThat(lines.get(1)).startsWith("mike=").contains("Slot1");
        assertThat(lines.get(2)).startsWith("zulu=").contains("Slot2");
    }
}
