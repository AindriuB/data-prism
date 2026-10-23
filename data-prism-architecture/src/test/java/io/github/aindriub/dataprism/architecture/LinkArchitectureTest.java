package io.github.aindriub.dataprism.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.link.SplitPackageFixture;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.hazelcast.archfixture.HzLimiterOpensTokens;
import io.github.aindriub.dataprism.hazelcast.archfixture.LinkLimiterFloorFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
 *   <li>(Task 269) The limiter, subject, opener and gate depend directly on neither {@code LinkKeys} nor
 *       {@code SecretKeyProvider}.
 *   <li>(Task 269) Nothing outside {@code core.link} calls or references {@code LinkCodec.open} or the limiter's
 *       two methods, except a Hazelcast class that itself implements the limiter.
 *   <li>(Task 269) No class in this repository outside {@code core.link} and {@code dataprism.hazelcast}
 *       implements {@code LinkAttemptLimiter}.
 *   <li>(Task 269) Every class in {@code core.link} is loaded from {@code data-prism-core}'s main classes.
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
                c -> !inPackageOrBelow(c.getPackageName(), "io.github.aindriub.dataprism.core.link")
                        && !inPackageOrBelow(c.getPackageName(), "io.github.aindriub.dataprism.hazelcast.seal"));
    }

    /** The package itself or a sub-package: {@code core.linkx} is not in {@code core.link}. */
    static boolean inPackageOrBelow(String packageName, String root) {
        return packageName.equals(root) || packageName.startsWith(root + ".");
    }

    private static ArchRule noCipherOutside(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().accessTargetWhere(new DescribedPredicate<JavaAccess<?>>("is a call to Cipher.getInstance") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        return (access instanceof JavaMethodCall || access instanceof JavaMethodReference)
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

    // ---- task 269 rule factories ----

    private static final String LINK_PACKAGE = "io.github.aindriub.dataprism.core.link";
    private static final String HAZELCAST_PACKAGE = "io.github.aindriub.dataprism.hazelcast";
    private static final List<String> FLOOR_CLASSES = List.of("LinkAttemptLimiter", "InMemoryLinkAttemptLimiter",
            "AttemptSubject", "LinkOpener", "LinkAttemptGate");

    /** The five classes and their nested classes. */
    private static DescribedPredicate<JavaClass> limiterFloorClasses() {
        return DescribedPredicate.describe("the limiter, subject, opener and gate classes",
                c -> c.getPackageName().equals(LINK_PACKAGE) && FLOOR_CLASSES.stream()
                        .anyMatch(name -> c.getSimpleName().equals(name)
                                || c.getName().startsWith(LINK_PACKAGE + "." + name + "$")));
    }

    private static ArchRule limiterFloorHasNoKeyDependency(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().dependOnClassesThat().areAssignableTo(LinkKeys.class)
                .orShould().dependOnClassesThat().areAssignableTo(SecretKeyProvider.class)
                .because("a limiter keys itself under its own non-rotating key, never under a link or provider key")
                .allowEmptyShould(true);
    }

    /**
     * A call or method reference to {@code LinkCodec.open}, or to the limiter's two methods. A Hazelcast
     * implementation of the limiter is exempt from the limiter half only: it may call and reference its own
     * {@code lockState} and {@code recordFailure}, and it is still forbidden to open a token.
     */
    private static boolean isLinkOpenOrLimiterCall(JavaAccess<?> access) {
        if (!(access instanceof JavaMethodCall || access instanceof JavaMethodReference)) {
            return false;
        }
        JavaClass owner = access.getTarget().getOwner();
        String name = access.getTarget().getName();
        boolean opensToken = owner.isAssignableTo(LinkCodec.class) && name.equals("open");
        boolean usesLimiter = owner.isAssignableTo(LinkAttemptLimiter.class)
                && (name.equals("lockState") || name.equals("recordFailure"))
                && !isHazelcastLimiter(access.getOriginOwner());
        return opensToken || usesLimiter;
    }

    private static boolean isHazelcastLimiter(JavaClass c) {
        return inPackageOrBelow(c.getPackageName(), HAZELCAST_PACKAGE) && c.isAssignableTo(LinkAttemptLimiter.class);
    }

    private static DescribedPredicate<JavaClass> outsideLink() {
        return DescribedPredicate.describe("outside core.link",
                c -> !inPackageOrBelow(c.getPackageName(), LINK_PACKAGE));
    }

    private static ArchRule noBypass(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().accessTargetWhere(new DescribedPredicate<JavaAccess<?>>(
                        "is a call or method reference to LinkCodec.open or the limiter's lockState or recordFailure") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        return isLinkOpenOrLimiterCall(access);
                    }
                })
                .because("a token is opened only through LinkAttemptGate, which owns the lock and the counting")
                .allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaClass> outsideLinkAndHazelcast() {
        return DescribedPredicate.describe("outside core.link and dataprism.hazelcast",
                c -> !inPackageOrBelow(c.getPackageName(), LINK_PACKAGE)
                        && !inPackageOrBelow(c.getPackageName(), HAZELCAST_PACKAGE));
    }

    private static ArchRule onlySanctionedLimiters(DescribedPredicate<JavaClass> origin) {
        return noClasses().that(origin)
                .should().beAssignableTo(LinkAttemptLimiter.class)
                .because("no class in this repository may ship a no-op limiter")
                .allowEmptyShould(true);
    }

    private static ArchRule linkIsOnlyLoadedFrom(Path coreMainClasses) {
        return classes().that(inLink()).should(new ArchCondition<JavaClass>(
                "be loaded from " + coreMainClasses.getFileName() + " of data-prism-core") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean fromCore = javaClass.getSource()
                        .map(source -> "file".equals(source.getUri().getScheme())
                                && Path.of(source.getUri()).startsWith(coreMainClasses))
                        .orElse(false);
                events.add(new SimpleConditionEvent(javaClass, fromCore,
                        javaClass.getName() + (fromCore ? " is in core's main classes"
                                : " is not loaded from data-prism-core's main classes")));
            }
        }).because("core.link lives only in data-prism-core, so a split package cannot hide a second codec")
                .allowEmptyShould(true);
    }

    private static Path coreMainClasses() throws IOException {
        Path root = ModuleGraph.repositoryRoot();
        return root.resolve("data-prism-core").resolve("target").resolve("classes").toAbsolutePath().normalize();
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

    @Test
    void theLimiterFloorHasNoKeyDependency() {
        assertThat(CLASSES.stream().filter(limiterFloorClasses()).count()).isGreaterThanOrEqualTo(5);
        limiterFloorHasNoKeyDependency(limiterFloorClasses()).check(CLASSES);
    }

    @Test
    void nothingOutsideLinkBypassesTheGate() {
        noBypass(outsideLink()).check(CLASSES);
    }

    @Test
    void onlyCoreLinkAndHazelcastImplementTheLimiter() {
        onlySanctionedLimiters(outsideLinkAndHazelcast()).check(CLASSES);
    }

    @Test
    void coreLinkIsLoadedOnlyFromDataPrismCore() throws IOException {
        linkIsOnlyLoadedFrom(coreMainClasses()).check(CLASSES);
    }

    // ---- fixtures: each rule must be able to fail ----

    static final class FixtureCreatesACipher {
        Object create() throws GeneralSecurityException {
            return Cipher.getInstance("AES/GCM/NoPadding");
        }
    }

    interface CipherFactory {
        Object create(String transformation) throws GeneralSecurityException;
    }

    static final class FixtureCreatesACipherByReference {
        CipherFactory factory() {
            return Cipher::getInstance;
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
    void cipherRuleFailsOnACipherCreatedByMethodReference() {
        EvaluationResult result = evaluate(noCipherOutside(named(FixtureCreatesACipherByReference.class)),
                FixtureCreatesACipherByReference.class);
        assertThat(result.hasViolation()).isTrue();
    }

    @Test
    void theSealerPackageMatchIsExact() {
        String link = "io.github.aindriub.dataprism.core.link";
        assertThat(inPackageOrBelow(link, link)).isTrue();
        assertThat(inPackageOrBelow(link + ".sub", link)).isTrue();
        assertThat(inPackageOrBelow(link + "x", link)).isFalse();
        assertThat(inPackageOrBelow(link + "x.sub", link)).isFalse();
        assertThat(inPackageOrBelow("io.github.aindriub.dataprism.hazelcast.sealx",
                "io.github.aindriub.dataprism.hazelcast.seal")).isFalse();
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

    // ---- task 269 fixtures ----

    static final class FixtureLimiterDependsOnLinkKeys {
        Object reach() {
            return LinkKeys.class;
        }
    }

    static final class FixtureLimiterDependsOnAProvider {
        Object reach() {
            return SecretKeyProvider.class;
        }
    }

    static final class FixtureCallsLinkCodecOpen {
        Object call(LinkCodec codec) {
            return codec.open("t", null, null);
        }
    }

    static final class FixtureReferencesLinkCodecOpen {
        Object bind(LinkCodec codec) {
            LinkOpenFunction open = codec::open;
            return open;
        }
    }

    interface LinkOpenFunction {
        Object open(String token, io.github.aindriub.dataprism.core.link.LinkBinding binding,
                java.util.Set<io.github.aindriub.dataprism.core.link.LinkRelation> accepted);
    }

    static final class FixtureCallsLockState {
        Object call(LinkAttemptLimiter limiter) {
            return limiter.lockState(null);
        }
    }

    static final class FixtureCallsRecordFailureOnTheInterface {
        Object call(LinkAttemptLimiter limiter) {
            return limiter.recordFailure(null);
        }
    }

    static final class FixtureCallsRecordFailureOnAnImplementation {
        Object call(io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter limiter) {
            return limiter.recordFailure(null);
        }
    }

    static final class FixtureReferencesLockState {
        Object reference(LinkAttemptLimiter limiter) {
            java.util.function.Function<io.github.aindriub.dataprism.core.link.AttemptSubject, Object> f =
                    limiter::lockState;
            return f;
        }
    }

    static final class FixtureReferencesRecordFailure {
        Object reference(LinkAttemptLimiter limiter) {
            java.util.function.Function<io.github.aindriub.dataprism.core.link.AttemptSubject, Object> f =
                    limiter::recordFailure;
            return f;
        }
    }

    /** Implements the SPI outside the Hazelcast package: the bypass rule and the sanctioned-implementation rule fail. */
    static final class FixtureImplementsTheLimiterOutsideHazelcast implements LinkAttemptLimiter {
        @Override
        public LockState lockState(io.github.aindriub.dataprism.core.link.AttemptSubject subject) {
            return LockState.UNLOCKED;
        }

        @Override
        public Admission recordFailure(io.github.aindriub.dataprism.core.link.AttemptSubject subject) {
            // A call on itself: inside a Hazelcast limiter that is allowed, anywhere else it is a bypass.
            return lockState(subject) == LockState.LOCKED ? Admission.OVER_LIMIT : Admission.WITHIN_LIMIT;
        }
    }

    private static DescribedPredicate<JavaClass> anyOf(Class<?>... fixtures) {
        return DescribedPredicate.describe("one of the fixtures",
                javaClass -> List.of(fixtures).stream().anyMatch(javaClass::isEquivalentTo));
    }

    @Test
    void keyRuleFailsOnADependencyOnLinkKeysOrAProvider() {
        assertThat(evaluate(limiterFloorHasNoKeyDependency(named(FixtureLimiterDependsOnLinkKeys.class)),
                FixtureLimiterDependsOnLinkKeys.class).hasViolation()).isTrue();
        assertThat(evaluate(limiterFloorHasNoKeyDependency(named(FixtureLimiterDependsOnAProvider.class)),
                FixtureLimiterDependsOnAProvider.class).hasViolation()).isTrue();
        assertThat(evaluate(limiterFloorHasNoKeyDependency(named(FixtureNeutral.class)), FixtureNeutral.class)
                .hasViolation()).isFalse();
    }

    @Test
    void bypassRuleFailsOnEachCallAndEachMethodReference() {
        for (Class<?> fixture : List.of(FixtureCallsLinkCodecOpen.class, FixtureReferencesLinkCodecOpen.class,
                FixtureCallsLockState.class, FixtureCallsRecordFailureOnTheInterface.class,
                FixtureCallsRecordFailureOnAnImplementation.class, FixtureReferencesLockState.class,
                FixtureReferencesRecordFailure.class)) {
            assertThat(evaluate(noBypass(outsideLinkAnd(fixture)), fixture).hasViolation())
                    .as(fixture.getSimpleName()).isTrue();
        }
        assertThat(evaluate(noBypass(outsideLinkAnd(FixtureNeutral.class)),
                FixtureNeutral.class).hasViolation()).isFalse();
    }

    private static DescribedPredicate<JavaClass> outsideLinkAnd(Class<?> fixture) {
        return named(fixture).and(outsideLink());
    }

    @Test
    void bypassRuleFailsOnALimiterImplementedOutsideHazelcastAndPassesTheHazelcastFloorFixture() {
        assertThat(evaluate(noBypass(outsideLinkAnd(
                FixtureImplementsTheLimiterOutsideHazelcast.class)),
                FixtureImplementsTheLimiterOutsideHazelcast.class).hasViolation()).isTrue();
        assertThat(evaluate(noBypass(outsideLinkAnd(LinkLimiterFloorFixture.class)),
                LinkLimiterFloorFixture.class).hasViolation()).isFalse();
    }

    @Test
    void aHazelcastLimiterMayNotOpenTokens() {
        assertThat(evaluate(noBypass(outsideLinkAnd(HzLimiterOpensTokens.class)), HzLimiterOpensTokens.class)
                .hasViolation()).isTrue();
    }

    @Test
    void sanctionedImplementationRuleFailsOutsideLinkAndHazelcast() {
        assertThat(evaluate(onlySanctionedLimiters(
                named(FixtureImplementsTheLimiterOutsideHazelcast.class)
                        .and(outsideLinkAndHazelcast())), FixtureImplementsTheLimiterOutsideHazelcast.class)
                .hasViolation()).isTrue();
        assertThat(evaluate(onlySanctionedLimiters(named(LinkLimiterFloorFixture.class)
                .and(outsideLinkAndHazelcast())), LinkLimiterFloorFixture.class).hasViolation()).isFalse();
        assertThat(outsideLinkAndHazelcast().test(new ClassFileImporter().importClass(LinkLimiterFloorFixture.class)))
                .isFalse();
        assertThat(outsideLinkAndHazelcast().test(new ClassFileImporter()
                .importClass(FixtureImplementsTheLimiterOutsideHazelcast.class))).isTrue();
    }

    @Test
    void splitPackageRuleFailsOnACoreLinkClassCompiledElsewhere() throws IOException {
        Path core = coreMainClasses();
        EvaluationResult result = linkIsOnlyLoadedFrom(core)
                .evaluate(new ClassFileImporter().importClasses(SplitPackageFixture.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("SplitPackageFixture");
        // A real core.link class, imported the same way, passes: the rule compares locations, not names.
        JavaClasses fromCore = new ClassFileImporter().importPaths(core);
        assertThat(fromCore.contain(LinkCodec.class.getName())).isTrue();
        assertThat(linkIsOnlyLoadedFrom(core).evaluate(fromCore).hasViolation()).isFalse();
        // The same class read from a jar on the classpath (as under `verify`) is not "in core's main classes".
        assertThat(linkIsOnlyLoadedFrom(core.resolveSibling("elsewhere")).evaluate(fromCore).hasViolation()).isTrue();
    }
}
