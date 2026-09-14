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
        noClasses()
                .that().doNotHaveFullyQualifiedName(
                        "io.github.aindriub.dataprism.server.ServerSecurityConfiguration")
                .and().doNotHaveFullyQualifiedName(
                        "io.github.aindriub.dataprism.server.JwtCallerContextExtractor")
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
