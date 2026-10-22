package io.github.aindriub.dataprism.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sealed-link boundaries (sealed-links.md section 7, item 25), built from predicates so a nested fixture can
 * prove each rule fails:
 * <ol>
 *   <li>Only {@code core.link} and the cluster sealing package create a GCM cipher. The rule is stated on
 *       {@code Cipher.getInstance} of any transformation, because the transformation string is not visible to
 *       the bytecode graph; no other main class calls it today, so the stricter form costs nothing.
 *   <li>{@code core.link} depends on neither {@code data-prism-reidentification} nor {@code data-prism-hazelcast}.
 *   <li>No {@code mcp} or {@code connectors} class depends on {@code core.link}.
 * </ol>
 */
class LinkArchitectureTest {

    private static final String LINK = "io.github.aindriub.dataprism.core.link..";
    private static final String CLUSTER_SEAL = "io.github.aindriub.dataprism.hazelcast.seal..";

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importWholeGraph() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        List<String> modules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modules));
    }

    // ---- rule factories ----

    private static DescribedPredicate<JavaClass> outsideTheSealers() {
        return DescribedPredicate.describe("outside core.link and the cluster sealing package",
                c -> !c.getPackageName().startsWith("io.github.aindriub.dataprism.core.link")
                        && !c.getPackageName().startsWith("io.github.aindriub.dataprism.hazelcast.seal"));
    }

    private static ArchRule noCipherOutside(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().accessTargetWhere(new DescribedPredicate<JavaAccess<?>>("is a call to Cipher.getInstance") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        return access instanceof JavaMethodCall
                                && access.getTarget().getOwner().isEquivalentTo(Cipher.class)
                                && access.getTarget().getName().equals("getInstance");
                    }
                })
                .because("only the link codec and the cluster seal create an AES/GCM cipher")
                .allowEmptyShould(true);
    }

    private static ArchRule linkDoesNotDependOn(DescribedPredicate<JavaClass> origin, String... forbidden) {
        return noClasses().that(origin)
                .should().dependOnClassesThat().resideInAnyPackage(forbidden)
                .because("the link codec is self-contained: it reads no record and reaches no store")
                .allowEmptyShould(true);
    }

    private static ArchRule noDependencyOnLink(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().dependOnClassesThat().resideInAPackage(LINK)
                .because("links are built and followed in orchestration, never in the tool or connector layers")
                .allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaClass> inLink() {
        return JavaClass.Predicates.resideInAPackage(LINK);
    }

    private static DescribedPredicate<JavaClass> inMcpOrConnectors() {
        return JavaClass.Predicates.resideInAnyPackage(
                "io.github.aindriub.dataprism.mcp..", "io.github.aindriub.dataprism.connectors..");
    }

    // ---- the rules, on the real graph ----

    @Test
    void onlyTheSealersCreateACipher() {
        noCipherOutside(outsideTheSealers()).check(CLASSES);
    }

    @Test
    void linkDependsOnNeitherReidentificationNorHazelcast() {
        linkDoesNotDependOn(inLink(), "io.github.aindriub.dataprism.reidentification..",
                "io.github.aindriub.dataprism.hazelcast..").check(CLASSES);
    }

    @Test
    void mcpAndConnectorsDoNotDependOnLink() {
        noDependencyOnLink(inMcpOrConnectors()).check(CLASSES);
    }

    @Test
    void thePredicatesMatchRealClasses() {
        assertThat(CLASSES.contain("io.github.aindriub.dataprism.core.link.LinkCodec")).isTrue();
        assertThat(CLASSES.stream().filter(inLink()).count()).isPositive();
        assertThat(CLASSES.stream().filter(inMcpOrConnectors()).count()).isPositive();
        JavaClass sealer = CLASSES.get("io.github.aindriub.dataprism.hazelcast.seal.Sealer");
        JavaClass codec = CLASSES.get("io.github.aindriub.dataprism.core.link.LinkCodec");
        assertThat(outsideTheSealers().test(sealer)).isFalse();
        assertThat(outsideTheSealers().test(codec)).isFalse();
        assertThat(outsideTheSealers().test(CLASSES.get("io.github.aindriub.dataprism.mcp.DataPrismObjectMapper")))
                .isTrue();
        // The two sealers really do create ciphers, so the rule has something to be about.
        assertThat(codec.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTarget().getOwner().isEquivalentTo(Cipher.class)
                        && call.getTarget().getName().equals("getInstance"))).isTrue();
        assertThat(CLASSES.stream()
                .filter(c -> c.getPackageName().startsWith("io.github.aindriub.dataprism.hazelcast.seal"))
                .anyMatch(c -> c.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTarget().getOwner().isEquivalentTo(Cipher.class)
                                && call.getTarget().getName().equals("getInstance")))).isTrue();
    }

    // ---- fixtures: each rule must be able to fail ----

    static final class FixtureCreatesACipher {
        Object create() throws GeneralSecurityException {
            return Cipher.getInstance("AES/GCM/NoPadding");
        }
    }

    static final class FixtureCreatesNoCipher {
        Object create() {
            return "no cipher";
        }
    }

    static final class FixtureDependsOnReidentification {
        Object reach() {
            return io.github.aindriub.dataprism.reidentification.ReidentificationService.class;
        }
    }

    static final class FixtureDependsOnHazelcast {
        Object reach() {
            return io.github.aindriub.dataprism.hazelcast.seal.Sealer.class;
        }
    }

    static final class FixtureDependsOnLink {
        Object reach() {
            return io.github.aindriub.dataprism.core.link.LinkCodec.class;
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
    void cipherRuleFailsOnACipherCreatedOutsideTheSealers() {
        EvaluationResult result = evaluate(noCipherOutside(named(FixtureCreatesACipher.class)),
                FixtureCreatesACipher.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("getInstance");
    }

    @Test
    void cipherRulePassesForAClassWithoutOne() {
        assertThat(evaluate(noCipherOutside(named(FixtureCreatesNoCipher.class)), FixtureCreatesNoCipher.class)
                .hasViolation()).isFalse();
    }

    @Test
    void linkRuleFailsOnReidentificationAndOnHazelcast() {
        String[] forbidden = {"io.github.aindriub.dataprism.reidentification..",
                "io.github.aindriub.dataprism.hazelcast.."};
        assertThat(evaluate(linkDoesNotDependOn(named(FixtureDependsOnReidentification.class), forbidden),
                FixtureDependsOnReidentification.class).hasViolation()).isTrue();
        assertThat(evaluate(linkDoesNotDependOn(named(FixtureDependsOnHazelcast.class), forbidden),
                FixtureDependsOnHazelcast.class).hasViolation()).isTrue();
        assertThat(evaluate(linkDoesNotDependOn(named(FixtureNeutral.class), forbidden), FixtureNeutral.class)
                .hasViolation()).isFalse();
    }

    @Test
    void layerRuleFailsOnADependencyOnLink() {
        assertThat(evaluate(noDependencyOnLink(named(FixtureDependsOnLink.class)), FixtureDependsOnLink.class)
                .hasViolation()).isTrue();
        assertThat(evaluate(noDependencyOnLink(named(FixtureNeutral.class)), FixtureNeutral.class)
                .hasViolation()).isFalse();
    }
}
