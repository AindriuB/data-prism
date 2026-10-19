package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.ConsistencyFinding;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseSizeTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> STRINGS = List.of("", "plain", "\"\\\b\f\n\r\t\u0000\u001f", "é漢字", "\u2028\u2029", "📦", "\ud800", "\udfff");

    @Test void generatedTreesNeverExceedTheBound() {
        // Fixed seed makes a failing generated case reproducible; this is not a pseudonymisation path.
        var random = new Random(193);
        for (int i = 0; i < 2000; i++) {
            JsonNode tree = generated(random, 4);
            long actual = MAPPER.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8).length;
            assertThat(ResponseSize.tree(tree)).as("generated tree %s", i)
                    .isBetween(actual, 3 * actual);
        }
    }

    @Test void responseBoundIncludesMetadataFindingsAndStatusOnlyEntries() {
        ObjectNode tree = NODES.objectNode().put("label", "📦\n漢");
        var finding = new ConsistencyFinding("label", PrivacyNamespace.NONE, ConsistencyFinding.Kind.INCONSISTENT,
                List.of(List.of("alpha", "beta"), List.of("gamma")), 2, "synthetic disagreement");
        var response = new ContextResponse("PARCEL", "SUBJ-1", Map.of("alpha", SourceResult.answered(tree),
                "beta", SourceResult.of(SourceOutcome.Status.REFUSED)), List.of(finding), Map.of(), "ignored");
        long actual = MAPPER.writeValueAsString(response).getBytes(StandardCharsets.UTF_8).length;
        assertThat(ResponseSize.bytes(response)).isBetween(actual, 3 * actual);
        assertThat(ResponseSize.records(response)).isEqualTo(1);
    }

    @Test void binaryNodesIncludeTheirBase64Encoding() {
        for (int length : new int[] {0, 1, 2, 3, 76, 1024}) {
            var node = NODES.binaryNode(new byte[length]);
            assertThat(ResponseSize.tree(node)).isEqualTo(MAPPER.writeValueAsBytes(node).length);
        }
    }

    @Test void numericExtremesHaveConservativeBounds() {
        for (double number : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, Double.MAX_VALUE, -0.0, 1e-200, 1e200}) {
            JsonNode tree = NODES.numberNode(number);
            assertThat(ResponseSize.tree(tree)).isGreaterThanOrEqualTo((long)MAPPER.writeValueAsBytes(tree).length);
        }
        for (String number : List.of("1E+1000", "1E-1000", "0.0000001", "123456789012345678901234567890.123")) {
            JsonNode tree = NODES.numberNode(new BigDecimal(number));
            assertThat(ResponseSize.tree(tree)).isGreaterThanOrEqualTo((long)MAPPER.writeValueAsBytes(tree).length);
        }
    }

    @Test void traversalDoesNotUseTheJavaCallStack() {
        ObjectNode root = NODES.objectNode();
        ObjectNode child = root;
        for (int i = 0; i < 20000; i++) child = child.putObject("x");
        assertThat(ResponseSize.tree(root)).isEqualTo(120002);
        var response = new ContextResponse("PARCEL", "SUBJ", Map.of("a", SourceResult.answered(root)), List.of(), Map.of(), "");
        assertThat(ResponseSize.records(response)).isEqualTo(1);
    }

    private JsonNode generated(Random random, int depth) {
        int kind = random.nextInt(depth == 0 ? 5 : 7);
        return switch (kind) {
            case 0 -> NODES.stringNode(STRINGS.get(random.nextInt(STRINGS.size())));
            case 1 -> NODES.numberNode(random.nextLong());
            case 2 -> NODES.numberNode(Double.longBitsToDouble(random.nextLong()));
            case 3 -> NODES.booleanNode(random.nextBoolean());
            case 4 -> NODES.nullNode();
            case 5 -> {
                var array = NODES.arrayNode();
                for (int i = random.nextInt(5); i > 0; i--) array.add(generated(random, depth - 1));
                yield array;
            }
            default -> {
                var object = NODES.objectNode();
                for (int i = random.nextInt(5); i > 0; i--)
                    object.set(STRINGS.get(random.nextInt(STRINGS.size())) + i, generated(random, depth - 1));
                yield object;
            }
        };
    }
}
