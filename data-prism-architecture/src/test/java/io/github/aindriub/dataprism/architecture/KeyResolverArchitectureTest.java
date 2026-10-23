package io.github.aindriub.dataprism.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key resolver boundaries (key-resolvers.md section 8, test obligation 10), built from predicates so a nested
 * fixture can prove each rule fails:
 * <ol>
 *   <li>No {@code mcp} or {@code connectors} class depends on {@code KeyResolver} or on
 *       {@code orchestration.links}: translation happens in orchestration, never in the tool or connector layers.
 *   <li>No {@code KeyResolver} implementation, and nothing in {@code orchestration.links}, depends on
 *       {@code data-prism-reidentification}: a resolver must not be a path to real values.
 * </ol>
 */
class KeyResolverArchitectureTest {

    private static final String LINKS = "io.github.aindriub.dataprism.orchestration.links..";
    private static final String REIDENTIFICATION = "io.github.aindriub.dataprism.reidentification..";
    private static final String KEY_RESOLVER = "io.github.aindriub.dataprism.core.spi.KeyResolver";

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importWholeGraph() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        List<String> modules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modules));
    }

    // ---- predicates and rule factories ----

    /** {@code KeyResolver} and its nested types ({@code Resolution}). */
    private static DescribedPredicate<JavaClass> keyResolverType() {
        return DescribedPredicate.describe("KeyResolver or a type nested in it",
                c -> c.getName().equals(KEY_RESOLVER) || c.getName().startsWith(KEY_RESOLVER + "$"));
    }

    private static DescribedPredicate<JavaClass> inLinks() {
        return JavaClass.Predicates.resideInAPackage(LINKS);
    }

    private static DescribedPredicate<JavaClass> inMcpOrConnectors() {
        return JavaClass.Predicates.resideInAnyPackage(
                "io.github.aindriub.dataprism.mcp..", "io.github.aindriub.dataprism.connectors..");
    }

    /** A class that is a {@code KeyResolver}, or an anonymous or nested class inside one. */
    private static DescribedPredicate<JavaClass> keyResolverImplementation() {
        return DescribedPredicate.describe("implements KeyResolver",
                c -> c.isAssignableTo(KeyResolver.class) && !c.isEquivalentTo(KeyResolver.class));
    }

    private static ArchRule noDependencyOnResolvers(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().dependOnClassesThat(keyResolverType().or(inLinks()))
                .because("translation is declared and invoked in orchestration, never in the tool or connector layers")
                .allowEmptyShould(true);
    }

    private static ArchRule noDependencyOnReidentification(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().dependOnClassesThat().resideInAPackage(REIDENTIFICATION)
                .because("a key resolver must not be a path to real values")
                .allowEmptyShould(true);
    }

    // ---- the rules, on the real graph ----

    @Test
    void mcpAndConnectorsDoNotDependOnResolversOrLinks() {
        noDependencyOnResolvers(inMcpOrConnectors()).check(CLASSES);
    }

    @Test
    void resolverImplementationsDoNotDependOnReidentification() {
        noDependencyOnReidentification(keyResolverImplementation()).check(CLASSES);
    }

    @Test
    void orchestrationLinksDoNotDependOnReidentification() {
        noDependencyOnReidentification(inLinks()).check(CLASSES);
    }

    @Test
    void thePredicatesMatchRealClasses() {
        assertThat(CLASSES.contain(KEY_RESOLVER)).isTrue();
        assertThat(CLASSES.stream().filter(keyResolverType()).count()).isGreaterThanOrEqualTo(2);
        assertThat(CLASSES.stream().filter(inLinks()).count()).isPositive();
        assertThat(CLASSES.stream().filter(inMcpOrConnectors()).count()).isPositive();
        // The shipped adapter is an anonymous KeyResolver, so the implementation rule has a real subject.
        assertThat(CLASSES.stream().filter(keyResolverImplementation()).count()).isPositive();
        assertThat(keyResolverImplementation().test(CLASSES.get(KEY_RESOLVER))).isFalse();
    }

    // ---- fixtures: each rule must be able to fail ----

    static final class FixtureUsesKeyResolver {
        Object reach() {
            return KeyResolver.class;
        }
    }

    static final class FixtureUsesLinks {
        Object reach() {
            return io.github.aindriub.dataprism.orchestration.links.KeyResolvers.class;
        }
    }

    static class FixtureResolverReachingReidentification implements KeyResolver {
        @Override public String name() { return "fixture"; }
        @Override public KeyNamespace from() { return new KeyNamespace("a"); }
        @Override public KeyNamespace to() { return new KeyNamespace("b"); }
        @Override public Resolution resolve(String key, ResolutionContext context, int maxResults) {
            Object reach = io.github.aindriub.dataprism.reidentification.ReidentificationService.class;
            return new Resolution(List.of(String.valueOf(reach)), false);
        }
    }

    static final class FixtureCleanResolver implements KeyResolver {
        @Override public String name() { return "fixture"; }
        @Override public KeyNamespace from() { return new KeyNamespace("a"); }
        @Override public KeyNamespace to() { return new KeyNamespace("b"); }
        @Override public Resolution resolve(String key, ResolutionContext context, int maxResults) {
            return new Resolution(List.of(), false);
        }
    }

    static final class FixtureNeutral {
        Object reach() {
            return String.class;
        }
    }

    private static DescribedPredicate<JavaClass> named(Class<?> fixture) {
        return DescribedPredicate.describe("the fixture " + fixture.getSimpleName(),
                javaClass -> javaClass.isEquivalentTo(fixture));
    }

    private static EvaluationResult evaluate(ArchRule rule, Class<?>... fixtures) {
        return rule.evaluate(new ClassFileImporter().importClasses(fixtures));
    }

    @Test
    void layerRuleFailsOnADependencyOnKeyResolverOrOnLinks() {
        assertThat(evaluate(noDependencyOnResolvers(named(FixtureUsesKeyResolver.class)),
                FixtureUsesKeyResolver.class).hasViolation()).isTrue();
        assertThat(evaluate(noDependencyOnResolvers(named(FixtureUsesLinks.class)),
                FixtureUsesLinks.class).hasViolation()).isTrue();
        assertThat(evaluate(noDependencyOnResolvers(named(FixtureNeutral.class)),
                FixtureNeutral.class).hasViolation()).isFalse();
    }

    @Test
    void reidentificationRuleFailsOnAResolverThatReachesIt() {
        assertThat(keyResolverImplementation().test(
                new ClassFileImporter().importClasses(FixtureResolverReachingReidentification.class)
                        .get(FixtureResolverReachingReidentification.class))).isTrue();
        assertThat(evaluate(noDependencyOnReidentification(keyResolverImplementation()),
                FixtureResolverReachingReidentification.class).hasViolation()).isTrue();
        assertThat(evaluate(noDependencyOnReidentification(keyResolverImplementation()),
                FixtureCleanResolver.class).hasViolation()).isFalse();
    }

    @Test
    void reidentificationRuleOnLinksFailsOnADependency() {
        assertThat(evaluate(noDependencyOnReidentification(named(FixtureResolverReachingReidentification.class)),
                FixtureResolverReachingReidentification.class).hasViolation()).isTrue();
        assertThat(evaluate(noDependencyOnReidentification(named(FixtureNeutral.class)), FixtureNeutral.class)
                .hasViolation()).isFalse();
    }
}
