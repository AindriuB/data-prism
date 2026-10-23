package io.github.aindriub.dataprism.core.spi;

import io.github.aindriub.dataprism.core.spi.IdentityResolver.CanonicalId;
import io.github.aindriub.dataprism.core.spi.IdentityResolver.SourceRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityResolverKeyResolversTest {

    private static final ResolutionContext CONTEXT = new ResolutionContext("scope", "principal", "purpose",
            Instant.EPOCH);

    private static final class Recording implements IdentityResolver {
        final List<SourceRef> resolved = new ArrayList<>();
        final List<List<String>> expandedFor = new ArrayList<>();

        @Override
        public CanonicalId resolve(SourceRef ref) {
            resolved.add(ref);
            return new CanonicalId("canon-" + ref.key());
        }

        @Override
        public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
            expandedFor.add(sourceNames);
            return List.of(new SourceRef("S2", "x-" + id.value()), new SourceRef("S2", "y-" + id.value()));
        }
    }

    @Test
    void returnsExactlyTheKeysExpandReturnsForTheSecondSource() {
        Recording identity = new Recording();
        KeyResolver resolver = IdentityResolverKeyResolvers.of(identity, "pair", "S1", new KeyNamespace("left"),
                "S2", new KeyNamespace("right"));
        KeyResolver.Resolution resolution = resolver.resolve("k1", CONTEXT, 5);
        assertThat(resolution.keys()).containsExactly("x-canon-k1", "y-canon-k1");
        assertThat(resolution.more()).isFalse();
        assertThat(identity.resolved).containsExactly(new SourceRef("S1", "k1"));
        assertThat(identity.expandedFor).containsExactly(List.of("S2"));
        assertThat(resolver.name()).isEqualTo("pair");
        assertThat(resolver.from()).isEqualTo(new KeyNamespace("left"));
        assertThat(resolver.to()).isEqualTo(new KeyNamespace("right"));
    }

    @Test
    void refusesToTruncateSilently() {
        KeyResolver resolver = IdentityResolverKeyResolvers.of(new Recording(), "pair", "S1",
                new KeyNamespace("left"), "S2", new KeyNamespace("right"));
        KeyResolver.Resolution resolution = resolver.resolve("k1", CONTEXT, 1);
        assertThat(resolution.keys()).hasSize(1);
        assertThat(resolution.more()).isTrue();
    }

    @Test
    void anIdentityResolverFailureIsUnavailableWithNoMessage() {
        IdentityResolver failing = new IdentityResolver() {
            @Override
            public CanonicalId resolve(SourceRef ref) {
                throw new IllegalStateException("secret-" + ref.key());
            }

            @Override
            public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
                return List.of();
            }
        };
        KeyResolver resolver = IdentityResolverKeyResolvers.of(failing, "pair", "S1", new KeyNamespace("left"),
                "S2", new KeyNamespace("right"));
        assertThatThrownBy(() -> resolver.resolve("k1", CONTEXT, 5))
                .isInstanceOf(KeyResolutionException.class)
                .satisfies(e -> {
                    assertThat(((KeyResolutionException) e).reason())
                            .isEqualTo(KeyResolutionException.Reason.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("secret");
                    assertThat(e.getCause()).isNull();
                });
    }
}
