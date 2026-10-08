package io.github.aindriub.dataprism.architecture;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.MapperBuilder;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.aindriub.dataprism.audit.fixture.AuditDependsOnMcpFixture;
import io.github.aindriub.dataprism.mapper.fixture.BuildOnlyMapperFixture;
import io.github.aindriub.dataprism.mapper.fixture.BuilderMapperFixture;
import io.github.aindriub.dataprism.mapper.fixture.ConstructorMapperFixture;
import io.github.aindriub.dataprism.mapper.fixture.RebuildMapperFixture;
import io.github.aindriub.dataprism.hazelcast.ScopeIdentityIndex;
import io.github.aindriub.dataprism.oversight.fixture.OversightDependsOnMcpFixture;
import io.github.aindriub.dataprism.spring.boot.JwtCallerContextExtractor;
import io.github.aindriub.dataprism.spring.boot.JwtDecoderSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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
     * <p>The allowlist also names five classes that build a YAML mapper
     * ({@code YAMLMapper.builder()}) to hand-parse a configuration file read
     * once at startup: {@code RestSources} (source definitions), {@code
     * SecurityPolicy} (the role-to-capability map), {@code PrivacyProfiles},
     * {@code ModelDescriptors} and {@code VocabularyRegistry}. None of them
     * ever sees a source record: each reads a file the operator wrote before
     * the process started, and none of their output reaches a transport.
     *
     * <p>Jackson 3 mappers are immutable and built from a builder, so a rule
     * that matched only {@code ObjectMapper} constructors would no longer see
     * most of them. This rule matches every way a mapper comes into being: a
     * constructor of {@code ObjectMapper} or a subtype, a static {@code
     * builder(..)} on one, {@code build()} on a {@code MapperBuilder} or a
     * subtype, and {@code ObjectMapper#rebuild()}, which derives a new mapper
     * from an existing one. The last matters because a reconfigured copy of
     * data-prism's own mapper is exactly as much a second mapper as a fresh one.
     * It is checked against negative fixtures below, so it cannot quietly go
     * blind to one of the four.
     */
    private static final ArchRule ONLY_DESIGNATED_CLASSES_CREATE_MAPPERS = noClasses()
            .that().resideInAPackage("io.github.aindriub.dataprism..")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.mcp.DataPrismObjectMapper")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.core.engine.SourceTree")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.connectors.rest.RestSources")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.security.SecurityPolicy")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.core.policy.PrivacyProfiles")
            .and().doNotHaveFullyQualifiedName("io.github.aindriub.dataprism.core.descriptor.ModelDescriptors")
            .and().doNotHaveFullyQualifiedName(
                    "io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry")
            .should().callConstructorWhere(constructsAMapper())
            .orShould().callMethodWhere(callsAMapperBuilderFactory())
            .orShould().callMethodWhere(buildsAMapper())
            .orShould().callMethodWhere(rebuildsAMapper())
            .because("output is serialised by one mapper, which is what makes the engine unbypassable");

    @Test
    void onlyDesignatedClassesCreateMappers() {
        ONLY_DESIGNATED_CLASSES_CREATE_MAPPERS.check(CLASSES);
    }

    /** A class outside the allowlist that builds a mapper is reported, whichever way it does it. */
    @Test
    void mapperRuleCatchesAConstructor() {
        assertMapperViolation(ConstructorMapperFixture.class, "ObjectMapper.<init>");
    }

    @Test
    void mapperRuleCatchesABuilder() {
        assertMapperViolation(BuilderMapperFixture.class, "JsonMapper.builder");
    }

    @Test
    void mapperRuleCatchesBuildOnAPassedInBuilder() {
        assertMapperViolation(BuildOnlyMapperFixture.class, "MapperBuilder.build");
    }

    @Test
    void mapperRuleCatchesARebuiltMapper() {
        assertMapperViolation(RebuildMapperFixture.class, "ObjectMapper.rebuild");
    }

    private static void assertMapperViolation(Class<?> fixtureClass, String call) {
        EvaluationResult result = ONLY_DESIGNATED_CLASSES_CREATE_MAPPERS
                .evaluate(new ClassFileImporter().importClasses(fixtureClass));
        org.assertj.core.api.Assertions.assertThat(result.hasViolation()).isTrue();
        org.assertj.core.api.Assertions.assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains(fixtureClass.getName())
                .contains(call);
    }

    private static DescribedPredicate<JavaConstructorCall> constructsAMapper() {
        return DescribedPredicate.describe("call a constructor of ObjectMapper or a subtype",
                call -> call.getTargetOwner().isAssignableTo(ObjectMapper.class));
    }

    private static DescribedPredicate<JavaMethodCall> callsAMapperBuilderFactory() {
        return DescribedPredicate.describe("call a static builder(..) on ObjectMapper or a subtype", call ->
                call.getTarget().getName().equals("builder")
                        && call.getTargetOwner().isAssignableTo(ObjectMapper.class));
    }

    private static DescribedPredicate<JavaMethodCall> buildsAMapper() {
        return DescribedPredicate.describe("call build() on a MapperBuilder or a subtype", call ->
                call.getTarget().getName().equals("build")
                        && call.getTargetOwner().isAssignableTo(MapperBuilder.class));
    }

    private static DescribedPredicate<JavaMethodCall> rebuildsAMapper() {
        return DescribedPredicate.describe("call ObjectMapper#rebuild()", call ->
                call.getTarget().getName().equals("rebuild")
                        && call.getTargetOwner().isAssignableTo(ObjectMapper.class));
    }

    /**
     * The allowlist above only holds "a YAML configuration reader is not a
     * route from source data to transport" for as long as these five classes
     * actually only read. Nothing stops a later edit from also calling
     * {@code mapper.writeValue(...)} on the same instance: the mapper-creation
     * rule above would stay silent, because it is not about what the mapper
     * is used for once built. This rule is: none of the five designated YAML
     * readers may call any {@code ObjectMapper#write*} or {@code
     * ObjectMapper#writer*} method, so the exemption cannot quietly widen from
     * "parses a configuration file" to "also serialises something" without
     * failing here first.
     */
    private static final ArchRule DESIGNATED_YAML_READERS_DO_NOT_WRITE = noClasses()
            .that(isADesignatedYamlReader())
            .should().callMethodWhere(callsAMapperWriteMethod());

    @Test
    void designatedYamlReadersDoNotWrite() {
        DESIGNATED_YAML_READERS_DO_NOT_WRITE.check(CLASSES);
    }

    private static DescribedPredicate<JavaClass> isADesignatedYamlReader() {
        Set<String> designatedYamlReaders = Set.of(
                "io.github.aindriub.dataprism.connectors.rest.RestSources",
                "io.github.aindriub.dataprism.security.SecurityPolicy",
                "io.github.aindriub.dataprism.core.policy.PrivacyProfiles",
                "io.github.aindriub.dataprism.core.descriptor.ModelDescriptors",
                "io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry");
        return DescribedPredicate.describe("is one of the five designated YAML readers",
                javaClass -> designatedYamlReaders.contains(javaClass.getFullName()));
    }

    private static DescribedPredicate<JavaMethodCall> callsAMapperWriteMethod() {
        return DescribedPredicate.describe("call ObjectMapper#write* or ObjectMapper#writer*", call ->
                call.getTargetOwner().isAssignableTo(ObjectMapper.class)
                        && (call.getTarget().getName().startsWith("write")
                        || call.getTarget().getName().startsWith("writer")));
    }

    /**
     * Dependencies point inward. Core must not know what is built on top of it.
     * {@code audit} and {@code oversight} live in {@code data-prism-core} but
     * sit outside the {@code ..core..} package, so they are named as subjects
     * explicitly and held to the same direction. No allow-list: a scan of the
     * bytecode at the base commit found no violations.
     */
    private static final ArchRule CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that().resideInAnyPackage("..dataprism.core..", "..dataprism.audit..", "..dataprism.oversight..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..dataprism.mcp..", "..dataprism.orchestration..",
                    "..dataprism.example..", "..dataprism.pseudonymisation..");

    @Test
    void coreDoesNotDependOnOuterLayers() {
        CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS.check(CLASSES);
    }

    @Test
    void outerLayerRuleCatchesAuditDependingOnMcp() {
        assertViolationNaming(AuditDependsOnMcpFixture.class);
    }

    @Test
    void outerLayerRuleCatchesOversightDependingOnMcp() {
        assertViolationNaming(OversightDependsOnMcpFixture.class);
    }

    /**
     * Asserts on the violation itself, not on {@code check} throwing: a rule
     * that matched no subject also fails {@code check}, which would pass these
     * tests even with the package dropped from the subjects.
     */
    private static void assertViolationNaming(Class<?> fixtureClass) {
        EvaluationResult result = CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS
                .evaluate(new ClassFileImporter().importClasses(fixtureClass));
        org.assertj.core.api.Assertions.assertThat(result.hasViolation()).isTrue();
        org.assertj.core.api.Assertions.assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains(fixtureClass.getName())
                .contains("dataprism.mcp.CorrelationRequirement");
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
     * Spring Security is for turning a verified JWT into an {@code
     * AuthenticatedCaller} at an HTTP resource server's edge. Nothing else —
     * least of all {@code data-prism-security}, which is meant to work the
     * same way regardless of which web framework, or none, sits in front of
     * it — may depend on it.
     *
     * <p>Two packages are exempt because there are two such edges: {@code
     * ..dataprism.example..} is the embedded-starter demonstration, and
     * {@code ..dataprism.server..} gained its own OAuth2 resource server in
     * task 17. Both terminate an HTTP request into the rest of the system;
     * neither is a place source data or transport output passes through, so
     * neither can turn Spring Security into a route around the privacy
     * engine. That data-prism-server's exemption stays no wider than the
     * edge itself is not this rule's job to police — {@code
     * ServerArchitectureTest} in that module already enforces that nothing
     * else inside {@code data-prism-server} depends on Spring Security, so
     * the narrower guarantee this rule relies on is enforced right next to
     * the code it constrains.
     *
     * <p>{@link JwtDecoderSupport} and {@link JwtCallerContextExtractor} are
     * exempt by fully qualified name rather than by their package: task 26
     * collapsed the two edges' byte-for-byte duplicated decoder construction
     * and caller extraction into these two classes in {@code
     * ..dataprism.spring.boot..}, which also carries {@code
     * DataPrismProperties} and other auto-configuration classes that must
     * stay usable without a resource server on the classpath. Exempting the
     * package would let any future class there depend on Spring Security
     * unnoticed; naming these two keeps the exemption as narrow as what
     * actually needs it.
     */
    private static final ArchRule ONLY_THE_EXAMPLE_DEPENDS_ON_SPRING_SECURITY = noClasses()
            .that(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(
                    JwtDecoderSupport.class, JwtCallerContextExtractor.class)))
            .and().resideOutsideOfPackages("..dataprism.example..", "..dataprism.server..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
            .allowEmptyShould(true);

    @Test
    void onlyTheExampleDependsOnSpringSecurity() {
        ONLY_THE_EXAMPLE_DEPENDS_ON_SPRING_SECURITY.check(CLASSES);
    }

    /**
     * Re-identification is never an MCP tool (docs/design-review.md section
     * B1). Nothing on the tool, orchestration or connector side may depend on
     * the module that undoes a pseudonym.
     */
    private static final ArchRule NO_TOOL_SIDE_DEPENDS_ON_REIDENTIFICATION = noClasses()
            .that().resideInAnyPackage("..mcp..", "..orchestration..", "..connectors..")
            .should().dependOnClassesThat().resideInAPackage("..reidentification..")
            .allowEmptyShould(true);

    @Test
    void noToolSideClassDependsOnReidentification() {
        NO_TOOL_SIDE_DEPENDS_ON_REIDENTIFICATION.check(CLASSES);
    }

    /** The reverse lookup has exactly one caller: the audited re-identification service. */
    private static final ArchRule ONLY_REIDENTIFICATION_CALLS_SUBJECT_FOR = noClasses()
            .that().resideOutsideOfPackage("..reidentification..")
            .should().accessTargetWhere(new DescribedPredicate<JavaAccess<?>>(
                    "is ScopeIdentityIndex.subjectFor (call or method reference)") {
                @Override
                public boolean test(JavaAccess<?> access) {
                    return access.getTarget().getOwner().isEquivalentTo(ScopeIdentityIndex.class)
                            && access.getTarget().getName().equals("subjectFor");
                }
            })
            .allowEmptyShould(true);

    @Test
    void onlyReidentificationCallsSubjectFor() {
        ONLY_REIDENTIFICATION_CALLS_SUBJECT_FOR.check(CLASSES);
    }

    @Test
    void subjectForRuleCatchesMethodReferences() {
        JavaClasses fixture = new ClassFileImporter().importClasses(SubjectForMethodReferenceFixture.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ONLY_REIDENTIFICATION_CALLS_SUBJECT_FOR.check(fixture))
                .isInstanceOf(AssertionError.class);
    }

    /**
     * The {@code core} root package is empty. Every type lives in a named
     * subpackage ({@code spi}, {@code model}, {@code engine}, {@code refusal},
     * {@code limits}, {@code metrics}, or the pre-existing {@code policy},
     * {@code correlation} and {@code descriptor}). The package is matched
     * exactly, not as {@code ..core..}, so the subpackages are not flagged.
     */
    private static final ArchRule CORE_ROOT_PACKAGE_IS_EMPTY = noClasses()
            .should().resideInAPackage("io.github.aindriub.dataprism.core");

    @Test
    void coreRootPackageIsEmpty() {
        CORE_ROOT_PACKAGE_IS_EMPTY.check(CLASSES);
    }

    /** The extension SPI is a contract, not an implementation: it never reaches into the engine. */
    private static final ArchRule SPI_DOES_NOT_DEPEND_ON_ENGINE = noClasses()
            .that().resideInAPackage("io.github.aindriub.dataprism.core.spi..")
            .should().dependOnClassesThat().resideInAPackage("io.github.aindriub.dataprism.core.engine..");

    @Test
    void spiDoesNotDependOnEngine() {
        SPI_DOES_NOT_DEPEND_ON_ENGINE.check(CLASSES);
    }

    /** The {@code core} subpackages form a directed acyclic graph. */
    private static final ArchRule CORE_SUBPACKAGES_ARE_FREE_OF_CYCLES = slices()
            .matching("..dataprism.core.(*)..")
            .should().beFreeOfCycles();

    @Test
    void coreSubpackagesAreFreeOfCycles() {
        CORE_SUBPACKAGES_ARE_FREE_OF_CYCLES.check(CLASSES);
    }

    private static final String AUDIT = "io.github.aindriub.dataprism.audit";
    private static final String[] AUDIT_SUBPACKAGES = {
        AUDIT + ".format..", AUDIT + ".sink..", AUDIT + ".checkpoint..", AUDIT + ".retention..", AUDIT + ".verify.."};

    /** The {@code audit} contract package (exactly, not {@code ..audit..}) never reaches into its subpackages. */
    private static final ArchRule AUDIT_CONTRACT_DOES_NOT_DEPEND_ON_SUBPACKAGES = noClasses()
            .that().resideInAPackage(AUDIT)
            .should().dependOnClassesThat().resideInAnyPackage(AUDIT_SUBPACKAGES);

    @Test
    void auditContractDoesNotDependOnItsSubpackages() {
        AUDIT_CONTRACT_DOES_NOT_DEPEND_ON_SUBPACKAGES.check(CLASSES);
    }

    /** Writers of the record never depend on the offline verifier. */
    private static final ArchRule AUDIT_WRITERS_DO_NOT_DEPEND_ON_VERIFY = noClasses()
            .that().resideInAnyPackage(AUDIT + ".format..", AUDIT + ".sink..", AUDIT + ".checkpoint..")
            .should().dependOnClassesThat().resideInAPackage(AUDIT + ".verify..");

    @Test
    void auditWritersDoNotDependOnVerify() {
        AUDIT_WRITERS_DO_NOT_DEPEND_ON_VERIFY.check(CLASSES);
    }

    /**
     * Retention replays the chain with the verifier before it deletes a segment, so that one
     * edge cannot be removed by placement. It is allow-listed at class level: only
     * {@code AuditRetention} (and its nested types), only {@code AuditChainVerifier} (and its
     * nested types) -- not the CLI, and not any other retention class.
     */
    private static final DescribedPredicate<JavaClass> AUDIT_RETENTION_CORE = DescribedPredicate.describe(
            "AuditRetention or a type nested in it",
            c -> c.getName().equals(AUDIT + ".retention.AuditRetention")
                    || c.getName().startsWith(AUDIT + ".retention.AuditRetention$"));

    private static final DescribedPredicate<JavaClass> AUDIT_CHAIN_VERIFIER_CORE = DescribedPredicate.describe(
            "AuditChainVerifier or a type nested in it",
            c -> c.getName().equals(AUDIT + ".verify.AuditChainVerifier")
                    || c.getName().startsWith(AUDIT + ".verify.AuditChainVerifier$"));

    private static final ArchRule AUDIT_RETENTION_VERIFY_EDGE_IS_ALLOW_LISTED = noClasses()
            .that().resideInAPackage(AUDIT + ".retention..").and(DescribedPredicate.not(AUDIT_RETENTION_CORE))
            .should().dependOnClassesThat().resideInAPackage(AUDIT + ".verify..");

    private static final ArchRule AUDIT_RETENTION_USES_ONLY_THE_VERIFIER = noClasses()
            .that(AUDIT_RETENTION_CORE)
            .should().dependOnClassesThat(
                    DescribedPredicate.and(JavaClass.Predicates.resideInAPackage(AUDIT + ".verify.."),
                            DescribedPredicate.not(AUDIT_CHAIN_VERIFIER_CORE)));

    @Test
    void auditRetentionDependsOnVerifyOnlyThroughTheAllowList() {
        AUDIT_RETENTION_VERIFY_EDGE_IS_ALLOW_LISTED.check(CLASSES);
        AUDIT_RETENTION_USES_ONLY_THE_VERIFIER.check(CLASSES);
    }

    /** The {@code audit} subpackages form a directed acyclic graph. */
    private static final ArchRule AUDIT_SUBPACKAGES_ARE_FREE_OF_CYCLES = slices()
            .matching("..dataprism.audit.(*)..")
            .should().beFreeOfCycles();

    @Test
    void auditSubpackagesAreFreeOfCycles() {
        AUDIT_SUBPACKAGES_ARE_FREE_OF_CYCLES.check(CLASSES);
    }
}
