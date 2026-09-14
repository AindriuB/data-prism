package io.github.aindriub.dataprism.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the guard: {@link ArchitectureTest}'s whole-graph rules only see the
 * whole graph if every module the root {@code pom.xml} names, and that has
 * main code of its own, is both a declared dependency of this module and
 * actually contributes a class to what gets imported. Without this, a module
 * that drops off the dependency list here — the way {@code
 * data-prism-connectors-rest} once dropped off the module the rules used to
 * live in — silently narrows every whole-graph rule to whatever remains,
 * with nothing red to say so.
 *
 * <p>Two separate checks, deliberately: a module can be a declared
 * dependency and still contribute nothing, which is exactly what {@code
 * data-prism-server} did until {@link ModuleGraph} started reading its
 * {@code target/classes} directly — see that class's javadoc.
 */
class ArchitectureCoverageTest {

    @Test
    void everyModuleWithMainCodeIsOnThisModulesTestClasspath() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        Path ownPom = Path.of(System.getProperty("user.dir")).resolve("pom.xml");

        List<String> declaredModules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        List<String> ownDependencies = ModuleGraph.declaredDependencyArtifactIds(ownPom);

        List<String> modulesWithMainCode = declaredModules.stream()
                .filter(module -> ModuleGraph.hasMainSource(repositoryRoot.resolve(module)))
                .toList();

        assertThat(modulesWithMainCode).contains(
                "data-prism-connectors-rest",
                "data-prism-hazelcast",
                "data-prism-spring-boot-autoconfigure",
                "data-prism-server");

        for (String module : modulesWithMainCode) {
            assertThat(ownDependencies)
                    .as("'%s' has main code but is not a dependency of data-prism-architecture's own pom.xml",
                            module)
                    .contains(module);
        }

        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modulesWithMainCode));

        for (String module : modulesWithMainCode) {
            Path moduleClassesDirectory = repositoryRoot.resolve(module).resolve("target").resolve("classes")
                    .normalize();
            boolean present = imported.stream()
                    .anyMatch(javaClass -> originatesFromModule(javaClass, moduleClassesDirectory));
            assertThat(present)
                    .as("'%s' is a declared dependency but contributed no class to the imported set", module)
                    .isTrue();
        }
    }

    /**
     * Every class ArchUnit imports carries the {@link java.net.URI} it was
     * read from. Reading straight from each module's {@code target/classes}
     * (see {@link ModuleGraph}) means that URI always resolves to a path
     * inside that directory. Comparing the resolved path rather than
     * substring-matching the module name against the URI's text means a
     * checkout directory that happens to be named after a module — someone
     * cloning into a path ending in {@code .../data-prism-server/} — cannot
     * make this check pass without that module's classes actually being on
     * the imported set.
     */
    private static boolean originatesFromModule(JavaClass javaClass, Path moduleClassesDirectory) {
        return javaClass.getSource()
                .map(source -> Path.of(source.getUri()).normalize())
                .map(path -> path.startsWith(moduleClassesDirectory))
                .orElse(false);
    }
}
