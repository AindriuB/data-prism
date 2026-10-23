package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 222: the cluster key and the name key are configured by reference, required to join other members,
 * optional (both or neither) on a solo member, and refused where no member is built here.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClusterKeyConfigurationTest {

    private static final String SECURITY = "dataprism.hazelcast.security.";
    /** A marker in every secret-looking string a refusal could leak; none may appear in any message or log line. */
    private static final String LEAK = "distinctive-";

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    /** Resolves like the shared test resolver, with a few references that stand for particular key states. */
    static byte[] material(String id, String reference) {
        return switch (reference) {
            case "REF_UNRESOLVED" -> throw new IllegalStateException(LEAK + "unresolved-reason");
            case "REF_WEAK" -> (LEAK + "weak-1").getBytes(StandardCharsets.UTF_8);
            case "REF_HMAC_TWIN" -> material("v1", "DATAPRISM_HMAC_KEY_REF");
            case "REF_FIXED" -> (LEAK + "fixed-material-0123456789abcdef").getBytes(StandardCharsets.UTF_8);
            default -> (LEAK + reference + ":" + id + ":resolved-key-material").getBytes(StandardCharsets.UTF_8);
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class KeyIntegrations extends DataPrismAutoConfigurationTest.ReviewedHttpIntegrations {
        @Override @Bean HmacKeyReferenceResolver keys() {
            return new HmacKeyReferenceResolver() {
                @Override public byte[] resolve(String keyId, String reference) {
                    return material(keyId, reference);
                }

                @Override public byte[] resolveProviderReference(String keyId, String reference) {
                    return material(keyId, reference);
                }
            };
        }
    }

    private static WebApplicationContextRunner runner(String topology, Map<String, String> properties) {
        List<String> all = new ArrayList<>();
        properties.forEach((k, v) -> all.add(k + "=" + v));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(KeyIntegrations.class)
                .withPropertyValues(OversightConfigurationTest.base(topology))
                .withPropertyValues(all.toArray(String[]::new));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static String everyMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            all.append(current.getMessage()).append('\n');
        }
        return all.toString();
    }

    // ---- the table -----------------------------------------------------------------------------

    private static String join(String mode) {
        return switch (mode) {
            case "tcp-ip" -> "tcp-ip";
            case "kubernetes" -> "kubernetes";
            default -> "none";
        };
    }

    /** A valid configuration for a join mode, with both keys supplied. */
    static Map<String, String> valid(String mode) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dataprism.hazelcast.cluster-name", "keys-" + System.nanoTime());
        p.put("dataprism.hazelcast.join.mode", join(mode));
        switch (mode) {
            case "tcp-ip" -> {
                p.put("dataprism.hazelcast.join.members[0]", "127.0.0.1:5999");
                p.put("dataprism.hazelcast.member.interface", "127.0.0.1");
            }
            case "kubernetes" -> {
                p.put("dataprism.hazelcast.join.kubernetes.namespace", "ns");
                p.put("dataprism.hazelcast.join.kubernetes.service-name", "svc");
                p.put("dataprism.hazelcast.member.interface", "10.0.*.*");
            }
            default -> {
            }
        }
        p.put(SECURITY + "cluster-key.key-id", "cluster-1");
        p.put(SECURITY + "cluster-key.environment-variable", "DATAPRISM_CLUSTER_KEY_REF");
        p.put(SECURITY + "name-key.key-id", "name-1");
        p.put(SECURITY + "name-key.environment-variable", "DATAPRISM_NAME_KEY_REF");
        return p;
    }

    /**
     * One refusal: the code, the edits (a leading {@code -} removes a key; keys are relative to
     * {@code dataprism.hazelcast.security.}), the text its message must carry (a property name), and the modes it
     * applies to. Rows are grouped by code, roughly in the order the refusals are made; the {@code .value} rows come
     * after the presence checks and before resolution, but are listed with the other literal rows.
     */
    record Row(String code, String what, String[] edits, String names, boolean joining, boolean solo) {
        @Override public String toString() {
            return code + " " + what;
        }
    }

    private static Row row(String code, String what, String names, boolean joining, boolean solo, String... edits) {
        return new Row(code, what, edits, names, joining, solo);
    }

    static final List<Row> ROWS = List.of(
            row("MISSING_CLUSTER_KEY", "no cluster key", "cluster-key.key-id", true, true,
                    "-cluster-key.key-id", "-cluster-key.environment-variable"),
            row("MISSING_CLUSTER_KEY", "no name key", "name-key.key-id", true, true,
                    "-name-key.key-id", "-name-key.environment-variable"),
            row("MISSING_CLUSTER_KEY", "no cluster key source", "cluster-key.environment-variable", true, true,
                    "-cluster-key.environment-variable"),
            row("MISSING_CLUSTER_KEY", "both cluster key sources", "cluster-key.environment-variable", true, true,
                    "cluster-key.provider-reference=SOME_REF"),
            row("MISSING_CLUSTER_KEY", "both name key sources", "name-key.environment-variable", true, true,
                    "name-key.provider-reference=SOME_REF"),
            row("MISSING_CLUSTER_KEY", "next without a current key", "cluster-key.key-id", true, true,
                    "-cluster-key.key-id", "-cluster-key.environment-variable", "-name-key.key-id",
                    "-name-key.environment-variable", "cluster-key.next.key-id=cluster-2",
                    "cluster-key.next.environment-variable=DATAPRISM_NEXT_KEY_REF"),
            row("MISSING_CLUSTER_KEY", "previous without a current key", "cluster-key.key-id", true, true,
                    "-cluster-key.key-id", "-cluster-key.environment-variable", "-name-key.key-id",
                    "-name-key.environment-variable", "cluster-key.previous.key-id=cluster-0",
                    "cluster-key.previous.environment-variable=DATAPRISM_PREVIOUS_KEY_REF"),
            row("MISSING_CLUSTER_KEY", "next key id without a source", "cluster-key.next.environment-variable",
                    true, true, "cluster-key.next.key-id=cluster-2"),
            row("MISSING_CLUSTER_KEY", "absence is checked before a literal", "name-key.key-id", true, true,
                    "-name-key.key-id", "-name-key.environment-variable", "cluster-key.value=" + LEAK + "literal"),
            row("LITERAL_SECRET_FORBIDDEN", "cluster key value", "cluster-key.value", true, true,
                    "cluster-key.value=" + LEAK + "literal"),
            row("LITERAL_SECRET_FORBIDDEN", "name key value", "name-key.value", true, true,
                    "name-key.value=" + LEAK + "literal"),
            row("LITERAL_SECRET_FORBIDDEN", "a literal is checked before resolution", "cluster-key.value", true,
                    true, "cluster-key.value=" + LEAK + "literal",
                    "cluster-key.environment-variable=REF_UNRESOLVED"),
            row("CLUSTER_KEY_UNRESOLVED", "current does not resolve", "cluster-key", true, true,
                    "cluster-key.environment-variable=REF_UNRESOLVED"),
            row("CLUSTER_KEY_UNRESOLVED", "next does not resolve", "cluster-key.next", true, true,
                    "cluster-key.next.key-id=cluster-2", "cluster-key.next.environment-variable=REF_UNRESOLVED"),
            row("CLUSTER_KEY_UNRESOLVED", "previous does not resolve", "cluster-key.previous", true, true,
                    "cluster-key.previous.key-id=cluster-0",
                    "cluster-key.previous.provider-reference=REF_UNRESOLVED"),
            row("CLUSTER_KEY_UNRESOLVED", "key id with a dot", "cluster-key.key-id", true, true,
                    "cluster-key.key-id=cluster.1"),
            row("CLUSTER_KEY_UNRESOLVED", "next key id with a dot", "cluster-key.next.key-id", true, true,
                    "cluster-key.next.key-id=cluster.2", "cluster-key.next.environment-variable=DATAPRISM_NEXT_KEY_REF"),
            row("NAME_KEY_UNRESOLVED", "name key id with a dot", "name-key.key-id", true, true,
                    "name-key.key-id=name.1"),
            row("LITERAL_SECRET_FORBIDDEN", "next key value", "cluster-key.next.value", true, true,
                    "cluster-key.next.value=" + LEAK + "literal"),
            row("LITERAL_SECRET_FORBIDDEN", "previous key value", "cluster-key.previous.value", true, true,
                    "cluster-key.previous.value=" + LEAK + "literal"),
            row("NAME_KEY_UNRESOLVED", "name key does not resolve", "name-key", true, true,
                    "name-key.environment-variable=REF_UNRESOLVED"),
            row("CLUSTER_KEY_WEAK", "current under 32 bytes", "cluster-key", true, true,
                    "cluster-key.environment-variable=REF_WEAK"),
            row("CLUSTER_KEY_WEAK", "next under 32 bytes", "cluster-key.next", true, true,
                    "cluster-key.next.key-id=cluster-2", "cluster-key.next.environment-variable=REF_WEAK"),
            row("NAME_KEY_WEAK", "name key under 32 bytes", "name-key", true, true,
                    "name-key.environment-variable=REF_WEAK"),
            row("CLUSTER_KEY_SHARED", "same material as the privacy HMAC key", "cluster-key", true, true,
                    "cluster-key.environment-variable=REF_HMAC_TWIN"),
            row("CLUSTER_KEY_SHARED", "same key id as the privacy HMAC key", "cluster-key", true, true,
                    "cluster-key.key-id=v1"),
            row("CLUSTER_KEY_SHARED", "next has the HMAC key's material", "cluster-key.next", true, true,
                    "cluster-key.next.key-id=cluster-2", "cluster-key.next.environment-variable=REF_HMAC_TWIN"),
            row("NAME_KEY_SHARED", "name key has the HMAC key's material", "name-key", true, true,
                    "name-key.environment-variable=REF_HMAC_TWIN"),
            row("NAME_KEY_SHARED", "name key equals the cluster key", "name-key", true, true,
                    "cluster-key.environment-variable=REF_FIXED", "name-key.environment-variable=REF_FIXED"),
            row("NAME_KEY_SHARED", "name key equals a rotation key", "name-key", true, true,
                    "cluster-key.previous.key-id=cluster-0", "cluster-key.previous.environment-variable=REF_FIXED",
                    "name-key.environment-variable=REF_FIXED"),
            row("NAME_KEY_SHARED", "equal keys are refused before equal ids", "name-key", true, true,
                    "cluster-key.environment-variable=REF_FIXED", "name-key.environment-variable=REF_FIXED",
                    "name-key.key-id=cluster-1"),
            row("DUPLICATE_CLUSTER_KEY_ID", "next repeats current", "cluster-key.next.key-id", true, true,
                    "cluster-key.next.key-id=cluster-1", "cluster-key.next.environment-variable=DATAPRISM_NEXT_KEY_REF"),
            row("DUPLICATE_CLUSTER_KEY_ID", "previous repeats next", "cluster-key.previous.key-id", true, true,
                    "cluster-key.next.key-id=cluster-2", "cluster-key.next.environment-variable=DATAPRISM_NEXT_KEY_REF",
                    "cluster-key.previous.key-id=cluster-2",
                    "cluster-key.previous.environment-variable=DATAPRISM_PREVIOUS_KEY_REF"),
            row("DUPLICATE_CLUSTER_KEY_ID", "name key id repeats a cluster key id", "name-key.key-id", true, true,
                    "name-key.key-id=cluster-1"),
            row("MISSING_MEMBER_INTERFACE", "a joining member names no interface", "member.interface", true, false,
                    "!-dataprism.hazelcast.member.interface"),
            row("UNBOUNDED_MEMBER_INTERFACE", "an interface that matches every address", "member.interface", true,
                    false, "!dataprism.hazelcast.member.interface=*.*.*.*"),
            row("MISSING_CLUSTER_KEY", "keys are checked before the interface", "cluster-key.key-id", true, false,
                    "-cluster-key.key-id", "-cluster-key.environment-variable", "-name-key.key-id",
                    "-name-key.environment-variable", "!-dataprism.hazelcast.member.interface"));

    static Stream<Object[]> joiningRows() {
        List<Object[]> out = new ArrayList<>();
        for (String mode : new String[] {"tcp-ip", "kubernetes"}) {
            for (Row r : ROWS) {
                if (r.joining()) {
                    out.add(new Object[] {mode, r});
                }
            }
        }
        return out.stream();
    }

    static Stream<Object[]> soloRows() {
        List<Object[]> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (r.solo()) {
                out.add(new Object[] {"none", r});
            }
        }
        return out.stream();
    }

    private static Map<String, String> edited(String mode, Row row) {
        Map<String, String> p = valid(mode);
        for (String edit : row.edits()) {
            boolean absolute = edit.startsWith("!");
            String e = absolute ? edit.substring(1) : edit;
            boolean remove = e.startsWith("-");
            String body = remove ? e.substring(1) : e;
            if (remove) {
                p.remove(absolute ? body : SECURITY + body);
            } else {
                int at = body.indexOf('=');
                p.put(absolute ? body.substring(0, at) : SECURITY + body.substring(0, at), body.substring(at + 1));
            }
        }
        return p;
    }

    private static void assertRefusedWith(AssertableWebApplicationContext result, Row row) {
        assertThat(result).hasFailed();
        String root = rootMessage(result.getStartupFailure());
        assertThat(root).startsWith(row.code() + ":");
        assertThat(root).contains(row.names());
        assertThat(everyMessage(result.getStartupFailure())).doesNotContain(LEAK);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("joiningRows")
    void a_joining_member_is_refused_with_the_registered_code(String mode, Row row) {
        runner("embedded", edited(mode, row)).run(result -> assertRefusedWith(result, row));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("soloRows")
    void a_solo_member_that_is_given_keys_is_refused_with_the_same_codes(String mode, Row row) {
        runner("embedded", edited(mode, row)).run(result -> assertRefusedWith(result, row));
    }

    @Test
    void no_refusal_puts_key_material_in_a_message_or_a_log_line(CapturedOutput output) {
        for (String mode : new String[] {"tcp-ip", "none"}) {
            for (Row row : ROWS) {
                if (mode.equals("none") && !row.solo()) {
                    continue;
                }
                runner("embedded", edited(mode, row)).run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(everyMessage(result.getStartupFailure())).doesNotContain(LEAK);
                });
            }
        }
        assertThat(output.getAll()).doesNotContain(LEAK);
    }

    // ---- the order of refusals ------------------------------------------------------------------

    /**
     * The refusals in the order the code makes them, one stage each: property checks (presence, literal values, the
     * interface), then the key ids, then each key in turn (current, next, previous, name) for resolution and
     * strength, then the cluster keys against the privacy HMAC key and the name key against it, the name key
     * against the cluster keys, and last the key ids against each other. The stage that checks the HMAC key itself
     * is pinned in {@link ClusterKeyResolutionTest}, because the context checks that key earlier.
     * {@code docs/configuration.md} lists this order.
     */
    static final List<Row> ORDER = List.of(
            row("MISSING_CLUSTER_KEY", "presence", "cluster-key.environment-variable", true, false,
                    "-cluster-key.environment-variable"),
            row("LITERAL_SECRET_FORBIDDEN", "literal value", "cluster-key.value", true, false,
                    "cluster-key.value=" + LEAK + "literal"),
            row("MISSING_MEMBER_INTERFACE", "interface", "member.interface", true, false,
                    "!-dataprism.hazelcast.member.interface"),
            row("CLUSTER_KEY_UNRESOLVED", "current key id has a dot", "cluster-key.key-id", true, false,
                    "cluster-key.key-id=cluster.1"),
            row("CLUSTER_KEY_UNRESOLVED", "next key id has a dot", "cluster-key.next.key-id", true, false,
                    "cluster-key.next.key-id=cluster.2",
                    "cluster-key.next.environment-variable=DATAPRISM_NEXT_KEY_REF"),
            row("CLUSTER_KEY_UNRESOLVED", "previous key id has a dot", "cluster-key.previous.key-id", true, false,
                    "cluster-key.previous.key-id=cluster.0",
                    "cluster-key.previous.environment-variable=DATAPRISM_PREVIOUS_KEY_REF"),
            row("NAME_KEY_UNRESOLVED", "name key id has a dot", "name-key.key-id", true, false,
                    "name-key.key-id=name.1"),
            row("CLUSTER_KEY_WEAK", "current key is weak", "cluster-key", true, false,
                    "cluster-key.environment-variable=REF_WEAK"),
            row("CLUSTER_KEY_UNRESOLVED", "next key does not resolve", "cluster-key.next", true, false,
                    "cluster-key.next.key-id=cluster-2", "cluster-key.next.environment-variable=REF_UNRESOLVED"),
            row("CLUSTER_KEY_WEAK", "previous key is weak", "cluster-key.previous", true, false,
                    "cluster-key.previous.key-id=cluster-0", "cluster-key.previous.environment-variable=REF_WEAK"),
            row("NAME_KEY_UNRESOLVED", "name key does not resolve", "name-key", true, false,
                    "name-key.environment-variable=REF_UNRESOLVED"),
            row("CLUSTER_KEY_SHARED", "next key is the HMAC key", "cluster-key.next", true, false,
                    "cluster-key.next.key-id=cluster-2", "cluster-key.next.environment-variable=REF_HMAC_TWIN"),
            row("NAME_KEY_SHARED", "name key has the HMAC key's id",
                    "name-key must differ from dataprism.privacy.hmac-key", true, false, "name-key.key-id=v1"),
            row("NAME_KEY_SHARED", "name key is a cluster key",
                    "name-key must differ from dataprism.hazelcast.security.cluster-key", true, false,
                    "cluster-key.environment-variable=REF_FIXED", "name-key.environment-variable=REF_FIXED"),
            row("DUPLICATE_CLUSTER_KEY_ID", "name key id repeats a cluster key id", "name-key.key-id", true, false,
                    "name-key.key-id=cluster-1"));

    static Stream<Object[]> adjacentPairs() {
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < ORDER.size(); i++) {
            out.add(new Object[] {ORDER.get(i), ORDER.get(i + 1)});
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}  before  {1}")
    @MethodSource("adjacentPairs")
    void when_two_adjacent_refusals_both_apply_the_earlier_one_is_made(Row first, Row second) {
        Map<String, String> p = edited("tcp-ip", first);
        for (String edit : second.edits()) {
            if (edit.startsWith("!-")) {
                p.remove(edit.substring(2));
            } else if (edit.startsWith("!")) {
                int at = edit.indexOf('=');
                p.put(edit.substring(1, at), edit.substring(at + 1));
            } else if (edit.startsWith("-")) {
                p.remove(SECURITY + edit.substring(1));
            } else {
                int at = edit.indexOf('=');
                p.put(SECURITY + edit.substring(0, at), edit.substring(at + 1));
            }
        }
        runner("embedded", p).run(result -> assertRefusedWith(result, first));
    }

    // ---- a solo member -------------------------------------------------------------------------

    @Test
    void a_solo_member_with_valid_keys_holds_exactly_the_resolved_keys() {
        Map<String, String> p = valid("none");
        p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        p.put(SECURITY + "cluster-key.next.key-id", "cluster-2");
        p.put(SECURITY + "cluster-key.next.provider-reference", "DATAPRISM_NEXT_KEY_REF");
        p.put(SECURITY + "cluster-key.previous.key-id", "cluster-0");
        p.put(SECURITY + "cluster-key.previous.environment-variable", "DATAPRISM_PREVIOUS_KEY_REF");
        runner("embedded", p).run(result -> {
            assertThat(result).hasNotFailed();
            PrivacyCluster.KeyIds ids = result.getBean(PrivacyCluster.class).keyIds().orElseThrow();
            assertThat(ids.current()).isEqualTo("cluster-1");
            assertThat(ids.next()).contains("cluster-2");
            assertThat(ids.previous()).contains("cluster-0");
            assertThat(ids.name()).isEqualTo("name-1");
        });
    }

    @Test
    void a_solo_member_with_no_security_key_starts_and_is_given_no_keys() {
        Map<String, String> p = valid("none");
        p.keySet().removeIf(k -> k.startsWith(SECURITY));
        p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        runner("embedded", p).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(PrivacyCluster.class).keyIds()).isEmpty();
        });
    }

    @Test
    void a_solo_member_with_a_weak_cluster_key_is_refused_as_weak() {
        Map<String, String> p = valid("none");
        p.put(SECURITY + "cluster-key.environment-variable", "REF_WEAK");
        runner("embedded", p).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith("CLUSTER_KEY_WEAK:");
        });
    }

    @Test
    void a_solo_member_with_only_one_of_the_two_keys_is_refused_naming_the_missing_one() {
        Map<String, String> onlyCluster = valid("none");
        onlyCluster.remove(SECURITY + "name-key.key-id");
        onlyCluster.remove(SECURITY + "name-key.environment-variable");
        runner("embedded", onlyCluster).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith("MISSING_CLUSTER_KEY:")
                    .contains(SECURITY + "name-key.key-id");
        });
        Map<String, String> onlyName = valid("none");
        onlyName.remove(SECURITY + "cluster-key.key-id");
        onlyName.remove(SECURITY + "cluster-key.environment-variable");
        runner("embedded", onlyName).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith("MISSING_CLUSTER_KEY:")
                    .contains(SECURITY + "cluster-key.key-id");
        });
    }

    /** The environment names the Compose and Kubernetes files set bind to the same properties as the dotted names. */
    @Test
    void the_environment_names_the_deployment_files_use_bind_through_relaxed_binding() throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("DATAPRISM_HAZELCAST_SECURITY_CLUSTERKEY_KEYID", "cluster-1");
        environment.put("DATAPRISM_HAZELCAST_SECURITY_CLUSTERKEY_ENVIRONMENTVARIABLE",
                "DATAPRISM_CLUSTER_KEY_MATERIAL");
        environment.put("DATAPRISM_HAZELCAST_SECURITY_NAMEKEY_KEYID", "name-1");
        environment.put("DATAPRISM_HAZELCAST_SECURITY_NAMEKEY_ENVIRONMENTVARIABLE", "DATAPRISM_NAME_KEY_MATERIAL");
        for (String file : List.of("../docker/multi-instance/compose.yaml",
                "../docker/multi-instance/kubernetes.yaml")) {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            environment.keySet().forEach(name -> assertThat(text).as(file).contains(name));
        }
        Map<String, String> p = valid("none");
        p.keySet().removeIf(k -> k.startsWith(SECURITY));
        p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        runner("embedded", p).withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment",
                        new LinkedHashMap<String, Object>(environment)))).run(result -> {
            assertThat(result).hasNotFailed();
            PrivacyCluster.KeyIds ids = result.getBean(PrivacyCluster.class).keyIds().orElseThrow();
            assertThat(ids.current()).isEqualTo("cluster-1");
            assertThat(ids.name()).isEqualTo("name-1");
        });
    }

    // ---- a joining member ----------------------------------------------------------------------

    @Test
    void a_tcp_ip_member_with_valid_keys_starts_and_holds_them() {
        Map<String, String> p = valid("tcp-ip");
        int port = ClusterConfigurationTest.freePort();
        p.put("dataprism.hazelcast.join.members[0]", "127.0.0.1:" + port);
        p.put("dataprism.hazelcast.member.port", String.valueOf(port));
        runner("embedded", p).run(result -> {
            assertThat(result).hasNotFailed();
            PrivacyCluster.KeyIds ids = result.getBean(PrivacyCluster.class).keyIds().orElseThrow();
            assertThat(ids.current()).isEqualTo("cluster-1");
            assertThat(ids.name()).isEqualTo("name-1");
        });
    }

    // ---- no member is built here ---------------------------------------------------------------

    @Test
    void single_node_refuses_every_security_key_naming_it() {
        runner("single-node", Map.of(SECURITY + "cluster-key.key-id", "cluster-1",
                SECURITY + "cluster-key.environment-variable", "DATAPRISM_CLUSTER_KEY_REF",
                SECURITY + "name-key.key-id", "name-1",
                SECURITY + "name-key.environment-variable", "DATAPRISM_NAME_KEY_REF")).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith("CLUSTER_SETTINGS_IGNORED:")
                    .contains(SECURITY + "cluster-key.key-id");
        });
        for (String key : new String[] {"cluster-key.environment-variable", "cluster-key.provider-reference",
                "cluster-key.value", "cluster-key.next.value", "cluster-key.previous.value", "cluster-key.next.key-id", "cluster-key.next.environment-variable",
                "cluster-key.next.provider-reference", "cluster-key.previous.key-id",
                "cluster-key.previous.environment-variable", "cluster-key.previous.provider-reference",
                "name-key.key-id", "name-key.environment-variable", "name-key.provider-reference",
                "name-key.value"}) {
            runner("single-node", Map.of(SECURITY + key, "x")).run(result -> {
                assertThat(result).hasFailed();
                assertThat(rootMessage(result.getStartupFailure())).startsWith("CLUSTER_SETTINGS_IGNORED:")
                        .contains(SECURITY + key);
            });
        }
    }

    @Test
    void an_application_supplied_cluster_refuses_the_security_keys_on_any_join_mode() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(SECURITY + "name-key.key-id", "name-1");
        runner("embedded", p).withUserConfiguration(ClusterConfigurationTest.SuppliedCluster.class)
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure())).startsWith("CLUSTER_SETTINGS_IGNORED:")
                            .contains(SECURITY + "name-key.key-id");
                });
    }
}
