package io.github.aindriub.dataprism.architecture.freeze;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the closed vocabularies (register section 7.4 and the section 15 row "Closed vocabularies").
 *
 * <p>Every enum of any visibility in every module's main classes is listed in {@code freeze/enums.txt} as
 * {@code <class> | <exit> | <via> | <CONSTANTS>}. {@code exit} is {@code out} when the constants' names leave
 * the process (a response, the audit, a metric, a log line, a file or a configuration value) and
 * {@code internal} when they do not; {@code via} says where, or why not. A new enum, a removed one, or a
 * constant added, removed or renamed fails until the list changes with it and the enum is classified.
 *
 * <p>Regenerate with {@code -Dfreeze.write=<directory>}: classified enums keep their exit and via, new ones
 * are written {@code UNCLASSIFIED}, which this test refuses.
 */
class EnumVocabularyFreezeTest {

    private static final String LIST = "enums.txt";
    private static final Set<String> EXITS = Set.of("out", "internal");

    @Test
    void everyEnumAndItsConstantsMatchTheFrozenList() throws IOException {
        Map<String, List<String>> actual = enumsInMainClasses();
        Map<String, String[]> listed = parse(FreezeLists.expected(LIST));
        write(actual, listed);

        List<String> differences = new ArrayList<>();
        Set<String> all = new TreeSet<>(actual.keySet());
        all.addAll(listed.keySet());
        for (String name : all) {
            if (!listed.containsKey(name)) {
                differences.add("new enum, not in freeze/" + LIST + ": " + name + " " + actual.get(name));
            } else if (!actual.containsKey(name)) {
                differences.add("listed but no longer in src/main: " + name);
            } else if (!List.of(listed.get(name)[3].split(",")).equals(actual.get(name))) {
                differences.add("constants differ: " + name + ": code " + actual.get(name) + ", list "
                        + listed.get(name)[3]);
            }
        }
        assertThat(differences).isEmpty();
    }

    @Test
    void everyEnumIsClassifiedAsLeavingTheProcessOrNot() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : parse(FreezeLists.expected(LIST)).entrySet()) {
            String[] fields = entry.getValue();
            if (!EXITS.contains(fields[1])) {
                problems.add(entry.getKey() + ": exit '" + fields[1] + "' is not out or internal");
            }
            if (fields[2].isBlank() || fields[2].equals("UNCLASSIFIED")) {
                problems.add(entry.getKey() + ": no account of where its names go");
            }
        }
        assertThat(problems).isEmpty();
    }

    private static Map<String, List<String>> enumsInMainClasses() throws IOException {
        Path root = MainSources.repositoryRoot();
        List<Path> directories = new ArrayList<>();
        for (String module : MainSources.modules(root)) {
            Path classes = root.resolve(module).resolve("target").resolve("classes");
            if (Files.isDirectory(classes)) {
                directories.add(classes);
            }
        }
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(directories);
        Map<String, List<String>> enums = new TreeMap<>();
        for (JavaClass type : classes) {
            if (type.isEnum() && !type.isAnonymousClass()) {
                List<String> constants = new ArrayList<>();
                for (JavaEnumConstant constant : type.getEnumConstants()) {
                    constants.add(constant.name());
                }
                constants.sort(null);
                enums.put(type.getName(), constants);
            }
        }
        return enums;
    }

    private static Map<String, String[]> parse(List<String> lines) {
        Map<String, String[]> parsed = new TreeMap<>();
        for (String line : lines) {
            String[] fields = line.split(" \\| ", -1);
            assertThat(fields).as(line).hasSize(4);
            parsed.put(fields[0], fields);
        }
        return parsed;
    }

    private static void write(Map<String, List<String>> actual, Map<String, String[]> listed) throws IOException {
        if (System.getProperty("freeze.write") == null) {
            return;
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : actual.entrySet()) {
            String[] old = listed.get(entry.getKey());
            out.add(entry.getKey() + " | " + (old == null ? "UNCLASSIFIED | UNCLASSIFIED" : old[1] + " | " + old[2])
                    + " | " + String.join(",", entry.getValue()));
        }
        FreezeLists.write(LIST, out);
    }
}
