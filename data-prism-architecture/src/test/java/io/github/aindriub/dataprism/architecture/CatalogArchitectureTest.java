package io.github.aindriub.dataprism.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Catalog boundaries (catalog.md section 7, items 1 and 5): the tool layer and the connector
 * modules do not depend on adapter implementations, and {@code core} names no business domain.
 * Each rule is built from a predicate so a nested fixture can prove it fails.
 */
class CatalogArchitectureTest {

    private static final List<String> DOMAIN_WORDS =
            List.of("Parcel", "Depot", "Courier", "Route", "Customer", "Patient", "Invoice");

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importWholeGraph() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        List<String> modules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modules));
    }

    private static DescribedPredicate<JavaClass> inMcpOrConnectors() {
        return DescribedPredicate.describe("in mcp or connectors", javaClass ->
                javaClass.getPackageName().contains(".dataprism.mcp")
                        || javaClass.getPackageName().contains(".dataprism.connectors"));
    }

    private static DescribedPredicate<JavaClass> isInCore() {
        return DescribedPredicate.describe("in a core package", javaClass ->
                javaClass.getPackageName().startsWith("io.github.aindriub.dataprism.core"));
    }

    /**
     * An adapter implementation outside the connectors: assignable to the SPI, not the SPI itself.
     * A connector may build on its own adapter classes (a configured source wraps the REST one);
     * what is forbidden is a connector or the tool layer depending on any other adapter.
     */
    private static DescribedPredicate<JavaClass> isAdapterImplementation() {
        return DescribedPredicate.describe("a DataSourceAdapter implementation outside the connectors",
                javaClass -> !javaClass.isEquivalentTo(DataSourceAdapter.class)
                        && javaClass.isAssignableTo(DataSourceAdapter.class)
                        && !javaClass.getPackageName().contains(".dataprism.connectors"));
    }

    private static ArchRule noDependencyOnAdapterImplementations(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin).should().dependOnClassesThat(isAdapterImplementation())
                .because("the tool and connector layers reach sources only through the orchestrator")
                .allowEmptyShould(true);
    }

    private static ArchRule noBusinessDomainNames(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin).should().haveSimpleNameContaining(DOMAIN_WORDS.get(0))
                .orShould().haveSimpleNameContaining(DOMAIN_WORDS.get(1))
                .orShould().haveSimpleNameContaining(DOMAIN_WORDS.get(2))
                .orShould().haveSimpleNameContaining(DOMAIN_WORDS.get(3))
                .orShould().haveSimpleNameContaining(DOMAIN_WORDS.get(4))
                .orShould().haveSimpleNameContaining(DOMAIN_WORDS.get(5))
                .orShould().haveSimpleNameContaining(DOMAIN_WORDS.get(6))
                .because("the core carries no business domain")
                .allowEmptyShould(true);
    }

    @Test
    void mcpAndConnectorsDoNotDependOnAdapterImplementations() {
        noDependencyOnAdapterImplementations(inMcpOrConnectors()).check(CLASSES);
    }

    @Test
    void coreNamesNoBusinessDomain() {
        noBusinessDomainNames(isInCore()).check(CLASSES);
    }

    // ---- fixtures: each rule must be able to fail ----

    static final class FixtureAdapter implements DataSourceAdapter<String> {
        @Override
        public String sourceName() {
            return "fixture";
        }

        @Override
        public Class<String> responseType() {
            return String.class;
        }

        @Override
        public String fetch(DataRequest request) {
            return "";
        }
    }

    static final class FixtureDependsOnAdapter {
        Object reach() {
            return new FixtureAdapter();
        }
    }

    static final class FixtureDependsOnSpiOnly {
        Class<?> reach() {
            return DataSourceAdapter.class;
        }
    }

    static final class FixtureCourierRoster {
    }

    static final class FixtureNeutralThing {
    }

    private static DescribedPredicate<JavaClass> named(Class<?> fixture) {
        return DescribedPredicate.describe("the fixture " + fixture.getSimpleName(),
                javaClass -> javaClass.isEquivalentTo(fixture));
    }

    private static EvaluationResult evaluate(ArchRule rule, Class<?>... fixtures) {
        return rule.evaluate(new ClassFileImporter().importClasses(fixtures));
    }

    @Test
    void adapterRuleFailsOnAnAdapterDependency() {
        EvaluationResult result = evaluate(noDependencyOnAdapterImplementations(named(FixtureDependsOnAdapter.class)),
                FixtureDependsOnAdapter.class, FixtureAdapter.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("FixtureAdapter");
    }

    @Test
    void adapterRulePassesOnADependencyOnTheSpiAlone() {
        assertThat(evaluate(noDependencyOnAdapterImplementations(named(FixtureDependsOnSpiOnly.class)),
                FixtureDependsOnSpiOnly.class).hasViolation()).isFalse();
    }

    @Test
    void domainRuleFailsOnADomainWord() {
        EvaluationResult result = evaluate(noBusinessDomainNames(named(FixtureCourierRoster.class)),
                FixtureCourierRoster.class);
        assertThat(result.hasViolation()).isTrue();
    }

    @Test
    void domainRulePassesOnANeutralName() {
        assertThat(evaluate(noBusinessDomainNames(named(FixtureNeutralThing.class)), FixtureNeutralThing.class)
                .hasViolation()).isFalse();
    }
}
