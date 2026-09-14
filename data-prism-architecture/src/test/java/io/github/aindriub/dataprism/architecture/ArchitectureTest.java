package io.github.aindriub.dataprism.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The boundaries, enforced rather than described.
 *
 * <p>This module carries no production code of its own. It exists so these
 * rules have somewhere to see the whole graph from: {@link #CLASSES} is
 * built from every other module named in the root {@code pom.xml} that has
 * main code — including {@code data-prism-connectors-rest}, {@code
 * data-prism-hazelcast}, {@code data-prism-spring-boot-autoconfigure} and
 * {@code data-prism-server}, none of which were on the classpath of the
 * module these rules used to live in. {@link ArchitectureCoverageTest}
 * checks that this stays true; see {@link ModuleGraph}'s javadoc for why the
 * import reads each module's {@code target/classes} directly rather than
 * relying on a classpath assembled from Maven dependencies.
 */
class ArchitectureTest {

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importWholeGraph() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        List<String> modules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modules));
    }

    /**
     * Two mappers write output and no more: {@code SourceTree} reads source
     * objects on the trusted side and never serialises, {@code
     * DataPrismObjectMapper} writes everything a model sees. A third would be
     * a route from source data to the transport that never passes through the
     * privacy engine, and it would look completely normal in review.
     *
     * <p>The allowlist also names five classes that construct an {@code
     * ObjectMapper} over a {@code YAMLFactory} to hand-parse a configuration
     * file read once at startup: {@code RestSources} (source definitions),
     * {@code SecurityPolicy} (the role-to-capability map), {@code
     * PrivacyProfiles}, {@code ModelDescriptors} and {@code
     * VocabularyRegistry}. None of them ever sees a source record — each
     * reads a file the operator wrote before the process started, and none of
     * their output reaches a transport. A YAML configuration reader is not a
     * route from source data to transport, which is what this rule actually
     * guards against; matching every {@code ObjectMapper} constructor rather
     * than only the no-argument one is what makes that distinction visible in
     * the first place — before, the argument-taking constructor these five
     * call evaded the rule entirely.
     */
    private static final ArchRule ONLY_DESIGNATED_CLASSES_CREATE_MAPPERS = noClasses()
            .that().resideInAPackage("io.github.aindriub.dataprism..")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.mcp.DataPrismObjectMapper")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.core.SourceTree")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.connectors.rest.RestSources")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.security.SecurityPolicy")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.core.policy.PrivacyProfiles")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.core.descriptor.ModelDescriptors")
            .and().doNotHaveFullyQualifiedName(
                    "io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry")
            .should().callConstructorWhere(constructsAnObjectMapper())
            .because("output is serialised by one mapper, which is what makes the engine unbypassable");

    @Test
    void onlyDesignatedClassesCreateMappers() {
        ONLY_DESIGNATED_CLASSES_CREATE_MAPPERS.check(CLASSES);
    }

    private static DescribedPredicate<JavaConstructorCall> constructsAnObjectMapper() {
        return DescribedPredicate.describe("call any ObjectMapper constructor",
                call -> call.getTarget().getOwner().isEquivalentTo(ObjectMapper.class));
    }

    /** Dependencies point inward. Core must not know what is built on top of it. */
    private static final ArchRule CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that().resideInAPackage("..dataprism.core..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.mcp..", "..dataprism.orchestration..",
                    "..dataprism.example..", "..dataprism.pseudonymisation..");

    @Test
    void coreDoesNotDependOnOuterLayers() {
        CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS.check(CLASSES);
    }

    /** The annotation library is what applications take on. It stays standalone. */
    private static final ArchRule ANNOTATIONS_DEPEND_ON_NOTHING = noClasses()
            .that().resideInAPackage("..dataprism.annotations..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.core..", "..dataprism.mcp..",
                    "..dataprism.orchestration..", "..dataprism.validation..",
                    "..dataprism.audit..", "..dataprism.pseudonymisation..");

    @Test
    void annotationsDependOnNothing() {
        ANNOTATIONS_DEPEND_ON_NOTHING.check(CLASSES);
    }

    /**
     * MCP talks to the orchestrator, never to a source. The connector modules are
     * leaves that nothing imports, which is what makes it impossible for the tool
     * layer to reach a source system directly rather than merely impolite.
     */
    private static final ArchRule MCP_DOES_NOT_REACH_SOURCES = noClasses()
            .that().resideInAPackage("..dataprism.mcp..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.connectors..", "..dataprism.example..")
            .allowEmptyShould(true);

    @Test
    void mcpDoesNotReachSources() {
        MCP_DOES_NOT_REACH_SOURCES.check(CLASSES);
    }

    /**
     * The orchestrator decides which sources to call and knows none of them. It
     * sees {@code DataSourceAdapter} from core; the implementations are wired in
     * at assembly. Without this the fan-out would slowly acquire knowledge of
     * HTTP, and the next transport would have to be threaded through it.
     */
    private static final ArchRule ORCHESTRATION_DOES_NOT_REACH_CONNECTORS = noClasses()
            .that().resideInAPackage("..dataprism.orchestration..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.connectors..", "..dataprism.example..");

    @Test
    void orchestrationDoesNotReachConnectors() {
        ORCHESTRATION_DOES_NOT_REACH_CONNECTORS.check(CLASSES);
    }

    /** Nothing in the platform depends on a connector; the example wires them. */
    private static final ArchRule CONNECTORS_ARE_LEAVES = noClasses()
            .that().resideInAPackage("io.github.aindriub.dataprism..")
            .and().resideOutsideOfPackages("..dataprism.connectors..", "..dataprism.example..")
            .should().dependOnClassesThat().resideInAPackage("..dataprism.connectors..");

    @Test
    void connectorsAreLeaves() {
        CONNECTORS_ARE_LEAVES.check(CLASSES);
    }

    /** Nothing depends on the example, including the example's own libraries. */
    private static final ArchRule NOTHING_DEPENDS_ON_THE_EXAMPLE = noClasses()
            .that().resideOutsideOfPackage("..dataprism.example..")
            .should().dependOnClassesThat().resideInAPackage("..dataprism.example..")
            .allowEmptyShould(true);

    @Test
    void nothingDependsOnTheExample() {
        NOTHING_DEPENDS_ON_THE_EXAMPLE.check(CLASSES);
    }

    /**
     * Determinism without the cache is a hard requirement: the same subject must
     * resolve identically on a cold JVM with Hazelcast down. Anything random or
     * clock-dependent in this package would break that silently, and only under
     * the conditions nobody tests.
     */
    private static final ArchRule PSEUDONYMISATION_IS_DETERMINISTIC = noClasses()
            .that().resideInAPackage("..dataprism.pseudonymisation..")
            .should().accessClassesThat()
            .haveFullyQualifiedName("java.util.Random")
            .orShould().accessClassesThat().haveFullyQualifiedName("java.security.SecureRandom")
            .orShould().callMethod(java.util.UUID.class, "randomUUID")
            .orShould().callMethod(java.lang.System.class, "currentTimeMillis")
            .orShould().callMethod(java.time.Instant.class, "now")
            .allowEmptyShould(false);

    @Test
    void pseudonymisationIsDeterministic() {
        PSEUDONYMISATION_IS_DETERMINISTIC.check(CLASSES);
    }

    /**
     * Scrubbing works on a Jackson data tree. Mutating source objects would both
     * bypass record invariants and make the boundary depend on their shape.
     */
    private static final ArchRule PRIVACY_MODULES_DO_NOT_MUTATE_OBJECT_GRAPHS_REFLECTIVELY = noClasses()
            .that().resideInAnyPackage("..dataprism.core..", "..dataprism.pseudonymisation..",
                    "..dataprism.validation..", "..dataprism.orchestration..")
            .should().accessClassesThat().haveFullyQualifiedName("sun.misc.Unsafe")
            .orShould().callMethodWhere(reflectiveFieldMutation())
            .orShould().callMethodWhere(methodHandleFieldAccess())
            .allowEmptyShould(false);

    @Test
    void privacyModulesDoNotMutateObjectGraphsReflectively() {
        PRIVACY_MODULES_DO_NOT_MUTATE_OBJECT_GRAPHS_REFLECTIVELY.check(CLASSES);
    }

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
    private static final ArchRule SECURITY_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that().resideInAPackage("..dataprism.security..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.mcp..", "..dataprism.orchestration..",
                    "..dataprism.connectors..", "..dataprism.example..");

    @Test
    void securityDoesNotDependOnOuterLayers() {
        SECURITY_DOES_NOT_DEPEND_ON_OUTER_LAYERS.check(CLASSES);
    }

    /**
     * Spring Security is task 07's own choice, for turning a verified JWT into
     * an {@code AuthenticatedCaller} at the resource server's edge. Nothing
     * else — least of all {@code data-prism-security}, which is meant to work
     * the same way regardless of which web framework, or none, sits in front
     * of it — may depend on it.
     *
     * <p>{@code data-prism-server} is now visible to this rule for the first
     * time and also depends on Spring Security, for the same reason the
     * example does: it is the standalone distribution's own resource-server
     * edge, and {@code ServerArchitectureTest} in that module already
     * enforces that nothing else in {@code data-prism-server} does. This rule
     * is left exactly as it ran before — it does not yet know {@code
     * ..dataprism.server..} is a second legitimate edge, so it now fails
     * against {@code JwtCallerContextExtractor} and {@code
     * ServerSecurityConfiguration}. That failure is reported rather than
     * silenced here; widening the exception is a design decision for
     * whoever owns this rule next, not something to do quietly while moving
     * the file.
     */
    private static final ArchRule ONLY_THE_EXAMPLE_DEPENDS_ON_SPRING_SECURITY = noClasses()
            .that().resideOutsideOfPackage("..dataprism.example..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
            .allowEmptyShould(true);

    @Test
    void onlyTheExampleDependsOnSpringSecurity() {
        ONLY_THE_EXAMPLE_DEPENDS_ON_SPRING_SECURITY.check(CLASSES);
    }
}
