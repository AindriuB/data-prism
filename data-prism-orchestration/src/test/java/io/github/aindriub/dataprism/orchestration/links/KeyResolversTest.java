package io.github.aindriub.dataprism.orchestration.links;

import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.KeyResolver;
import io.github.aindriub.dataprism.core.spi.ResolutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyResolversTest {

    private static KeyNamespace ns(String value) {
        return new KeyNamespace(value);
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
                return new Resolution(List.of(), false);
            }
        };
    }

    @Test
    void refusesTwoResolversForOnePair() {
        assertThatThrownBy(() -> new KeyResolvers(List.of(resolver("one", "a", "b"), resolver("two", "a", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("DUPLICATE_KEY_RESOLVER");
    }

    @Test
    void acceptsTheSameNamespacesInTheOtherDirectionAndOtherTargets() {
        KeyResolvers registry = new KeyResolvers(List.of(resolver("one", "a", "b"), resolver("two", "b", "a"),
                resolver("three", "a", "c")));
        assertThat(registry.from(ns("a"))).extracting(KeyResolver::name).containsExactly("one", "three");
    }

    @Test
    void refusesFromEqualToTo() {
        assertThatThrownBy(() -> new KeyResolvers(List.of(resolver("one", "a", "a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_KEY_RESOLVER");
    }

    @Test
    void refusesANameOutsideTheGrammar() {
        for (String bad : new String[] {"", "A", "1a", "a_b", "a b", "a".repeat(33), "a\n", "é"}) {
            assertThatThrownBy(() -> new KeyResolvers(List.of(resolver(bad, "a", "b"))))
                    .as("name '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("INVALID_KEY_RESOLVER");
        }
        assertThatThrownBy(() -> new KeyResolvers(List.of(resolver(null, "a", "b"))))
                .hasMessageStartingWith("INVALID_KEY_RESOLVER");
    }

    @Test
    void acceptsTheLongestAndShortestNames() {
        new KeyResolvers(List.of(resolver("a".repeat(32), "a", "b"), resolver("z", "a", "c")));
    }

    @Test
    void theRefusalMessageNamesNeitherKeysNorNamespaceValues() {
        assertThatThrownBy(() -> new KeyResolvers(List.of(resolver("alpha-x", "a", "b"), resolver("beta-y", "a", "b"))))
                .hasMessageNotContaining("alpha-x").hasMessageNotContaining("beta-y");
    }

    @Test
    void fromReturnsAscendingNameOrderWhateverTheRegistrationOrder() {
        KeyResolvers registry = new KeyResolvers(List.of(resolver("zeta", "a", "b"), resolver("alpha", "a", "c"),
                resolver("mid", "a", "d"), resolver("other", "x", "y")));
        assertThat(registry.from(ns("a"))).extracting(KeyResolver::name).containsExactly("alpha", "mid", "zeta");
        assertThat(registry.from(ns("nothing"))).isEmpty();
    }

    @Test
    void unusedPairsAreThoseWhoseNamespacesNoFieldOrInputKeyUses() {
        KeyResolvers registry = new KeyResolvers(List.of(resolver("one", "a", "b"), resolver("two", "c", "d"),
                resolver("three", "a", "d")));
        assertThat(registry.unusedPairs(Set.of(ns("a"), ns("b"))))
                .containsExactly(new KeyResolvers.Pair(ns("a"), ns("d")), new KeyResolvers.Pair(ns("c"), ns("d")));
        assertThat(registry.unusedPairs(Set.of(ns("a"), ns("b"), ns("c"), ns("d")))).isEmpty();
        assertThat(registry.unusedPairs(Set.of())).hasSize(3);
    }

    @Test
    void anEmptyRegistryIsValid() {
        assertThat(new KeyResolvers(List.of()).unusedPairs(Set.of())).isEmpty();
    }
}
