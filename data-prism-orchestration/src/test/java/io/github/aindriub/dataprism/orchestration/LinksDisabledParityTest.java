package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Parcel;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Plain;
import io.github.aindriub.dataprism.orchestration.links.LinkBuilderTest.Rig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** With links off, the serialised response is what it was before links existed. */
class LinksDisabledParityTest {

    private static Rig rig() {
        return new Rig().disabled().source("origin-api", new Parcel("P-1", List.of("D-1"), List.of(), "ok"))
                .source("parcel-api", new Plain("a"), "parcel-ref").source("quiet-api", null);
    }

    @Test
    void noSourceCarriesLinksOrALinksOmittedMarker() throws Exception {
        ContextResponse response = rig().run();

        String json = LinkBuilderTest.MAPPER.writeValueAsString(response);
        assertThat(json).doesNotContain("_links").doesNotContain("_linksOmitted");
        for (JsonNode source : LinkBuilderTest.MAPPER.readTree(json).get("sources")) {
            assertThat(source.propertyNames()).isSubsetOf("status", "entity");
        }
    }

    @Test
    void theSerialisedShapeIsTheBaseShape() throws Exception {
        ContextResponse response = rig().run();

        JsonNode tree = LinkBuilderTest.MAPPER.readTree(LinkBuilderTest.MAPPER.writeValueAsString(response));

        assertThat(tree.propertyNames()).containsExactlyInAnyOrder("entityType", "subject", "sources", "findings");
        long answered = tree.get("sources").properties().stream()
                .filter(e -> e.getValue().get("status").asString().equals("ANSWERED")).count();
        assertThat(answered).isEqualTo(2);
        tree.get("sources").forEach(source -> {
            if (source.get("status").asString().equals("ANSWERED")) {
                assertThat(source.get("entity").toString()).isEqualTo("{\"note\":\"" + (source.get("entity")
                        .get("note").asString()) + "\"}");
            }
        });
    }

    @Test
    void nothingIsCountedAndTheSourceResultKeepsItsTwoArgumentForm() {
        Rig rig = rig();

        ContextResponse response = rig.run();

        assertThat(rig.count(Metric.LINKS_ISSUED)).isZero();
        assertThat(rig.count(Metric.LINKS_OMITTED)).isZero();
        assertThat(response.sources().values()).allMatch(s -> s.links().isEmpty() && s.linksOmitted() == null);
        assertThat(new SourceResult("NO_DATA", null)).isEqualTo(SourceResult.of(SourceOutcome.Status.NO_DATA));
    }
}
