package io.github.aindriub.dataprism.server;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ServerArchitectureTest {
    private static final String ROOT = "io.github.aindriub.dataprism";

    @Test
    void springSecurityRemainsAtTheDistributionEdge() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        // task 26 moved the decoder construction and caller extraction that
        // used to live in this module into data-prism-spring-boot-autoconfigure,
        // where they're shared with the example. Both are on this module's
        // classpath and so appear in `classes` above; they legitimately depend
        // on Spring Security and are allowlisted here by name, the same way
        // ServerSecurityConfiguration is.
        noClasses()
                .that().doNotHaveFullyQualifiedName(
                        "io.github.aindriub.dataprism.server.ServerSecurityConfiguration")
                .and().doNotHaveFullyQualifiedName(
                        "io.github.aindriub.dataprism.spring.boot.JwtDecoderSupport")
                .and().doNotHaveFullyQualifiedName(
                        "io.github.aindriub.dataprism.spring.boot.JwtCallerContextExtractor")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.security..")
                .check(classes);
    }

    @Test
    void serverHasNoDependencyOnTheExample() {
        var classes = new ClassFileImporter().importPackages(DataPrismServerApplication.class.getPackageName());
        noClasses().should().dependOnClassesThat().resideInAnyPackage("..example..")
                .check(classes);
    }
}
