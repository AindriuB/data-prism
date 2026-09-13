package io.github.aindriub.dataprism.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The boundaries, enforced rather than described.
 *
 * <p>The example is where these live because it is the only module that depends
 * on every other one, so it is the only place the whole graph is visible.
 */
@AnalyzeClasses(
        packages = "io.github.aindriub.dataprism",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Two mappers exist and no more: {@code SourceTree} reads source objects on
     * the trusted side and never serialises, {@code DataPrismObjectMapper} writes
     * everything a model sees. A third would be a route from source data to the
     * transport that never passes through the privacy engine, and it would look
     * completely normal in review.
     *
     * <p>The allowlist is two names rather than four because the reading side was
     * consolidated. A rule that grows an exception every time someone needs a
     * mapper stops being a rule.
     */
    @ArchTest
    static final ArchRule onlyDesignatedClassesCreateMappers = noClasses()
            .that().resideInAPackage("io.github.aindriub.dataprism..")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.mcp.DataPrismObjectMapper")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.core.SourceTree")
            .should().callConstructor(ObjectMapper.class)
            .because("output is serialised by one mapper, which is what makes the engine unbypassable");

    /** Dependencies point inward. Core must not know what is built on top of it. */
    @ArchTest
    static final ArchRule coreDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage("..dataprism.core..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.mcp..", "..dataprism.orchestration..",
                    "..dataprism.example..", "..dataprism.pseudonymisation..");

    /** The annotation library is what applications take on. It stays standalone. */
    @ArchTest
    static final ArchRule annotationsDependOnNothing = noClasses()
            .that().resideInAPackage("..dataprism.annotations..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.core..", "..dataprism.mcp..",
                    "..dataprism.orchestration..", "..dataprism.validation..",
                    "..dataprism.audit..", "..dataprism.pseudonymisation..");

    /**
     * MCP talks to the orchestrator, never to a source. The connector modules are
     * leaves that nothing imports, which is what makes it impossible for the tool
     * layer to reach a source system directly rather than merely impolite.
     */
    @ArchTest
    static final ArchRule mcpDoesNotReachSources = noClasses()
            .that().resideInAPackage("..dataprism.mcp..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.connectors..", "..dataprism.example..")
            .allowEmptyShould(true);

    /**
     * The orchestrator decides which sources to call and knows none of them. It
     * sees {@code DataSourceAdapter} from core; the implementations are wired in
     * at assembly. Without this the fan-out would slowly acquire knowledge of
     * HTTP, and the next transport would have to be threaded through it.
     */
    @ArchTest
    static final ArchRule orchestrationDoesNotReachConnectors = noClasses()
            .that().resideInAPackage("..dataprism.orchestration..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.connectors..", "..dataprism.example..");

    /** Nothing in the platform depends on a connector; the example wires them. */
    @ArchTest
    static final ArchRule connectorsAreLeaves = noClasses()
            .that().resideInAPackage("io.github.aindriub.dataprism..")
            .and().resideOutsideOfPackages("..dataprism.connectors..", "..dataprism.example..")
            .should().dependOnClassesThat().resideInAPackage("..dataprism.connectors..");

    /** Nothing depends on the example, including the example's own libraries. */
    @ArchTest
    static final ArchRule nothingDependsOnTheExample = noClasses()
            .that().resideOutsideOfPackage("..dataprism.example..")
            .should().dependOnClassesThat().resideInAPackage("..dataprism.example..")
            .allowEmptyShould(true);

    /**
     * Determinism without the cache is a hard requirement: the same subject must
     * resolve identically on a cold JVM with Hazelcast down. Anything random or
     * clock-dependent in this package would break that silently, and only under
     * the conditions nobody tests.
     */
    @ArchTest
    static final ArchRule pseudonymisationIsDeterministic = noClasses()
            .that().resideInAPackage("..dataprism.pseudonymisation..")
            .should().accessClassesThat()
            .haveFullyQualifiedName("java.util.Random")
            .orShould().accessClassesThat().haveFullyQualifiedName("java.security.SecureRandom")
            .orShould().callMethod(java.util.UUID.class, "randomUUID")
            .orShould().callMethod(java.lang.System.class, "currentTimeMillis")
            .orShould().callMethod(java.time.Instant.class, "now")
            .allowEmptyShould(false);

    /**
     * Scrubbing works on a Jackson data tree. Mutating source objects would both
     * bypass record invariants and make the boundary depend on their shape.
     */
    @ArchTest
    static final ArchRule privacyModulesDoNotMutateObjectGraphsReflectively = noClasses()
            .that().resideInAnyPackage("..dataprism.core..", "..dataprism.pseudonymisation..",
                    "..dataprism.validation..", "..dataprism.orchestration..")
            .should().accessClassesThat().haveFullyQualifiedName("sun.misc.Unsafe")
            .orShould().callMethodWhere(reflectiveFieldMutation())
            .orShould().callMethodWhere(methodHandleFieldAccess())
            .allowEmptyShould(false);

    private static DescribedPredicate<JavaMethodCall> reflectiveFieldMutation() {
        return DescribedPredicate.describe("call Field#set* or setAccessible", call -> {
            String owner = call.getTarget().getOwner().getFullName();
            String name = call.getTarget().getName();
            return (owner.equals("java.lang.reflect.Field") && name.startsWith("set"))
                    || (owner.startsWith("java.lang.reflect.") && name.equals("setAccessible"));
        });
    }

    private static DescribedPredicate<JavaMethodCall> methodHandleFieldAccess() {
        return DescribedPredicate.describe("use MethodHandles or VarHandle field access", call -> {
            String owner = call.getTarget().getOwner().getFullName();
            String name = call.getTarget().getName();
            return owner.equals("java.lang.invoke.VarHandle")
                    || (owner.equals("java.lang.invoke.MethodHandles$Lookup")
                    && (name.equals("findGetter") || name.equals("findSetter")
                    || name.equals("findStaticGetter") || name.equals("findStaticSetter")
                    || name.equals("findVarHandle") || name.equals("findStaticVarHandle")
                    || name.equals("unreflectGetter") || name.equals("unreflectSetter")
                    || name.equals("unreflectVarHandle")));
        });
    }

    /**
     * {@code data-prism-security} is authorisation and scope resolution, usable
     * from any transport — it must work identically whether stdio or the HTTP
     * resource server calls it. Depending on MCP, the orchestrator, a connector
     * or the example would make it impossible to reuse from a transport that
     * is not this one.
     */
    @ArchTest
    static final ArchRule securityDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage("..dataprism.security..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.mcp..", "..dataprism.orchestration..",
                    "..dataprism.connectors..", "..dataprism.example..");

    /**
     * Spring Security is task 07's own choice, for turning a verified JWT into
     * an {@code AuthenticatedCaller} at the resource server's edge. Nothing
     * else — least of all {@code data-prism-security}, which is meant to work
     * the same way regardless of which web framework, or none, sits in front
     * of it — may depend on it.
     */
    @ArchTest
    static final ArchRule onlyTheExampleDependsOnSpringSecurity = noClasses()
            .that().resideOutsideOfPackage("..dataprism.example..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
            .allowEmptyShould(true);
}
