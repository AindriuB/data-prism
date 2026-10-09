package io.github.aindriub.dataprism.example.boundary;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.example.boundary.PerSourceHarness.Call;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static io.github.aindriub.dataprism.example.boundary.PerSourceHarness.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Task 190: each source keeps its own scrubbed record under its own key, and one source's refusal costs only that source. */
class PerSourceResultsTest {

    private static final Set<String> CODES = Set.of("ANSWERED", "NO_DATA", "TIMED_OUT", "FAILED", "CIRCUIT_OPEN",
            "SKIPPED_OVER_LIMIT", "REFUSED");

    @Test
    @DisplayName("two sources using the same field name keep both values, each under its own source")
    void sameFieldNameIsNotOverwritten() {
        Call call = call(List.of(plain("alpha-api", "ACTIVE", "Aoife Byrne"), plain("beta-api", "DORMANT", "Aoife Byrne")));

        JsonNode json = call.json();
        assertThat(json.has("entity")).as("no top-level entity").isFalse();
        assertThat(json.at("/sources/alpha-api/status").asString()).isEqualTo("ANSWERED");
        assertThat(json.at("/sources/beta-api/status").asString()).isEqualTo("ANSWERED");
        assertThat(json.at("/sources/alpha-api/entity/status").asString()).isEqualTo("ACTIVE");
        assertThat(json.at("/sources/beta-api/entity/status").asString()).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("every source asked appears once, with a status from the documented set and an entity only when answered")
    void everySourceAppearsWithADocumentedStatus() {
        Call call = call(List.of(plain("alpha-api", "ACTIVE", "Aoife Byrne"), empty("none-api"),
                scrubRefusing("bad-api")));

        JsonNode sources = call.json().get("sources");
        List<String> names = new ArrayList<>();
        sources.forEachEntry((name, entry) -> {
            names.add(name);
            assertThat(CODES).contains(entry.get("status").asString());
            assertThat(entry.has("entity")).isEqualTo("ANSWERED".equals(entry.get("status").asString()));
        });
        assertThat(names).containsExactly("alpha-api", "bad-api", "none-api");
        assertThat(sources.get("bad-api").get("status").asString()).isEqualTo("REFUSED");
        assertThat(sources.get("none-api").get("status").asString()).isEqualTo("NO_DATA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scrub", "validation"})
    @DisplayName("a refused source is withheld: the others answer, and the response equals one where it had no data")
    void refusedSourceIsWithheldAndLeavesNoTrace(String kind) {
        Function<String, DataSourceAdapter<?>> bad = "scrub".equals(kind)
                ? PerSourceHarness::scrubRefusing : PerSourceHarness::validationRefusing;
        String code = "scrub".equals(kind) ? "UNKNOWN_FIELD" : "VALIDATION_FAILED";
        DataSourceAdapter<?> alpha = plain("alpha-api", "ACTIVE", "Aoife Byrne");
        DataSourceAdapter<?> beta = plain("beta-api", "DORMANT", "Aiofe Byrne");

        Call refused = call(List.of(alpha, beta, bad.apply("bad-api")));
        Call noData = call(List.of(alpha, beta, empty("bad-api")));
        Call sibling = call(List.of(clean("bad-api")));

        assertThat(refused.result().isError()).isFalse();
        JsonNode json = refused.json();
        assertThat(json.at("/sources/alpha-api/status").asString()).isEqualTo("ANSWERED");
        assertThat(json.at("/sources/beta-api/status").asString()).isEqualTo("ANSWERED");
        assertThat(json.at("/sources/bad-api/status").asString()).isEqualTo("REFUSED");
        assertThat(json.get("sources").get("bad-api").has("entity")).isFalse();
        assertThat(json.get("findings")).as("the two answering sources disagree about the name").isNotEmpty();

        // Nothing of the refused source: not its field name, raw value, offending field or pseudonym.
        String text = refused.text();
        assertThat(text).doesNotContain(BAD_FIELD).doesNotContain(BAD_NAME).doesNotContain(STRAY_KEY)
                .doesNotContain(STRAY_VALUE).doesNotContain(NOTE_VALUE).doesNotContain("note\"");
        Set<String> pseudonyms = entityStrings(sibling.json());
        assertThat(pseudonyms).as("the sibling run produced the refused source's pseudonym").isNotEmpty();
        pseudonyms.stream().filter(s -> s.length() > 3).forEach(s -> assertThat(text).doesNotContain(s));
        assertThat(json.get("findings").toString()).doesNotContain("bad-api");

        // Byte-identical to the run where that source had no data, apart from its status string.
        assertThat(text.replace("\"REFUSED\"", "\"NO_DATA\"")).isEqualTo(noData.text());

        AuditEvent event = refused.event();
        assertThat(event.policyDecision()).isEqualTo("ALLOW");
        assertThat(event.sourceSystems()).contains("bad-api:REFUSED", "alpha-api:ANSWERED", "beta-api:ANSWERED");
        assertThat(event.fieldDispositions()).containsEntry("bad-api:<refused>", "REFUSED:" + code);
        assertThat(event.fieldDispositions().keySet()).noneMatch(k -> k.startsWith("bad-api:/"))
                .doesNotContain("merged:<refused>");
    }
}
