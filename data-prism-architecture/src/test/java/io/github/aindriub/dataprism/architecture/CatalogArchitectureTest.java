package io.github.aindriub.dataprism.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Catalog boundaries (catalog.md section 7, items 1 and 5): the tool layer and the connector
 * modules do not depend on adapter implementations, and {@code core} names no business domain.
 * Each rule is built from a predicate so a nested fixture can prove it fails.
 *
 * <p>Decision (coordinator, review round 1): there is no blanket exemption for the connector
 * modules. {@link #ALLOWED_COMPOSITIONS} lists, by class name, every dependency on a
 * {@code DataSourceAdapter} implementation that is known and intended: today a configured JSON
 * source wraps the REST adapter, and its initializer builds the configured adapter. Any other
 * dependency from {@code mcp} or {@code connectors} on an adapter implementation fails, so a new
 * composition must be added here in review.
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

    private static final String CONNECTORS = "io.github.aindriub.dataprism.connectors.rest.";

    /** "origin -> target": the reviewed adapter compositions inside the connectors. */
    static final Set<String> ALLOWED_COMPOSITIONS = Set.of(
            CONNECTORS + "ConfiguredJsonDataSourceAdapter -> " + CONNECTORS + "RestDataSourceAdapter",
            CONNECTORS + "ConfiguredJsonSourcesInitializer -> " + CONNECTORS + "ConfiguredJsonDataSourceAdapter");

    private static DescribedPredicate<JavaClass> inMcpOrConnectors() {
        return JavaClass.Predicates.resideInAnyPackage(
                "io.github.aindriub.dataprism.mcp..", "io.github.aindriub.dataprism.connectors..");
    }

    private static DescribedPredicate<JavaClass> isInCore() {
        return JavaClass.Predicates.resideInAPackage("io.github.aindriub.dataprism.core..");
    }

    /** An adapter implementation: assignable to the SPI, and not the SPI itself. */
    private static DescribedPredicate<JavaClass> isAdapterImplementation() {
        return DescribedPredicate.describe("a DataSourceAdapter implementation", javaClass ->
                !javaClass.isEquivalentTo(DataSourceAdapter.class)
                        && javaClass.isAssignableTo(DataSourceAdapter.class));
    }

    private static ArchRule noDependencyOnAdapterImplementations(
            DescribedPredicate<JavaClass> origin, Set<String> allowed) {
        ArchCondition<JavaClass> condition = new ArchCondition<>(
                "not depend on a DataSourceAdapter implementation outside the allowed compositions") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!target.equals(javaClass) && isAdapterImplementation().test(target)
                            && !allowed.contains(javaClass.getName() + " -> " + target.getName())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
        return ArchRuleDefinition.classes().that(origin).should(condition)
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
        noDependencyOnAdapterImplementations(inMcpOrConnectors(), ALLOWED_COMPOSITIONS).check(CLASSES);
    }

    @Test
    void coreNamesNoBusinessDomain() {
        noBusinessDomainNames(isInCore()).check(CLASSES);
    }

    @Test
    void theOriginPredicatesMatchTheRealPackages() {
        assertThat(CLASSES.get("io.github.aindriub.dataprism.mcp.DataPrismObjectMapper"))
                .matches(inMcpOrConnectors()::test);
        assertThat(CLASSES.get(CONNECTORS + "RestDataSourceAdapter")).matches(inMcpOrConnectors()::test);
        assertThat(CLASSES.get("io.github.aindriub.dataprism.core.spi.DataRequest")).matches(isInCore()::test);
        assertThat(CLASSES.get("io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog"))
                .matches(c -> !inMcpOrConnectors().test(c) && !isInCore().test(c));
        assertThat(CLASSES.stream().filter(inMcpOrConnectors()).count()).isPositive();
        assertThat(CLASSES.stream().filter(isInCore()).count()).isPositive();
    }

    @Test
    void theAllowListNamesRealClasses() {
        for (String entry : ALLOWED_COMPOSITIONS) {
            String[] parts = entry.split(" -> ");
            assertThat(CLASSES.contain(parts[0])).as(parts[0]).isTrue();
            assertThat(CLASSES.contain(parts[1])).as(parts[1]).isTrue();
            assertThat(isAdapterImplementation().test(CLASSES.get(parts[1]))).as(parts[1]).isTrue();
        }
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

    static final class FixtureParcelThing { }
    static final class FixtureDepotThing { }
    static final class FixtureCourierThing { }
    static final class FixtureRouteThing { }
    static final class FixtureCustomerThing { }
    static final class FixturePatientThing { }
    static final class FixtureInvoiceThing { }
    static final class FixtureNeutralThing { }

    private static DescribedPredicate<JavaClass> named(Class<?> fixture) {
        return DescribedPredicate.describe("the fixture " + fixture.getSimpleName(),
                javaClass -> javaClass.isEquivalentTo(fixture));
    }

    private static EvaluationResult evaluate(ArchRule rule, Class<?>... fixtures) {
        return rule.evaluate(new ClassFileImporter().importClasses(fixtures));
    }

    @Test
    void adapterRuleFailsOnAnAdapterDependency() {
        EvaluationResult result = evaluate(
                noDependencyOnAdapterImplementations(named(FixtureDependsOnAdapter.class), Set.of()),
                FixtureDependsOnAdapter.class, FixtureAdapter.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("FixtureAdapter");
    }

    @Test
    void adapterRulePassesForAnAllowListedComposition() {
        Set<String> allowed = Set.of(FixtureDependsOnAdapter.class.getName() + " -> " + FixtureAdapter.class.getName());
        assertThat(evaluate(noDependencyOnAdapterImplementations(named(FixtureDependsOnAdapter.class), allowed),
                FixtureDependsOnAdapter.class, FixtureAdapter.class).hasViolation()).isFalse();
    }

    @Test
    void adapterRuleStillFailsForAPairThatIsNotAllowListed() {
        Set<String> allowed = Set.of(FixtureDependsOnSpiOnly.class.getName() + " -> " + FixtureAdapter.class.getName());
        assertThat(evaluate(noDependencyOnAdapterImplementations(named(FixtureDependsOnAdapter.class), allowed),
                FixtureDependsOnAdapter.class, FixtureAdapter.class).hasViolation()).isTrue();
    }

    @Test
    void adapterRulePassesOnADependencyOnTheSpiAlone() {
        assertThat(evaluate(noDependencyOnAdapterImplementations(named(FixtureDependsOnSpiOnly.class), Set.of()),
                FixtureDependsOnSpiOnly.class).hasViolation()).isFalse();
    }

    @Test
    void domainRuleFailsOnEveryDomainWord() {
        for (Class<?> fixture : List.of(FixtureParcelThing.class, FixtureDepotThing.class, FixtureCourierThing.class,
                FixtureRouteThing.class, FixtureCustomerThing.class, FixturePatientThing.class,
                FixtureInvoiceThing.class)) {
            assertThat(evaluate(noBusinessDomainNames(named(fixture)), fixture).hasViolation())
                    .as(fixture.getSimpleName()).isTrue();
        }
    }

    @Test
    void domainRulePassesOnANeutralName() {
        assertThat(evaluate(noBusinessDomainNames(named(FixtureNeutralThing.class)), FixtureNeutralThing.class)
                .hasViolation()).isFalse();
    }
}
