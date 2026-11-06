package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.names;
import static io.github.aindriub.dataprism.orchestration.catalog.CatalogQueriesTest.sources;
import static org.assertj.core.api.Assertions.assertThat;

/** The keyset walk of batch-and-bulk-follow.md Part A items 1 to 10, over estates of every shape. */
class CatalogPagingTest {

    private static final int N = 3;

    private static PagingSettings settings() {
        return new PagingSettings(N, 5, Duration.ofMinutes(15));
    }

    /** Walks the whole listing, returning the pages' labels; fails on an unexpected refusal. */
    private static List<List<String>> walk(Fixture fx, Integer pageSize) {
        List<List<String>> pages = new ArrayList<>();
        CatalogQueries.Outcome outcome = fx.list(pageSize, null);
        while (true) {
            assertThat(outcome.refusalCode()).isNull();
            pages.add(sources(outcome));
            JsonNode page = outcome.body().get("page");
            if (page.get("last").asBoolean()) {
                assertThat(page.has("next")).isFalse();
                assertThat(page.has("expiresAt")).isFalse();
                return pages;
            }
            assertThat(page.get("next").asString()).isNotBlank();
            assertThat(page.get("expiresAt").asString()).isNotBlank();
            outcome = fx.list(pageSize, page.get("next").asString());
        }
    }

    @ParameterizedTest(name = "an estate of {0} entries is returned exactly once at page size 3")
    @ValueSource(ints = {0, 1, 3, 4, 6, 7})
    @DisplayName("every entry exactly once over estates of 0, 1, n, n+1 and 2n; no empty final page")
    void everyEntryExactlyOnce(int estate) {
        Fixture fx = new Fixture(names(estate), settings(), true);

        List<List<String>> pages = walk(fx, null);

        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(names(estate));
        if (estate > 0) {
            assertThat(pages).allSatisfy(page -> assertThat(page).isNotEmpty().hasSizeLessThanOrEqualTo(N));
        } else {
            assertThat(pages).containsExactly(List.of());
        }
        assertThat(pages).hasSize(Math.max(1, (estate + N - 1) / N));
    }

