package io.github.aindriub.dataprism.server;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ServerArchitectureTest {
    private static final String ROOT = "io.github.aindriub.dataprism";

    @Test
    void springSecurityRemainsAtTheDistributionEdge() {
        var classes = new ClassFileImporter().importPackages(ROOT);
        noClasses().that().resideOutsideOfPackage("..server..")
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
