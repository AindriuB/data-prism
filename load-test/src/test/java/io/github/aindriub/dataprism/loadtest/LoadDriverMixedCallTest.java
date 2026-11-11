package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The mixed dispatch of {@code LoadDriver.mixedCall}, behind its sender seam: no server, no network. */
class LoadDriverMixedCallTest {

    private static final Scenario.Load LOAD = new Scenario.Load(1, 10, 0, 0, 0, 30, 0);

    private static Scenario scenario(Scenario.Mix mix, Scenario.Target target) {
        return target == null
                ? new Scenario("t", 2, null, LOAD, null, 1L, 0, null, mix, null)
                : new Scenario("t", 0, null, LOAD, null, 1L, 0, null, mix, target);
    }

    private static McpSchema.CallToolResult text(String body, boolean error) {
        return McpSchema.CallToolResult.builder().addTextContent(body).isError(error).build();
    }

    private static final java.util.function.LongSupplier CLOCK = new java.util.function.LongSupplier() {
        private long now;

        @Override
        public long getAsLong() {
            return now += 1_000_000;
        }
    };

    @Test
    void aContextCallIsClassifiedAsContextAndOtherToolsAsOther() {
        Scenario scenario = scenario(new Scenario.Mix(1, 0, 0, 0, 0, 0), null);
        McpSchema.CallToolResult answered = text("{\"sources\":{\"a\":{\"status\":\"ANSWERED\"}}}", false);

        LoadDriver.Called context = LoadDriver.mixedCall(request -> answered, new MixedCalls.State(), scenario,
                "s-1", 0, 0, CLOCK);

        assertThat(context.operation()).isEqualTo("entityContext");
        assertThat(context.outcome().sourceStatuses()).containsEntry("a", "ANSWERED");

        Scenario listing = scenario(new Scenario.Mix(0, 0, 1, 0, 0, 0), null);
        LoadDriver.Called list = LoadDriver.mixedCall(request -> text("{\"sources\":[]}", false),
                new MixedCalls.State(), listing, "s-1", 0, 0, CLOCK);

        assertThat(list.operation()).isEqualTo("listSources");
        assertThat(list.outcome().sourceStatuses()).isEmpty();
        assertThat(list.outcome().refusalCode()).isNull();
    }

    @Test
    void aThrownCallIsAFailureAndStillClearsWhatItWouldHaveReplaced() {
        Scenario scenario = scenario(new Scenario.Mix(0, 0, 1, 0, 0, 0), null);
        MixedCalls.State state = new MixedCalls.State();
        // A first walk leaves a next page.
        LoadDriver.mixedCall(request -> text("{\"sources\":[{\"source\":\"x\"}],\"page\":{\"last\":false,\"next\":\"P2\"}}",
                false), state, scenario, "s", 0, 0, CLOCK);
        List<McpSchema.CallToolRequest> sent = new ArrayList<>();

        LoadDriver.Called thrown = LoadDriver.mixedCall(request -> {
            sent.add(request);
            throw new IllegalStateException("boom");
        }, state, scenario, "s", 1, 0, CLOCK);
        LoadDriver.mixedCall(request -> {
            sent.add(request);
            return text("{\"sources\":[]}", false);
        }, state, scenario, "s", 2, 0, CLOCK);

        assertThat(thrown.outcome().completed()).isFalse();
        assertThat(thrown.operation()).isEqualTo("listSources");
        assertThat(sent.get(0).arguments()).containsEntry("page", "P2");
        // The failed call dropped the page link: the next walk starts again from the first page.
        assertThat(sent.get(1).arguments()).doesNotContainKey("page");
    }

    @Test
    void aTargetsSubjectsAreCycledAndTheGeneratedOneIsNotUsed() {
        Scenario.Target target = new Scenario.Target("http://h:1", "http://h:2/t", "CUSTOMER",
                List.of("1001", "1002"), 0, 0);
        Scenario scenario = scenario(new Scenario.Mix(1, 0, 0, 0, 0, 0), target);
        List<Object> subjects = new ArrayList<>();
        List<Object> types = new ArrayList<>();

        for (long index = 0; index < 4; index++) {
            LoadDriver.mixedCall(request -> {
                subjects.add(request.arguments().get("subjectId"));
                types.add(request.arguments().get("entityType"));
                return text("{\"sources\":{}}", false);
            }, new MixedCalls.State(), scenario, "generated", index, 0, CLOCK);
        }

        assertThat(subjects).containsExactly("1001", "1002", "1001", "1002");
        assertThat(types).containsOnly("CUSTOMER");
        assertThat(LoadDriver.subjectOf(scenario(null, null), "generated-1", 3)).isEqualTo("generated-1");
    }
}