    @Test
    @DisplayName("last is decided by looking one entry past: a full final page says last, never an empty extra page")
    void exactMultipleEndsOnAFullLastPage() {
        Fixture fx = new Fixture(names(6), settings(), true);

        CatalogQueries.Outcome first = fx.list(null, null);
        CatalogQueries.Outcome second = fx.list(null, first.body().get("page").get("next").asString());

        assertThat(first.body().get("page").get("last").asBoolean()).isFalse();
        assertThat(sources(second)).containsExactly("src-004", "src-005", "src-006");
        assertThat(second.body().get("page").get("last").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the next page begins after the last label (keyset), not after a count (offset)")
    void keysetNotOffset() {
        Fixture fx = new Fixture(names(6), settings(), true);
        CatalogQueries.Outcome first = fx.list(null, null);
        String next = first.body().get("page").get("next").asString();

        // Same estate, the first entry removed: a keyset walk from the old link is refused by the tag, but a
        // fixture with the same estate gives the same labels, so the link resumes at src-004 on a fresh instance.
        Fixture other = new Fixture(names(6), settings(), true);
        CatalogQueries.Outcome resumed = other.list(null, next);

        assertThat(resumed.refusalCode()).isNull();
        assertThat(sources(resumed)).containsExactly("src-004", "src-005", "src-006");
    }

    @Test
    @DisplayName("a label equal to the last one is not repeated (greater-than, not greater-or-equal)")
    void strictlyAfterTheLastLabel() {
        Fixture fx = new Fixture(names(4), new PagingSettings(1, 5, Duration.ofMinutes(15)), true);

        List<List<String>> pages = walk(fx, null);

        assertThat(pages).containsExactly(List.of("src-001"), List.of("src-002"), List.of("src-003"), List.of("src-004"));
    }

    @Test
    @DisplayName("a pageSize above the ceiling applies the ceiling and reports it; the default applies when absent")
    void ceilingAndDefault() {
        Fixture fx = new Fixture(names(12), settings(), true);

        CatalogQueries.Outcome above = fx.list(99, null);
        CatalogQueries.Outcome absent = fx.list(null, null);
        CatalogQueries.Outcome small = fx.list(2, null);

        assertThat(above.body().get("page").get("size").asInt()).isEqualTo(5);
        assertThat(sources(above)).hasSize(5);
        assertThat(absent.body().get("page").get("size").asInt()).isEqualTo(N);
        assertThat(sources(small)).hasSize(2);
        // The ceiling is a pageSize too: a walk that repeats the oversized request is not a mismatch.
        CatalogQueries.Outcome follow = fx.list(99, above.body().get("page").get("next").asString());
        assertThat(follow.refusalCode()).isNull();
        assertThat(sources(follow)).containsExactly("src-006", "src-007", "src-008", "src-009", "src-010");
    }

    @Test
    @DisplayName("filters apply before the slice over a walk: every matching entry once, pages full")
    void filteredWalk() {
        List<String> all = names(9);
        java.util.Map<String, io.github.aindriub.dataprism.core.spi.SourceDescription> described = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            described.put(all.get(i), new io.github.aindriub.dataprism.core.spi.SourceDescription(
                    java.util.Set.of(i % 3 == 0 ? "DEPOT" : "PARCEL"), null, null, List.of(), null, null));
        }
        Fixture fx = new Fixture(all, new PagingSettings(2, 5, Duration.ofMinutes(15)), true, true,
                List.of(new io.github.aindriub.dataprism.validation.SensitivePatternValidator()), described);

        List<String> seen = new ArrayList<>();
        CatalogQueries.Outcome outcome = fx.list(new CatalogQueries.ListRequest("DEPOT", null, null, null), true);
        while (true) {
            seen.addAll(sources(outcome));
            if (outcome.body().get("page").get("last").asBoolean()) {
                break;
            }
            assertThat(sources(outcome)).hasSize(2);
            outcome = fx.list(new CatalogQueries.ListRequest("DEPOT", null, null,
                    outcome.body().get("page").get("next").asString()), true);
        }

        assertThat(seen).containsExactly("src-001", "src-004", "src-007");
    }

    // ---- real names over the link's 64-byte label bound (PLAN 276) ----

    private static String longName(String prefix) {
        return prefix + "-" + "x".repeat(70);
    }

    @Test
    @DisplayName("long real names [L1, L2, a, b] at page size 2: the page ends at a name the link can carry")
    void longNamesFirst() {
        // Byte order: "a", "b", "l1...", "l2..." -> use names so the long ones sort first.
        String l1 = longName("A1");
        String l2 = longName("A2");
        Fixture fx = new Fixture(List.of(l1, l2, "b-a", "b-b"), new PagingSettings(2, 5, Duration.ofMinutes(15)), true);

        List<List<String>> pages = walk(fx, null);

        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactly(l1, l2, "b-a", "b-b");
        // The window [L1, L2] has no label that fits, so the page extends to the next one that does.
        assertThat(pages.get(0)).containsExactly(l1, l2, "b-a");
        assertThat(pages.get(1)).containsExactly("b-b");
    }

    @Test
    @DisplayName("all names too long at page size 1: the walk ends on one page, last true")
    void allLong() {
        List<String> longs = List.of(longName("a"), longName("b"), longName("c"));
        Fixture fx = new Fixture(longs, new PagingSettings(1, 5, Duration.ofMinutes(15)), true);

        List<List<String>> pages = walk(fx, null);

        assertThat(pages).containsExactly(longs);
    }

    @Test
    @DisplayName("batch example 1: a-depot, a 70-character b, c-depot at page size 2")
    void batchExampleOne() {
        String b = longName("b");
        Fixture fx = new Fixture(List.of("a-depot", b, "c-depot"), new PagingSettings(2, 5, Duration.ofMinutes(15)), true);

        List<List<String>> pages = walk(fx, null);

        assertThat(pages).containsExactly(List.of("a-depot"), List.of(b, "c-depot"));
    }

    @Test
    @DisplayName("batch example 2: page size 3 over a-depot, long b, long c, d-depot")
    void batchExampleTwo() {
        String b = longName("b");
        String c = longName("c");
        Fixture fx = new Fixture(List.of("a-depot", b, c, "d-depot"), new PagingSettings(3, 5, Duration.ofMinutes(15)), true);

        List<List<String>> pages = walk(fx, null);

        assertThat(pages).containsExactly(List.of("a-depot"), List.of(b, c, "d-depot"));
    }

    @Test
    @DisplayName("a page that ends at a fitting label with entries after it is not extended")
    void fittingEndIsNotExtended() {
        String b = longName("b");
        Fixture fx = new Fixture(List.of("a-depot", b, "c-depot", "d-depot"), new PagingSettings(3, 5, Duration.ofMinutes(15)), true);

        List<List<String>> pages = walk(fx, null);

        assertThat(pages).containsExactly(List.of("a-depot", b, "c-depot"), List.of("d-depot"));
    }

    @Test
    @DisplayName("the pages of a walk are contiguous slices of one total order")
    void walkIsContiguous() {
        Fixture fx = new Fixture(names(20), settings(), true);

        String joined = walk(fx, null).stream().flatMap(List::stream).collect(Collectors.joining(","));

        assertThat(joined).isEqualTo(String.join(",", names(20)));
    }
}
