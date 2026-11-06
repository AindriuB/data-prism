package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.mcp.ListSourcesToolTest.Env;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The four schema files: valid draft 2020-12, the definitions the tools serve, and the configured page bound. */
class CatalogToolSchemaTest {

    private static final ObjectMapper MAPPER = DataPrismObjectMapper.create();
    private static final List<String> FILES = List.of("list_sources.input.json", "list_sources.output.json",
            "describe_source.input.json", "describe_source.output.json");

    private static JsonNode file(String name) throws IOException {
        try (InputStream in = CatalogToolSchemaTest.class.getResourceAsStream("schema/" + name)) {
            assertThat(in).as(name).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    @Test
    @DisplayName("each file declares draft 2020-12, compiles as a schema, and every $ref resolves in its document")
    void filesAreValidSchemas() throws IOException {
        DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
        for (String name : FILES) {
            JsonNode schema = file(name);
            assertThat(schema.get("$schema").asString()).as(name).isEqualTo("https://json-schema.org/draft/2020-12/schema");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.convertValue(schema, Map.class);
            assertThat(validator.validateSchema(map).valid()).as(name).isTrue();
            assertRefsResolve(schema, schema, name);
        }
    }

    private static void assertRefsResolve(JsonNode root, JsonNode node, String name) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null) {
                assertThat(ref.asString()).startsWith("#/");
                assertThat(root.at(ref.asString().substring(1)).isMissingNode()).as(name + " " + ref).isFalse();
            }
            node.forEachEntry((key, child) -> assertRefsResolve(root, child, name));
        } else if (node.isArray()) {
            node.forEach(child -> assertRefsResolve(root, child, name));
        }
    }

    @Test
    @DisplayName("the served schemas are the checked-in files, byte for byte in canonical form, at the default length")
    void servedSchemasEqualTheFiles() throws IOException {
        Env env = new Env(Set.of("LIST_SOURCES", "DESCRIBE_SOURCE"), 1);
        var list = env.list.specification().tool();
        var describe = env.describe.specification().tool();

        assertThat(canonical(list.inputSchema())).isEqualTo(canonical(file("list_sources.input.json")));
        assertThat(canonical(list.outputSchema())).isEqualTo(canonical(file("list_sources.output.json")));
        assertThat(canonical(describe.inputSchema())).isEqualTo(canonical(file("describe_source.input.json")));
        assertThat(canonical(describe.outputSchema())).isEqualTo(canonical(file("describe_source.output.json")));
    }

    private static String canonical(Object tree) {
        return MAPPER.writeValueAsString(tree instanceof JsonNode node ? node : MAPPER.valueToTree(tree));
    }

    @Test
    @DisplayName("the page bound of the served schemas is the configured link max-length")
    @SuppressWarnings("unchecked")
    void pageBoundFollowsTheConfiguration() {
        Env env = new Env(Set.of("LIST_SOURCES"), 1);
        ListSourcesTool tool = new ListSourcesTool(env.queries, 700, null_authz(), null_scopes(),
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), env.audit,
                ListSourcesToolTest.FIXED, null, ToolOptions.defaults().noAdmission().build());

        var spec = tool.specification().tool();
        Map<String, Object> input = (Map<String, Object>) ((Map<String, Object>) spec.inputSchema().get("properties")).get("page");
        Map<String, Object> defs = (Map<String, Object>) spec.outputSchema().get("$defs");
        Map<String, Object> next = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) defs.get("page"))
                .get("properties")).get("next");

        assertThat(input).containsEntry("maxLength", 700);
        assertThat(next).containsEntry("maxLength", 700);
    }

    private static io.github.aindriub.dataprism.security.AuthorizationService null_authz() {
        return new io.github.aindriub.dataprism.security.AuthorizationService(
                new io.github.aindriub.dataprism.security.SecurityPolicy(Set.of("p"), Map.of()), "DEFAULT",
                io.github.aindriub.dataprism.core.model.PrivacyScopeType.INVESTIGATION,
                io.github.aindriub.dataprism.security.ClientAllowlist.unrestricted());
    }

    private static io.github.aindriub.dataprism.security.ScopeResolver null_scopes() {
        return new io.github.aindriub.dataprism.security.ScopeResolver(
                io.github.aindriub.dataprism.core.model.PseudonymisationVersion.HMAC_SHA256_V1.withKey("k")
                        .withVocabulary("v"),
                java.time.Duration.ofHours(1),
                new io.github.aindriub.dataprism.security.PurposeValidator(Set.of("p")),
                io.github.aindriub.dataprism.security.CaseAuthorizer.tokenTrusted());
    }

    @Test
    @DisplayName("the output schemas reject a page that says last false without next, and accept the shape of the design")
    void pageObjectConditions() {
        Env env = new Env(Set.of("LIST_SOURCES"), 1);
        Map<String, Object> schema = env.list.specification().tool().outputSchema();
        DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
        Map<String, Object> entry = Map.of("source", "ORG-6M1ZQ0AB", "description", "Items in transit.",
                "inputKeys", List.of("parcel-ref"));

        Map<String, Object> middle = Map.of("correlationId", "c-1", "labels", "ALIAS", "sources", List.of(entry),
                "page", Map.of("size", 2, "last", false, "next", "AQpkZW1vLWxpc3QtMQ", "expiresAt", "2026-10-19T14:45:00Z"));
        Map<String, Object> last = Map.of("correlationId", "c-1", "labels", "ALIAS", "sources", List.of(entry),
                "page", Map.of("size", 2, "last", true));
        Map<String, Object> noNext = Map.of("correlationId", "c-1", "labels", "ALIAS", "sources", List.of(entry),
                "page", Map.of("size", 2, "last", false));
        Map<String, Object> nextOnLast = Map.of("correlationId", "c-1", "labels", "ALIAS", "sources", List.of(entry),
                "page", Map.of("size", 2, "last", true, "next", "AQpk"));
        Map<String, Object> nullField = Map.of("correlationId", "c-1", "labels", "ALIAS",
                "sources", List.of(Map.of("source", "ORG-6M1ZQ0AB", "description", "x", "inputKeys", List.of(),
                        "cost", "FREE")),
                "page", Map.of("size", 2, "last", true));

        assertThat(validator.validate(schema, middle).valid()).isTrue();
        assertThat(validator.validate(schema, last).valid()).isTrue();
        assertThat(validator.validate(schema, noNext).valid()).isFalse();
        assertThat(validator.validate(schema, nextOnLast).valid()).isFalse();
        assertThat(validator.validate(schema, nullField).valid()).isFalse();
    }

    @Test
    @DisplayName("the describe output schema requires chainable and keyNamespace together")
    void describeFieldConditions() {
        Env env = new Env(Set.of("DESCRIBE_SOURCE"), 1);
        Map<String, Object> schema = env.describe.specification().tool().outputSchema();
        DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
        Map<String, Object> base = Map.of("correlationId", "c", "labels", "REAL", "source", "s", "description", "d",
                "inputKeys", List.of(), "undeclared", "REFUSE");

        assertThat(validator.validate(schema, with(base, Map.of("path", "a", "protection", "MARKER"))).valid()).isTrue();
        assertThat(validator.validate(schema, with(base,
                Map.of("path", "a", "protection", "ABSENT", "chainable", true, "keyNamespace", "parcel-ref"))).valid())
                .isTrue();
        assertThat(validator.validate(schema, with(base, Map.of("path", "a", "protection", "ABSENT", "chainable", true)))
                .valid()).isFalse();
        assertThat(validator.validate(schema, with(base, Map.of("path", "a", "protection", "NOPE"))).valid()).isFalse();
    }

    private static Map<String, Object> with(Map<String, Object> base, Map<String, Object> field) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(base);
        out.put("fields", List.of(field));
        return out;
    }
}
