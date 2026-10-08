package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what {@link DataPrismAutoConfiguration} registers, independently of which class declares
 * each {@code @Bean} method, so splitting or moving configuration classes cannot change it.
 *
 * <p>Two views. The static one lists every {@code @Bean} method reachable from the auto-configuration
 * (name, declared return type, whether static, every {@code @Conditional*}/{@code @Primary}/
 * {@code @DependsOn} annotation, the declaring class's own {@code @Conditional*} annotations, and the
 * {@link PrivacyExtensionPoints} row), never the declaring
 * class. The runtime one lists, per scenario, the sorted {@code (bean name, declared type)} pairs of
 * a running context, leaving out configuration-class beans, whose names carry their class name.
 * The expected lists are checked in under {@code src/test/resources/bean-inventory}; regenerate with
 * {@code -Ddataprism.inventory.write=true} only for a deliberate change to the bean set.
 */
class AutoConfiguredBeanInventoryTest {

    private static final boolean WRITE = Boolean.getBoolean("dataprism.inventory.write");
    private static final String DIRECTORY = "bean-inventory/";

    @AfterEach
    void shutDownEveryClusterMemberThisTestStarted() {
        Hazelcast.shutdownAll();
    }

    // ---- static view ---------------------------------------------------------------------------

    @Test
    void the_bean_method_set_with_conditions_and_classifications_is_unchanged() throws IOException {
        Set<Class<?>> classes = new LinkedHashSet<>();
        collectClasses(DataPrismAutoConfiguration.class, classes);
        List<String> lines = new ArrayList<>();
        for (Class<?> declaring : classes) {
            for (Method method : declaring.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Bean.class)) {
                    continue;
                }
                List<String> annotations = new ArrayList<>();
                for (Annotation annotation : method.getDeclaredAnnotations()) {
                    String name = annotation.annotationType().getSimpleName();
                    if (name.startsWith("Conditional") || name.equals("Primary") || name.equals("DependsOn")
                            || name.equals("Bean")) {
                        annotations.add(annotation.toString());
                    }
                }
                annotations.sort(null);
                // The declaring class's own conditions gate every bean on it, so they are part of the bean's.
                List<String> classConditions = new ArrayList<>();
                for (Annotation annotation : declaring.getDeclaredAnnotations()) {
                    if (annotation.annotationType().getSimpleName().startsWith("Conditional")) {
                        classConditions.add(annotation.toString());
                    }
                }
                classConditions.sort(null);
                lines.add(method.getName() + " | " + method.getGenericReturnType().getTypeName()
                        + (Modifier.isStatic(method.getModifiers()) ? " | static" : " | instance")
                        + " | " + PrivacyExtensionPoints.contractOf(method.getName())
                        + " | " + String.join(" ", annotations)
                        + " | class: " + String.join(" ", classConditions));
            }
        }
        lines.sort(null);
        assertThat(lines).as("number of @Bean methods").hasSize(51);
        compareOrWrite("bean-methods.txt", lines);
    }

    private static void collectClasses(Class<?> root, Set<Class<?>> collected) {
        if (!collected.add(root)) {
            return;
        }
        for (Class<?> nested : root.getDeclaredClasses()) {
            collectClasses(nested, collected);
        }
        Import imported = root.getAnnotation(Import.class);
        if (imported != null) {
            for (Class<?> importedClass : imported.value()) {
                collectClasses(importedClass, collected);
            }
        }
    }

    // ---- runtime view --------------------------------------------------------------------------

    private static WebApplicationContextRunner runner(String topology, Class<?> integrations, String... extra) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(integrations)
                .withPropertyValues(OversightConfigurationTest.base(topology))
                .withPropertyValues("embedded".equals(topology)
                        ? ClusterConfigurationTest.singleMember() : new String[0])
                .withPropertyValues(extra);
    }

    private static final String[] OPERATOR = {
            "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
            "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=dataprism.operate"};
    private static final String[] INDEX = {
            "dataprism.hazelcast.reidentification-enabled=true",
            "dataprism.hazelcast.reidentification-controls-reference=REVIEWED_REIDENTIFICATION_CONTROLS"};
    private static final String[] REIDENTIFICATION = {
            "dataprism.reidentification.enabled=true", "dataprism.reidentification.purposes[0]=fraud-review",
            "dataprism.reidentification.roles.requester[0]=REQUEST",
            "dataprism.reidentification.roles.approver[0]=APPROVE"};

    private void scenario(String file, WebApplicationContextRunner runner) throws IOException {
        List<String>[] inventory = new List[1];
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            inventory[0] = inventoryOf(context);
        });
        compareOrWrite(file, inventory[0]);
    }

    @Test
    void default_scenario() throws IOException {
        scenario("default.txt", runner("single-node", DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class));
    }

    @Test
    void slf4j_audit_sink_scenario() throws IOException {
        scenario("slf4j-sink.txt", runner("single-node",
                DataPrismAutoConfigurationTest.ReviewedHttpIntegrationsWithoutAudit.class,
                "dataprism.audit.sink=slf4j"));
    }

    @Test
    void pass_through_identity_resolver_scenario() throws IOException {
        scenario("pass-through-identity.txt", runner("single-node",
                DataPrismAutoConfigurationTest.IntegrationsWithoutIdentity.class,
                "dataprism.identity.resolver=pass-through"));
    }

    @Test
    void hash_chained_directory_with_checkpoint_scenario(@TempDir Path dir) throws IOException {
        scenario("hash-chained.txt", runner("single-node", AuditRetentionConfigurationTest.Integrations.class,
                "dataprism.audit.sink=hash-chained",
                "dataprism.audit.directory=" + dir.resolve("native"),
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp.jsonl")));
    }

    @Test
    void hash_chained_with_json_projection_scenario(@TempDir Path dir) throws IOException {
        scenario("hash-chained-json.txt", runner("single-node", AuditRetentionConfigurationTest.Integrations.class,
                "dataprism.audit.sink=hash-chained",
                "dataprism.audit.directory=" + dir.resolve("native"),
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp.jsonl"),
                "dataprism.audit.output.json-directory=" + dir.resolve("json")));
    }

    @Test
    void cluster_scenario() throws IOException {
        scenario("cluster.txt", runner("embedded", DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class));
    }

    @Test
    void reidentification_scenario() throws IOException {
        List<String> properties = new ArrayList<>();
        properties.addAll(Arrays.asList(INDEX));
        properties.addAll(Arrays.asList(REIDENTIFICATION));
        properties.addAll(Arrays.asList(OPERATOR));
        scenario("reidentification.txt", runner("embedded",
                DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class, properties.toArray(String[]::new)));
    }

    @Test
    void oversight_scenario() throws IOException {
        List<String> properties = new ArrayList<>(Arrays.asList(OPERATOR));
        properties.add("dataprism.oversight.approval-required-tools[0]=get_entity_context");
        properties.add("dataprism.oversight.caller-rate-limit.requests=10");
        scenario("oversight.txt", runner("single-node",
                DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class, properties.toArray(String[]::new)));
    }

    private static List<String> inventoryOf(AssertableWebApplicationContext context) {
        ConfigurableListableBeanFactory factory = context.getSourceApplicationContext().getBeanFactory();
        Set<String> lines = new TreeSet<>();
        for (String name : factory.getBeanDefinitionNames()) {
            if (name.startsWith("org.springframework.") || name.startsWith("spring.")) {
                continue;
            }
            Class<?> type = factory.getType(name, false);
            if (type != null && AnnotatedElementUtils.hasAnnotation(type, Configuration.class)) {
                continue;
            }
            String declared = type == null ? "?" : type.getName();
            if (factory.getMergedBeanDefinition(name) instanceof RootBeanDefinition root
                    && root.getResolvedFactoryMethod() != null) {
                declared = root.getResolvedFactoryMethod().getGenericReturnType().getTypeName();
            }
            lines.add(name + " | " + declared);
        }
        return new ArrayList<>(lines);
    }

    // ---- checked-in lists ----------------------------------------------------------------------

    private static void compareOrWrite(String file, List<String> actual) throws IOException {
        if (WRITE) {
            Path target = Path.of("src/test/resources", DIRECTORY, file);
            Files.createDirectories(target.getParent());
            Files.write(target, actual, StandardCharsets.UTF_8);
        }
        try (InputStream in = AutoConfiguredBeanInventoryTest.class.getClassLoader()
                .getResourceAsStream(DIRECTORY + file)) {
            assertThat(in).as("checked-in list %s", file).isNotNull();
            List<String> expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertThat(actual).as("bean inventory %s", file).containsExactlyElementsOf(expected);
        }
    }
}
