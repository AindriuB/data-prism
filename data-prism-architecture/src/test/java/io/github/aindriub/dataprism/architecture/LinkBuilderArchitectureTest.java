package io.github.aindriub.dataprism.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link builder reads the raw record and holds the chained key (sealed-links.md section 6, boundary 1). No
 * {@code mcp} or {@code connectors} class may depend on the builder or on the target selection that feeds it:
 * those layers receive {@code IssuedLink} values, which hold tokens only. Built from predicates so a nested
 * fixture can prove the rule fails.
 */
class LinkBuilderArchitectureTest {

    private static final String BUILDER = "io.github.aindriub.dataprism.orchestration.links.LinkBuilder";
    private static final String TARGETS = "io.github.aindriub.dataprism.orchestration.links.LinkTargets";

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importWholeGraph() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        List<String> modules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modules));
    }

    /** The builder, the target selection, and every type nested in either. */
    private static DescribedPredicate<JavaClass> rawValueHolders() {
        return DescribedPredicate.describe("LinkBuilder, LinkTargets or a type nested in them",
                c -> c.getName().equals(BUILDER) || c.getName().startsWith(BUILDER + "$")
                        || c.getName().equals(TARGETS) || c.getName().startsWith(TARGETS + "$"));
    }

    private static DescribedPredicate<JavaClass> inMcpOrConnectors() {
        return JavaClass.Predicates.resideInAnyPackage(
                "io.github.aindriub.dataprism.mcp..", "io.github.aindriub.dataprism.connectors..");
    }

    private static ArchRule noDependencyOnTheBuilder(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().dependOnClassesThat(rawValueHolders())
                .because("the builder holds the chained key; the tool and connector layers receive tokens only")
                .allowEmptyShould(true);
    }

    @Test
    void noMcpOrConnectorsClassDependsOnTheLinkBuilder() {
        noDependencyOnTheBuilder(inMcpOrConnectors()).check(CLASSES);
    }

    // ---- fixtures: the rule must be able to fail ----

    static final class FixtureUsesTheBuilderResult {
        LinkBuilder.Result result;
    }

    static final class FixtureUsesNothing {
        String value;
    }

    private static DescribedPredicate<JavaClass> named(Class<?> fixture) {
        return DescribedPredicate.describe(fixture.getSimpleName(), c -> c.getName().equals(fixture.getName()));
    }

    private static EvaluationResult evaluate(ArchRule rule, Class<?>... fixtures) {
        return rule.evaluate(new ClassFileImporter().importClasses(fixtures));
    }

    @Test
    void theRuleFailsForAClassThatDependsOnTheBuilder() {
        assertThat(evaluate(noDependencyOnTheBuilder(named(FixtureUsesTheBuilderResult.class)),
                FixtureUsesTheBuilderResult.class, LinkBuilder.Result.class).hasViolation()).isTrue();
    }

    @Test
    void theRulePassesForAClassThatDoesNot() {
        assertThat(evaluate(noDependencyOnTheBuilder(named(FixtureUsesNothing.class)), FixtureUsesNothing.class)
                .hasViolation()).isFalse();
    }
}
