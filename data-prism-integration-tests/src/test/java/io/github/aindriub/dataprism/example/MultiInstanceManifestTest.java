package io.github.aindriub.dataprism.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 284: {@code trusted-network: true} in the multi-instance files is an assertion that only Data Prism members
 * can reach the member port. On Kubernetes the NetworkPolicy is what makes it true, and a pod on the host network
 * bypasses it; Compose is a local demonstration whose isolation depends on the engine.
 */
class MultiInstanceManifestTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final String POLICY = "data-prism-cluster-members";
    private static final String DEMO_SENTENCE = "This file is a local demonstration, not a deployment.";
    private static final String ENGINE_SENTENCE = "The isolation assertion that trusted-network: true makes holds "
            + "only on a Linux Docker engine where cross-network routing to the member port has been checked and "
            + "found blocked; on OrbStack or Docker Desktop it does not hold.";

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve("docker/multi-instance").resolve(file));
    }

    private static List<JsonNode> documents(String manifest) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String text : manifest.split("(?m)^---\\s*$")) {
            JsonNode node = YAML.readTree(text);
            if (node != null && node.isObject()) {
                out.add(node);
            }
        }
        return out;
    }

    private static List<String> labelsOf(JsonNode selector) {
        List<String> labels = new ArrayList<>();
        selector.path("matchLabels").properties().forEach(e -> labels.add(e.getKey() + "=" + e.getValue().asString()));
        return labels;
    }

    /** What is wrong with the manifest's member-port protection; empty when it is as the task requires. */
    static List<String> problems(String manifest) throws IOException {
        List<String> problems = new ArrayList<>();
        List<JsonNode> documents = documents(manifest);
        String memberPort = null;
        for (JsonNode doc : documents) {
            if ("Deployment".equals(doc.path("kind").asString())) {
                for (JsonNode container : doc.path("spec").path("template").path("spec").path("containers")) {
                    for (JsonNode env : container.path("env")) {
                        if ("DATAPRISM_HAZELCAST_MEMBER_PORT".equals(env.path("name").asString())) {
                            memberPort = env.path("value").asString();
                        }
                    }
                }
            }
        }
        if (memberPort == null) {
            problems.add("no member port is configured");
        }
        JsonNode policy = documents.stream().filter(d -> "NetworkPolicy".equals(d.path("kind").asString())
                && POLICY.equals(d.path("metadata").path("name").asString())).findFirst().orElse(null);
        if (policy == null) {
            problems.add("the " + POLICY + " NetworkPolicy is missing");
        } else {
            JsonNode spec = policy.path("spec");
            if (!labelsOf(spec.path("podSelector")).equals(List.of("app=data-prism"))) {
                problems.add("the policy selects other pods than app=data-prism");
            }
            if (spec.path("policyTypes").size() != 1 || !"Ingress".equals(spec.path("policyTypes").path(0).asString())) {
                problems.add("the policy is not ingress-only");
            }
            JsonNode ingress = spec.path("ingress");
            if (ingress.size() != 1) {
                problems.add("the policy has " + ingress.size() + " ingress rules, not one");
            } else {
                JsonNode rule = ingress.path(0);
                JsonNode from = rule.path("from");
                boolean onlyDataPrismPods = from.size() == 1 && from.path(0).size() == 1
                        && labelsOf(from.path(0).path("podSelector")).equals(List.of("app=data-prism"));
                if (!onlyDataPrismPods) {
                    problems.add("the policy admits a source other than pods labelled app=data-prism");
                }
                JsonNode ports = rule.path("ports");
                boolean onlyMemberPort = ports.size() == 1 && "TCP".equals(ports.path(0).path("protocol").asString())
                        && memberPort != null && memberPort.equals(ports.path(0).path("port").asString());
                if (!onlyMemberPort) {
                    problems.add("the policy names a port other than the configured member port");
                }
            }
        }
        for (JsonNode doc : documents) {
            JsonNode podSpec = "Pod".equals(doc.path("kind").asString()) ? doc.path("spec")
                    : doc.path("spec").path("template").path("spec");
            if (podSpec.path("hostNetwork").asBoolean(false)) {
                problems.add(doc.path("kind").asString() + " " + doc.path("metadata").path("name").asString()
                        + " sets hostNetwork: true, which bypasses the policy");
            }
        }
        return problems;
    }

    /** Comments joined, so a sentence wrapped over several comment lines still matches. */
    private static String commentText(String file) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : read(file).split("\n")) {
            String trimmed = line.strip();
            int hash = trimmed.indexOf('#');
            if (hash >= 0) {
                text.append(trimmed.substring(hash + 1).strip()).append(' ');
            }
        }
        return text.toString().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("kubernetes.yaml protects the member port, and no pod uses the host network")
    void theShippedManifestIsProtected() throws IOException {
        assertThat(problems(read("kubernetes.yaml"))).isEmpty();
    }

    @Test
    @DisplayName("a manifest without the NetworkPolicy is refused")
    void withoutThePolicyItFails() throws IOException {
        String manifest = read("kubernetes.yaml");
        int start = manifest.indexOf("kind: NetworkPolicy");
        int end = manifest.indexOf("# --8<-- [end:k8s-networkpolicy]");
        assertThat(start).isPositive();
        String without = manifest.substring(0, manifest.lastIndexOf("---", start)) + manifest.substring(end);
        assertThat(problems(without)).anyMatch(p -> p.contains("is missing"));
    }

    @Test
    @DisplayName("a policy that selects other pods, admits another source or names another port is refused")
    void aWeakenedPolicyFails() throws IOException {
        String manifest = read("kubernetes.yaml");
        String otherPods = manifest.replaceFirst("(?s)(kind: NetworkPolicy.*?matchLabels:\\s*\\n\\s*)app: data-prism",
                "$1app: other");
        assertThat(problems(otherPods)).anyMatch(p -> p.contains("selects other pods"));
        String anySource = manifest.replace("    - from:\n        - podSelector:\n            matchLabels:\n"
                + "              app: data-prism\n", "    - from:\n        - podSelector:\n            matchLabels:\n"
                + "              app: data-prism\n        - ipBlock:\n            cidr: 0.0.0.0/0\n");
        assertThat(problems(anySource)).anyMatch(p -> p.contains("admits a source"));
        String otherPort = manifest.replace("          port: 5701\n# --8<-- [end:k8s-networkpolicy]",
                "          port: 5702\n# --8<-- [end:k8s-networkpolicy]");
        assertThat(problems(otherPort)).anyMatch(p -> p.contains("other than the configured member port"));
    }

    @Test
    @DisplayName("a pod template with hostNetwork: true is refused, and false or absent passes")
    void hostNetworkFails() throws IOException {
        String manifest = read("kubernetes.yaml");
        String marker = "      automountServiceAccountToken: false\n";
        assertThat(manifest).contains(marker);
        assertThat(problems(manifest.replace(marker, marker + "      hostNetwork: true\n")))
                .anyMatch(p -> p.contains("hostNetwork: true"));
        assertThat(problems(manifest.replace(marker, marker + "      hostNetwork: false\n"))).isEmpty();
    }

    @Test
    @DisplayName("kubernetes.yaml sets the assertion and says what makes it true and what limits it")
    void theKubernetesManifestStatesTheAssertion() throws IOException {
        String manifest = read("kubernetes.yaml");
        assertThat(manifest).contains("name: DATAPRISM_HAZELCAST_TRUSTEDNETWORK\n              value: \"true\"");
        String comments = commentText("kubernetes.yaml");
        assertThat(comments).contains("only Data Prism members can reach the member port")
                .contains("unsealed").contains(POLICY).contains("any pod labelled app: data-prism")
                .contains("dedicated namespace").contains("flannel").contains("kind's default")
                .contains("hostNetwork");
    }

    @Test
    @DisplayName("compose.yaml says it is a local demonstration and when the assertion does not hold")
    void composeIsADemonstration() throws IOException {
        String manifest = read("compose.yaml");
        assertThat(manifest.split("DATAPRISM_HAZELCAST_TRUSTEDNETWORK: \"true\"", -1)).hasSize(3);
        String comments = commentText("compose.yaml");
        assertThat(comments).contains(DEMO_SENTENCE).contains(ENGINE_SENTENCE);
        // Two copies: the header and the comment above the setting.
        assertThat(comments.split(java.util.regex.Pattern.quote(ENGINE_SENTENCE), -1)).hasSizeGreaterThanOrEqualTo(3);
    }
}
