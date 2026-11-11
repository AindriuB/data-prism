package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MixedCallsTest {

    private static final Scenario.Mix ALL = new Scenario.Mix(4, 3, 3, 2, 2, 1);

    private static McpSchema.CallToolResult json(String body) {
        return McpSchema.CallToolResult.builder().addTextContent(body).isError(false).build();
    }

    @Test
    void everyOperationIsChosenInRoughProportionToItsWeight() {
        Map<MixedCalls.Op, Integer> counts = new EnumMap<>(MixedCalls.Op.class);
        for (long i = 0; i < 15_000; i++) {
            counts.merge(MixedCalls.choose(ALL, i), 1, Integer::sum);
        }

        assertThat(counts).hasSize(6);
        // 4 of 15 of 15,000 is 4,000; the hash is not exact, so a generous band.
        assertThat(counts.get(MixedCalls.Op.ENTITY_CONTEXT)).isBetween(3600, 4400);
        assertThat(counts.get(MixedCalls.Op.INVALID_LINK)).isBetween(800, 1200);
    }

    @Test
    void aZeroWeightIsNeverChosen() {
        Scenario.Mix mix = new Scenario.Mix(1, 0, 1, 0, 0, 0);
        for (long i = 0; i < 2_000; i++) {
            assertThat(MixedCalls.choose(mix, i)).isIn(MixedCalls.Op.ENTITY_CONTEXT, MixedCalls.Op.LIST_SOURCES);
        }
    }

    @Test
    void theChoiceIsRepeatable() {
        for (long i = 0; i < 200; i++) {
            assertThat(MixedCalls.choose(ALL, i)).isEqualTo(MixedCalls.choose(ALL, i));
        }
    }

    @Test
    void callsThatNeedWhatTheWorkerLacksFetchItFirst() {
        MixedCalls.State state = new MixedCalls.State();

        assertThat(MixedCalls.plan(MixedCalls.Op.DESCRIBE_SOURCE, state, "PARCEL", "s", 1).tool())
                .isEqualTo("list_sources");
        assertThat(MixedCalls.plan(MixedCalls.Op.TARGETED_ENTITY_CONTEXT, state, "PARCEL", "s", 1).tool())
                .isEqualTo("list_sources");
        assertThat(MixedCalls.plan(MixedCalls.Op.FOLLOW, state, "PARCEL", "s", 1).tool())
                .isEqualTo("get_entity_context");
    }

    @Test
    void aListResultGivesLabelsAndThePageToContinueFrom() {
        MixedCalls.State state = new MixedCalls.State();
        MixedCalls.Planned list = MixedCalls.plan(MixedCalls.Op.LIST_SOURCES, state, "PARCEL", "s", 0);
        MixedCalls.absorb(list, json("""
                {"sources":[{"source":"alpha"},{"source":"beta"}],"page":{"size":2,"last":false,"next":"tok_1"}}
                """), state);

        MixedCalls.Planned next = MixedCalls.plan(MixedCalls.Op.LIST_SOURCES, state, "PARCEL", "s", 1);
        MixedCalls.Planned describe = MixedCalls.plan(MixedCalls.Op.DESCRIBE_SOURCE, state, "PARCEL", "s", 1);
        MixedCalls.Planned targeted = MixedCalls.plan(MixedCalls.Op.TARGETED_ENTITY_CONTEXT, state, "PARCEL", "s", 0);

        assertThat(next.request().arguments()).containsEntry("page", "tok_1");
        assertThat(describe.tool()).isEqualTo("describe_source");
        assertThat(describe.request().arguments()).containsEntry("source", "beta");
        assertThat(targeted.request().arguments().get("targetSources")).isEqualTo(List.of("alpha", "beta"));
    }

    @Test
    void theLastPageEndsTheWalk() {
        MixedCalls.State state = new MixedCalls.State();
        MixedCalls.Planned list = MixedCalls.plan(MixedCalls.Op.LIST_SOURCES, state, "PARCEL", "s", 0);
        MixedCalls.absorb(list, json("{\"sources\":[{\"source\":\"alpha\"}],\"page\":{\"size\":1,\"last\":false,\"next\":\"t\"}}"), state);
        MixedCalls.absorb(list, json("{\"sources\":[{\"source\":\"alpha\"}],\"page\":{\"size\":1,\"last\":true}}"), state);

        assertThat(MixedCalls.plan(MixedCalls.Op.LIST_SOURCES, state, "PARCEL", "s", 2).request().arguments())
                .doesNotContainKey("page");
    }

    @Test
    void aContextResultsLinksAreFollowedOnceEach() {
        MixedCalls.State state = new MixedCalls.State();
        MixedCalls.Planned context = MixedCalls.plan(MixedCalls.Op.ENTITY_CONTEXT, state, "PARCEL", "s", 0);
        MixedCalls.absorb(context, json("""
                {"sources":{"a":{"status":"ANSWERED","_links":[{"link":"L1"},{"link":"L2"}]}}}
                """), state);

        MixedCalls.Planned first = MixedCalls.plan(MixedCalls.Op.FOLLOW, state, "PARCEL", "s", 1);
        MixedCalls.Planned second = MixedCalls.plan(MixedCalls.Op.FOLLOW, state, "PARCEL", "s", 2);
        MixedCalls.Planned third = MixedCalls.plan(MixedCalls.Op.FOLLOW, state, "PARCEL", "s", 3);

        assertThat(first.tool()).isEqualTo("follow");
        assertThat(second.tool()).isEqualTo("follow");
        assertThat(first.request().arguments().get("link")).isNotEqualTo(second.request().arguments().get("link"));
        assertThat(third.tool()).isEqualTo("get_entity_context");
    }

    private static final byte[] HELD = {1, 7, 'k', 9, 9, 9, 9, 9, 9, 9, 9, 'x', 'y', 'z', 42, 43, 44, 45};
    private static final String HELD_LINK = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(HELD);

    @Test
    void anInvalidLinkIsAHeldLinkWithOnlyItsLastByteChanged() {
        MixedCalls.State state = new MixedCalls.State();
        MixedCalls.absorb(MixedCalls.plan(MixedCalls.Op.ENTITY_CONTEXT, state, "PARCEL", "s", 0), json(
                "{\"sources\":{\"a\":{\"_links\":[{\"link\":\"" + HELD_LINK + "\"}]}}}"), state);

        MixedCalls.Planned planned = MixedCalls.plan(MixedCalls.Op.INVALID_LINK, state, "PARCEL", "s", 1);
        String sent = (String) planned.request().arguments().get("link");
        byte[] raw = java.util.Base64.getUrlDecoder().decode(sent);

        assertThat(planned.tool()).isEqualTo("follow");
        assertThat(sent).matches("[A-Za-z0-9_-]+").hasSameSizeAs(HELD_LINK).isNotEqualTo(HELD_LINK);
        // The header bytes (the version first) are the issued link's own, so the server gets past the cheap checks.
        assertThat(raw[0]).isEqualTo(HELD[0]);
        assertThat(java.util.Arrays.copyOf(raw, raw.length - 1)).isEqualTo(java.util.Arrays.copyOf(HELD, HELD.length - 1));
        assertThat(raw[raw.length - 1]).isNotEqualTo(HELD[HELD.length - 1]);
        // The held link is still there to be followed.
        assertThat(MixedCalls.plan(MixedCalls.Op.FOLLOW, state, "PARCEL", "s", 2).request().arguments().get("link"))
                .isEqualTo(HELD_LINK);
    }

    @Test
    void anInvalidLinkWithNoHeldLinkGetsOneFirst() {
        assertThat(MixedCalls.plan(MixedCalls.Op.INVALID_LINK, new MixedCalls.State(), "PARCEL", "s", 1).tool())
                .isEqualTo("get_entity_context");
    }

    @Test
    void translatedLinksAreKeptApartAndFollowedOnTheirOwn() {
        MixedCalls.State state = new MixedCalls.State();
        MixedCalls.absorb(MixedCalls.plan(MixedCalls.Op.ENTITY_CONTEXT, state, "PARCEL", "s", 0), json("""
                {"sources":{"a":{"_links":[{"link":"D1","rel":"direct"},
                  {"link":"T1","rel":"translated","toNamespace":"parcel-ref"}]}}}
                """), state);

        assertThat(MixedCalls.plan(MixedCalls.Op.FOLLOW_TRANSLATED, state, "PARCEL", "s", 1)
                .request().arguments().get("link")).isEqualTo("T1");
        assertThat(MixedCalls.plan(MixedCalls.Op.FOLLOW, state, "PARCEL", "s", 2)
                .request().arguments().get("link")).isEqualTo("D1");
        assertThat(MixedCalls.plan(MixedCalls.Op.FOLLOW_TRANSLATED, state, "PARCEL", "s", 3).tool())
                .isEqualTo("get_entity_context");
    }

    @Test
    void operationsAreNamedByCallKind() {
        assertThat(MixedCalls.Op.INVALID_LINK.key()).isEqualTo("invalidLink");
        assertThat(MixedCalls.Op.TARGETED_ENTITY_CONTEXT.key()).isEqualTo("targetedEntityContext");
        assertThat(MixedCalls.Op.FOLLOW_TRANSLATED.key()).isEqualTo("followTranslated");
    }

    @Test
    void aFailedResultKeepsNothingFromItsBody() {
        MixedCalls.State state = new MixedCalls.State();
        MixedCalls.Planned list = MixedCalls.plan(MixedCalls.Op.LIST_SOURCES, state, "PARCEL", "s", 0);
        MixedCalls.absorb(list, McpSchema.CallToolResult.builder().addTextContent(
                "{\"sources\":[{\"source\":\"x\"}]}").isError(true).build(), state);

        assertThat(MixedCalls.plan(MixedCalls.Op.DESCRIBE_SOURCE, state, "PARCEL", "s", 0).tool())
                .isEqualTo("list_sources");
    }
}
