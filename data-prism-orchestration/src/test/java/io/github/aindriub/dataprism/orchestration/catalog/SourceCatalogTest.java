package io.github.aindriub.dataprism.orchestration.catalog;

import static io.github.aindriub.dataprism.orchestration.catalog.TestAdapters.aliasText;
import static io.github.aindriub.dataprism.orchestration.catalog.TestAdapters.texts;
import static io.github.aindriub.dataprism.orchestration.catalog.TestAdapters.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.catalog.TestAdapters.DescribedAdapter;
import io.github.aindriub.dataprism.orchestration.catalog.TestAdapters.OldStyleAdapter;
import io.github.aindriub.dataprism.orchestration.catalog.TestAdapters.WidgetLedgerAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SourceCatalogTest {

    private static final String INVALID = "INVALID_SOURCE_DESCRIPTION: ";
    private static final String LEAKS = "SOURCE_DESCRIPTION_LEAKS_NAME: ";

    private static SourceCatalog build(DataSourceAdapter<?>... adapters) {
        return SourceCatalog.build(List.of(adapters), Map.of());
    }

    private static DataSourceAdapter<?> described(String text) {
        return new DescribedAdapter("alpha", aliasText(text));
    }

    private static void assertRefused(String code, int rule, DataSourceAdapter<?> adapter) {
        assertThatThrownBy(() -> build(adapter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(code + "source " + adapter.sourceName() + " rule " + rule);
    }

    // ---- compatibility and fallback ----

    @Test
    @DisplayName("an adapter with no description() builds with the fallback, no types and no keys")
    void oldStyleAdapter() {
        SourceCatalog catalog = build(new OldStyleAdapter("alpha"));
        assertThat(catalog.entries()).hasSize(1);
        SourceCatalog.Entry entry = catalog.entry("alpha").orElseThrow();
        assertThat(entry.aliasText()).isEqualTo("A data source.");
        assertThat(entry.textFor(SourceCatalog.LabelKind.REAL)).isEqualTo("A data source.");
        assertThat(entry.description().entityTypes()).isEmpty();
        assertThat(entry.description().inputKeys()).isEmpty();
        assertThat(catalog.generatedDescriptionCount()).isEqualTo(1);
        assertThat(catalog.generatedDescriptionSources()).containsExactly("alpha");
    }

    @Test
    @DisplayName("an adapter returning null from description() is treated as undeclared, not dropped")
    void nullDescription() {
        SourceCatalog catalog = build(new DescribedAdapter("alpha", null));
        assertThat(catalog.entry("alpha")).isPresent();
        assertThat(catalog.entry("alpha").orElseThrow().aliasText()).isEqualTo("A data source.");
    }

    @Test
    @DisplayName("the fallback texts are exact and sorted whatever the declared order")
    void fallbackTexts() {
        assertThat(fallback(types(Set.of("Zeta", "Alpha"), List.of("z-ref", "a-ref"))))
                .isEqualTo("Holds Alpha, Zeta records; accepts a-ref, z-ref keys.");
        assertThat(fallback(types(Set.of("Zeta", "Alpha"), List.of()))).isEqualTo("Holds Alpha, Zeta records.");
        assertThat(fallback(types(Set.of("One"), List.of("k")))).isEqualTo("Holds One records; accepts k keys.");
        assertThat(fallback(types(Set.of(), List.of("k")))).isEqualTo("A data source.");
        assertThat(fallback(SourceDescription.undeclared())).isEqualTo("A data source.");
    }

    private static String fallback(SourceDescription description) {
        return build(new DescribedAdapter("alpha", description)).entry("alpha").orElseThrow().aliasText();
    }

    @Test
    @DisplayName("a declared alias is used, a missing real text falls back to the alias text, a declared one is kept")
    void textSelection() {
        SourceCatalog.Entry onlyAlias = build(described("Neutral text.")).entry("alpha").orElseThrow();
        assertThat(onlyAlias.textFor(SourceCatalog.LabelKind.ALIAS)).isEqualTo("Neutral text.");
        assertThat(onlyAlias.textFor(SourceCatalog.LabelKind.REAL)).isEqualTo("Neutral text.");
        assertThat(onlyAlias.generated()).isFalse();
        SourceCatalog.Entry both = build(new DescribedAdapter("alpha", texts("Neutral text.", "Real text.")))
                .entry("alpha").orElseThrow();
        assertThat(both.textFor(SourceCatalog.LabelKind.ALIAS)).isEqualTo("Neutral text.");
        assertThat(both.textFor(SourceCatalog.LabelKind.REAL)).isEqualTo("Real text.");
    }

    // ---- rules 1 to 3 ----

    @Test
    @DisplayName("rule 1: 240 characters pass, 241, blank and multi-line texts refuse")
    void ruleOne() {
        assertThat(build(described("a".repeat(240))).entries()).hasSize(1);
        assertRefused(INVALID, 1, described("a".repeat(241)));
        assertRefused(INVALID, 1, described(""));
        assertRefused(INVALID, 1, described("   "));
        assertRefused(INVALID, 1, described("line one\nline two"));
        assertRefused(INVALID, 1, described("line one\rline two"));
    }

    @Test
    @DisplayName("rule 1 applies to the real description too")
    void ruleOneOnReal() {
        assertRefused(INVALID, 1, new DescribedAdapter("alpha", texts("Fine.", "a".repeat(241))));
        assertThat(build(new DescribedAdapter("alpha", texts("Fine.", "a".repeat(240)))).entries()).hasSize(1);
    }

    @Test
    @DisplayName("rule 2: control characters, markup, @ and brackets refuse; the allowed punctuation passes")
    void ruleTwo() {
        for (String bad : new String[] {"tab\there", "nul\u0000x", "<b>bold</b>", "a {b}", "a [b]", "a `b`", "a\\b",
                "write to me@here", "a & b", "a! b", "a? b", "a | b", "‮flip"}) {
            assertRefused(INVALID, 2, described(bad));
        }
        assertThat(build(described("Records (read-only): a, b; c_d/e's \"f\".")).entries()).hasSize(1);
    }

    @Test
    @DisplayName("rule 3: key-shaped, URL-shaped and host-shaped texts refuse")
    void ruleThree() {
        for (String bad : new String[] {
                "key sk-abcdefghijklmnopqrstuvwx",
                "account GB82WEST12345698765432",
                "id 1234567T",
                "see http://internal-thing",
                "see https://thing/path",
                "see www.thing",
                "runs on db-7.internal.example.org",
                "see thing.example"}) {
            assertRefused(INVALID, 3, described(bad));
        }
    }

    @Test
    @DisplayName("rule 3 also covers the real description")
    void ruleThreeOnReal() {
        assertRefused(INVALID, 3, new DescribedAdapter("alpha", texts("Fine.", "runs on db-7.example.org")));
    }

    @Test
    @DisplayName("rules run in order: a text failing rules 1 and 2 reports rule 1")
    void inOrder() {
        assertRefused(INVALID, 1, described("<b>\n</b>"));
        assertRefused(INVALID, 2, new DescribedAdapter("alpha", texts("<b>", "a".repeat(10))));
        assertRefused(INVALID, 1, new DescribedAdapter("alpha", texts("<b>", "")));
    }

    // ---- rule 4 ----

    @Test
    @DisplayName("rule 4: a description() that throws refuses without its message")
    void ruleFour() {
        DataSourceAdapter<?> throwing = new OldStyleAdapter("alpha") {
            @Override
            public SourceDescription description() {
                throw new IllegalArgumentException("SENTINEL text");
            }
        };
        assertRefused(INVALID, 4, throwing);
        assertThatThrownBy(() -> build(throwing)).hasMessageNotContaining("SENTINEL").hasNoCause();
    }

    // ---- rule 5 ----

    @Test
    @DisplayName("rule 5: the real name refuses in mixed case with punctuation")
    void ruleFiveRealName() {
        DataSourceAdapter<?> adapter = new DescribedAdapter("parcel-store", aliasText("Look in the PARCEL: Store, please."));
        assertRefused(LEAKS, 5, adapter);
        assertThat(build(new DescribedAdapter("parcel-store", aliasText("Look in the parcel store, please."))))
                .isNotNull();
    }

    @Test
    @DisplayName("rule 5: whole normalised names only - a half name passes")
    void ruleFiveHalfName() {
        assertThat(build(new DescribedAdapter("parcel-store", aliasText("Holds the parcel list."))).entries()).hasSize(1);
    }

    @Test
    @DisplayName("rule 5: the adapter's simple class name refuses")
    void ruleFiveClassName() {
        assertRefused(LEAKS, 5, new WidgetLedgerAdapter("alpha", aliasText("The WIDGET-ledger-ADAPTER data.")));
    }

    @Test
    @DisplayName("rule 5: the base-url host refuses in mixed case with punctuation")
    void ruleFiveHost() {
        SourceCatalog.build(List.of(new DescribedAdapter("alpha", aliasText("Fine."))), Map.of("alpha", "api.thing.example"));
        assertThatThrownBy(() -> SourceCatalog.build(
                List.of(new DescribedAdapter("alpha", aliasText("Served by API thing example, roughly."))),
                Map.of("alpha", "api.thing.example")))
                .hasMessage(LEAKS + "source alpha rule 5");
    }

    @Test
    @DisplayName("rule 5: another source's real name refuses, and a host of another source does not")
    void ruleFiveOtherSource() {
        DataSourceAdapter<?> a = new DescribedAdapter("alpha", aliasText("Like BETA, but older."));
        DataSourceAdapter<?> b = new OldStyleAdapter("beta");
        assertThatThrownBy(() -> build(a, b)).hasMessage(LEAKS + "source alpha rule 5");
        assertThatThrownBy(() -> build(b, a)).hasMessage(LEAKS + "source alpha rule 5");
    }

    @Test
    @DisplayName("rule 5 is not applied to the real description")
    void ruleFiveSkipsReal() {
        assertThat(build(new DescribedAdapter("alpha", texts("Neutral text.", "This is the alpha system."))).entries())
                .hasSize(1);
    }

    @Test
    @DisplayName("a name that normalises to nothing forbids nothing")
    void emptyNormalisedName() {
        assertThat(build(new DescribedAdapter("---", aliasText("Fine.")), new OldStyleAdapter("alpha")).entries())
                .hasSize(2);
    }

    // ---- rule 6 ----

    @Test
    @DisplayName("rule 6: identical alias and real texts refuse; different ones do not")
    void ruleSix() {
        assertRefused(INVALID, 6, new DescribedAdapter("alpha", texts("Same text.", "Same text.")));
        assertThat(build(new DescribedAdapter("alpha", texts("Same text.", "Same text"))).entries()).hasSize(1);
    }

    // ---- messages and logs ----

    @Test
    @DisplayName("no refusal message and no log line carries the text")
    void sentinelNeverEscapes() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            for (String bad : new String[] {"SENTINEL <b>", "SENTINEL\nSENTINEL", "SENTINEL " + "x".repeat(300),
                    "SENTINEL alpha", "SENTINEL sk-abcdefghijklmnopqrstuvwx"}) {
                assertThatThrownBy(() -> build(described(bad))).hasMessageNotContaining("SENTINEL");
            }
            assertThatThrownBy(() -> build(new DescribedAdapter("alpha", texts("SENTINEL a", "SENTINEL a"))))
                    .hasMessageNotContaining("SENTINEL");
            build(described("SENTINEL fine text."));
        } finally {
            root.detachAppender(appender);
        }
        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("SENTINEL"));
    }

    // ---- ordering and grammar ----

    @Test
    @DisplayName("entries come in byte order of the real name however adapters were registered")
    void orderIsStable() {
        List<DataSourceAdapter<?>> adapters = List.of(new OldStyleAdapter("delta"), new OldStyleAdapter("alpha"),
                new OldStyleAdapter("charlie"), new OldStyleAdapter("bravo"), new OldStyleAdapter("a-b"),
                new OldStyleAdapter("Zulu"));
        List<String> expected = List.of("Zulu", "a-b", "alpha", "bravo", "charlie", "delta");
        for (int seed = 0; seed < 20; seed++) {
            List<DataSourceAdapter<?>> shuffled = new ArrayList<>(adapters);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            SourceCatalog catalog = SourceCatalog.build(shuffled, Map.of());
            assertThat(catalog.entries()).extracting(SourceCatalog.Entry::name).containsExactlyElementsOf(expected);
            assertThat(catalog.generatedDescriptionSources()).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("the first failure is the same whatever the registration order")
    void firstFailureIsStable() {
        DataSourceAdapter<?> first = new DescribedAdapter("aaa", aliasText("a".repeat(241)));
        DataSourceAdapter<?> second = new DescribedAdapter("bbb", aliasText("<b>"));
        assertThatThrownBy(() -> build(first, second)).hasMessage(INVALID + "source aaa rule 1");
        assertThatThrownBy(() -> build(second, first)).hasMessage(INVALID + "source aaa rule 1");
    }

    @Test
    @DisplayName("name grammar: 64 characters conform, 65 do not, upper case, leading digit, underscore do not; nothing refuses")
    void nameGrammar() {
        SourceCatalog catalog = build(new OldStyleAdapter("a".repeat(64)), new OldStyleAdapter("a".repeat(65)),
                new OldStyleAdapter("Upper"), new OldStyleAdapter("1lead"), new OldStyleAdapter("under_score"),
                new OldStyleAdapter("ok-name"), new OldStyleAdapter("x"));
        assertThat(catalog.entries()).hasSize(7);
        assertThat(catalog.nonconformingNameCount()).isEqualTo(4);
        assertThat(build(new OldStyleAdapter("ok")).nonconformingNameCount()).isZero();
    }

    @Test
    @DisplayName("an unknown source has no entry, and the lists cannot be modified")
    void lookupAndImmutability() {
        SourceCatalog catalog = build(new OldStyleAdapter("alpha"));
        assertThat(catalog.entry("missing")).isEmpty();
        assertThatThrownBy(() -> catalog.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.generatedDescriptionSources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("described sources are not counted as generated")
    void generatedCount() {
        SourceCatalog catalog = build(described("Neutral text."), new OldStyleAdapter("beta"));
        assertThat(catalog.generatedDescriptionCount()).isEqualTo(1);
        assertThat(catalog.generatedDescriptionSources()).containsExactly("beta");
    }

    @Test
    @DisplayName("SourceVisibility.allowAll permits every source to every caller")
    void allowAll() {
        InvestigationContext caller = new InvestigationContext("p", "c", "case", Set.of());
        SourceVisibility visibility = SourceVisibility.allowAll();
        assertThat(visibility.permitted(caller, "alpha")).isTrue();
        assertThat(visibility.permitted(caller, "anything-else")).isTrue();
    }
}
