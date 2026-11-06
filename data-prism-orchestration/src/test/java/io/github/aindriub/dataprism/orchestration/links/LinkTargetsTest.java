package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.link.LinkRelation;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.catalog.RealNamesPolicy;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.orchestration.catalog.SourceLabels;
import io.github.aindriub.dataprism.orchestration.catalog.SourceVisibility;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Targets come from declarations only: exact namespace, one resolver, direct first, never the origin. */
class LinkTargetsTest {

    private static KeyNamespace ns(String value) {
        return new KeyNamespace(value);
    }

    private static DataSourceAdapter<Object> adapter(String name, String... inputKeys) {
        List<KeyNamespace> keys = new ArrayList<>();
        for (String key : inputKeys) {
            keys.add(ns(key));
        }
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public Class<Object> responseType() {
                return Object.class;
            }

            @Override
            public Object fetch(DataRequest request) {
                throw new AssertionError("a target is never read to decide a link");
            }

            @Override
            public SourceDescription description() {
                return new SourceDescription(java.util.Set.of(), null, null, keys, null, null);
            }
        };
    }

    private static DataSourceAdapter<Object> typed(String name, java.util.Set<String> types,
                                                   Map<KeyNamespace, String> mapping, String... inputKeys) {
        List<KeyNamespace> keys = new ArrayList<>();
        for (String key : inputKeys) {
            keys.add(ns(key));
        }
        DataSourceAdapter<Object> base = adapter(name);
        return new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public Class<Object> responseType() {
                return Object.class;
            }

            @Override
            public Object fetch(DataRequest request) {
                throw new AssertionError("a target is never read to decide a link");
            }

            @Override
            public SourceDescription description() {
                return new SourceDescription(types, null, null, keys, null, null, mapping);
            }
        };
    }

    private static KeyResolver resolver(String name, String from, String to) {
        return new KeyResolver() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public KeyNamespace from() {
                return ns(from);
            }

            @Override
            public KeyNamespace to() {
                return ns(to);
            }

            @Override
            public Resolution resolve(String key, ResolutionContext context, int maxResults) {
                throw new AssertionError("a resolver is never called to decide a link");
            }
        };
    }

    private static SourceLabels labels(List<DataSourceAdapter<?>> adapters, SourceVisibility visibility) {
        return SourceLabels.forRequest(SourceCatalog.build(adapters, Map.of()), visibility,
                new SourceAliasing(new HmacValueTokenSource(StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data"))),
                RealNamesPolicy.none(), LinkBuilderTest.caller(), LinkBuilderTest.context());
    }

    private static List<LinkTargets.Target> targets(String namespace, SourceLabels labels, KeyResolver... resolvers) {
        return LinkTargets.forNamespace(ns(namespace), "origin-api", labels, new KeyResolvers(List.of(resolvers)));
    }

    @Test
    void directOnExactMatchAndTranslatedThroughOneResolverAndNeverTheOrigin() {
        SourceLabels labels = labels(List.of(adapter("origin-api", "parcel-ref"), adapter("direct-api", "parcel-ref"),
                adapter("route-api", "route-code")), SourceVisibility.allowAll());

        List<LinkTargets.Target> found = targets("parcel-ref", labels, resolver("p-to-r", "parcel-ref", "route-code"));

        assertThat(found).extracting(LinkTargets.Target::name).containsExactlyInAnyOrder("direct-api", "route-api");
        assertThat(found).filteredOn(t -> t.name().equals("direct-api")).extracting(LinkTargets.Target::relation)
                .containsExactly(LinkRelation.DIRECT);
        assertThat(found).filteredOn(t -> t.name().equals("route-api")).extracting(LinkTargets.Target::resolverName)
                .containsExactly("p-to-r");
    }

    @Test
    void directWinsAndATargetIsListedOnce() {
        SourceLabels labels = labels(List.of(adapter("origin-api"), adapter("both-api", "route-code", "parcel-ref")),
                SourceVisibility.allowAll());

        List<LinkTargets.Target> found = targets("parcel-ref", labels, resolver("p-to-r", "parcel-ref", "route-code"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).relation()).isEqualTo(LinkRelation.DIRECT);
    }

    @Test
    void twoResolversInARowGiveNoTarget() {
        SourceLabels labels = labels(List.of(adapter("origin-api"), adapter("end-api", "end-ref")),
                SourceVisibility.allowAll());

        assertThat(targets("parcel-ref", labels, resolver("one", "parcel-ref", "mid-ref"),
                resolver("two", "mid-ref", "end-ref"))).isEmpty();
    }

    @Test
    void aSourceTheCallerMayNotSeeIsNotATarget() {
        SourceLabels labels = labels(List.of(adapter("origin-api"), adapter("hidden-api", "parcel-ref")),
                (caller, name) -> !name.equals("hidden-api"));

        assertThat(targets("parcel-ref", labels)).isEmpty();
    }

    @Test
    void aNameOver64BytesIsListedAsIneligible() {
        SourceLabels labels = labels(List.of(adapter("origin-api"), adapter("n".repeat(65), "parcel-ref")),
                SourceVisibility.allowAll());

        List<LinkTargets.Target> found = targets("parcel-ref", labels);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).eligible()).isFalse();
    }

    @Test
    void targetsComeInAscendingLabelOrder() {
        SourceLabels labels = labels(List.of(adapter("origin-api"), adapter("a-api", "parcel-ref"),
                adapter("b-api", "parcel-ref"), adapter("c-api", "parcel-ref")), SourceVisibility.allowAll());

        List<String> found = targets("parcel-ref", labels).stream().map(LinkTargets.Target::label).toList();

        assertThat(found).isSorted().hasSize(3);
    }

    @Test
    void theTypeIsDeclaredOneMappedOrTheSentinelAndAnUnmappedMultiTypeSourceIsIneligible() {
        SourceLabels labels = labels(List.of(adapter("origin-api"), adapter("none-api", "parcel-ref"),
                typed("one-api", java.util.Set.of("PARCEL"), Map.of(), "parcel-ref"),
                typed("mapped-api", java.util.Set.of("DEPOT", "PARCEL"), Map.of(ns("parcel-ref"), "PARCEL"),
                        "depot-code", "parcel-ref"),
                typed("loose-api", java.util.Set.of("DEPOT", "PARCEL"), Map.of(), "depot-code", "parcel-ref")),
                SourceVisibility.allowAll());

        List<LinkTargets.Target> found = targets("parcel-ref", labels);

        assertThat(found).filteredOn(t -> t.name().equals("none-api")).extracting(LinkTargets.Target::entityType)
                .containsExactly("entity");
        assertThat(found).filteredOn(t -> t.name().equals("one-api")).extracting(LinkTargets.Target::entityType)
                .containsExactly("PARCEL");
        assertThat(found).filteredOn(t -> t.name().equals("mapped-api")).extracting(LinkTargets.Target::entityType)
                .containsExactly("PARCEL");
        assertThat(found).filteredOn(t -> t.name().equals("loose-api")).extracting(LinkTargets.Target::eligible)
                .containsExactly(false);
    }

    @Test
    void aTranslatedTargetIsTypedByTheNamespaceItReadsNotTheOneItWasIssuedFrom() {
        SourceLabels labels = labels(List.of(adapter("origin-api"),
                typed("hub-api", java.util.Set.of("DEPOT", "PARCEL"),
                        Map.of(ns("route-code"), "PARCEL", ns("parcel-ref"), "DEPOT"), "route-code", "parcel-ref")),
                SourceVisibility.allowAll());

        List<LinkTargets.Target> found = targets("depot-code", labels, resolver("d-to-r", "depot-code", "route-code"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).entityType()).isEqualTo("PARCEL");
    }
}
