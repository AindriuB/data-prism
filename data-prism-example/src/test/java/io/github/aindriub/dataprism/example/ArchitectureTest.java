package io.github.aindriub.dataprism.example;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            .allowEmptyShould(true);
}
