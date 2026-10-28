package io.github.aindriub.dataprism.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The serving gate reaches the stores that never call the sealer through one accessor
 * (docs/plan/design/cluster-security.md 2.4 d2, task 227). {@code PrivacyCluster.servingInstance()} returns the
 * instance while the member serves and refuses while it does not; {@code PrivacyCluster.instance()} stays ungated, so
 * card refresh and pause state keep working. A store that reached its map through {@code instance()} would work
 * around the gate, so no class of the hazelcast module may call it except the ones that must work while the member
 * is closed:
 * <ul>
 *   <li>{@code PrivacyCluster} itself;
 *   <li>{@code MemberCards} and {@code ClusterSentinel}, which prove the member;
 *   <li>{@code HazelcastOversightState}, which reads and writes pause state (a pause is allowed while closed);
 *   <li>{@code IdentityMaps}, which reads the member-local solo keys out of the instance's user context and reaches
 *       no map (added beside the four of the task: its one call is not a store access).
 * </ul>
 */
class ServingInstanceArchitectureTest {

    private static final String HAZELCAST = "io.github.aindriub.dataprism.hazelcast..";
    private static final String PRIVACY_CLUSTER = "io.github.aindriub.dataprism.hazelcast.PrivacyCluster";
    private static final String BASE = "io.github.aindriub.dataprism.hazelcast.";

    /** The classes allowed to call the ungated accessor, by simple name in the hazelcast package. */
    private static final Set<String> ALLOWED = Set.of("PrivacyCluster", "MemberCards", "ClusterSentinel",
            "HazelcastOversightState", "IdentityMaps");

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importWholeGraph() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        List<String> modules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modules));
    }

    // ---- predicates and the rule factory ----

    private static boolean isAllowed(JavaClass c) {
        for (JavaClass outer = c; outer != null; outer = outer.getEnclosingClass().orElse(null)) {
            String name = outer.getName();
            if (ALLOWED.stream().anyMatch(allowed -> name.equals(BASE + allowed))) {
                return true;
            }
        }
        return false;
    }

    /** A class of the hazelcast module that is not one of the allowed callers, nested and anonymous ones included. */
    private static DescribedPredicate<JavaClass> storeLikeClassOfTheHazelcastModule() {
        return JavaClass.Predicates.resideInAPackage(HAZELCAST)
                .and(DescribedPredicate.describe("is not one of the classes allowed to call instance()",
                        c -> !isAllowed(c)));
    }

    private static DescribedPredicate<JavaMethodCall> callsTheUngatedInstance() {
        return DescribedPredicate.describe("call PrivacyCluster.instance()", call ->
                call.getTarget().getName().equals("instance")
                        && call.getTarget().getRawParameterTypes().isEmpty()
                        && call.getTargetOwner().isEquivalentTo(PrivacyCluster.class));
    }

    private static ArchRule noCallToTheUngatedInstance(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().callMethodWhere(callsTheUngatedInstance())
                .because("a store reaches its map through PrivacyCluster.servingInstance(), which refuses while the "
                        + "member is not serving; instance() is ungated so card refresh and pause state still work")
                .allowEmptyShould(true);
    }

    // ---- the rule, on the real graph ----

    @Test
    void noStoreCallsTheUngatedInstance() {
        noCallToTheUngatedInstance(storeLikeClassOfTheHazelcastModule()).check(CLASSES);
    }

    @Test
    void thePredicatesMatchRealClasses() {
        assertThat(CLASSES.contain(PRIVACY_CLUSTER)).isTrue();
        assertThat(CLASSES.stream().filter(storeLikeClassOfTheHazelcastModule()).count()).isGreaterThan(10);
        // The stores are subjects of the rule, and the allowed callers really do call instance().
        for (String store : List.of("HazelcastApprovalStore", "HazelcastScopeBudget", "HazelcastCallerRateLimiter",
                "CachingSyntheticValueSource", "ScopeIdentityIndex")) {
            assertThat(storeLikeClassOfTheHazelcastModule().test(CLASSES.get(BASE + store))).as(store).isTrue();
        }
        for (String allowed : ALLOWED) {
            assertThat(storeLikeClassOfTheHazelcastModule().test(CLASSES.get(BASE + allowed))).as(allowed)
                    .isFalse();
        }
        assertThat(CLASSES.get(BASE + "MemberCards").getMethodCallsFromSelf().stream()
                .anyMatch(callsTheUngatedInstance())).isTrue();
        assertThat(CLASSES.get(BASE + "HazelcastOversightState").getMethodCallsFromSelf().stream()
                .anyMatch(callsTheUngatedInstance())).isTrue();
    }

    // ---- fixtures: the rule must be able to fail ----

    /** A store-like class that reaches its map through the ungated accessor. */
    static final class FixtureStoreCallingInstance {
        private final PrivacyCluster cluster;

        FixtureStoreCallingInstance(PrivacyCluster cluster) {
            this.cluster = cluster;
        }

        Object map() {
            return cluster.instance().getMap("dataprism.fixture");
        }
    }

    /** The same through a lambda, which is how ScopeIdentityIndex resolved its maps. */
    static final class FixtureStoreCallingInstanceInALambda {
        private final Function<String, Object> maps;

        FixtureStoreCallingInstanceInALambda(PrivacyCluster cluster) {
            this.maps = name -> cluster.instance().getMap(name);
        }

        Object map() {
            return maps.apply("dataprism.fixture");
        }
    }

    /** A store-like class that uses the gated accessor. */
    static final class FixtureStoreCallingServingInstance {
        private final PrivacyCluster cluster;

        FixtureStoreCallingServingInstance(PrivacyCluster cluster) {
            this.cluster = cluster;
        }

        Object map() {
            return cluster.servingInstance().getMap("dataprism.fixture");
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
    void theRuleFailsOnAStoreLikeClassThatCallsInstance() {
        assertThat(evaluate(noCallToTheUngatedInstance(named(FixtureStoreCallingInstance.class)),
                FixtureStoreCallingInstance.class).hasViolation()).isTrue();
        assertThat(evaluate(noCallToTheUngatedInstance(named(FixtureStoreCallingInstanceInALambda.class)),
                FixtureStoreCallingInstanceInALambda.class).hasViolation()).isTrue();
    }

    @Test
    void theRulePassesAStoreLikeClassThatCallsServingInstance() {
        assertThat(evaluate(noCallToTheUngatedInstance(named(FixtureStoreCallingServingInstance.class)),
                FixtureStoreCallingServingInstance.class).hasViolation()).isFalse();
    }
}
