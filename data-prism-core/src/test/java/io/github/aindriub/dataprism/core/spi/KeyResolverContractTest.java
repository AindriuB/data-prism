package io.github.aindriub.dataprism.core.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyResolverContractTest {

    @Test
    void theInterfaceHasExactlyTheFourMethods() {
        Set<String> names = Arrays.stream(KeyResolver.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("name", "from", "to", "resolve");
        assertThat(Arrays.stream(KeyResolver.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("resolve")).findFirst().orElseThrow().getParameterTypes())
                .containsExactly(String.class, ResolutionContext.class, int.class);
    }

    @Test
    void resolutionCopiesItsKeys() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        KeyResolver.Resolution resolution = new KeyResolver.Resolution(source, false);
        source.add("c");
        assertThat(resolution.keys()).containsExactly("a", "b");
        assertThatThrownBy(() -> resolution.keys().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolutionKeepsANullElementSoTheInvokerCanClassifyIt() {
        KeyResolver.Resolution resolution = new KeyResolver.Resolution(Arrays.asList("a", null), false);
        assertThat(resolution.keys()).containsExactly("a", null);
    }

    @Test
    void resolutionAcceptsMoreWithNoKeysSoTheInvokerCanClassifyIt() {
        KeyResolver.Resolution resolution = new KeyResolver.Resolution(List.of(), true);
        assertThat(resolution.more()).isTrue();
        assertThat(resolution.keys()).isEmpty();
    }

    @Test
    void resolutionRejectsNullKeys() {
        assertThatThrownBy(() -> new KeyResolver.Resolution(null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void contextCarriesOnlyScopePrincipalPurposeAndDeadline() {
        assertThat(Arrays.stream(ResolutionContext.class.getRecordComponents())
                .map(c -> c.getName() + ":" + c.getType().getSimpleName()).toList())
                .containsExactly("scopeId:String", "principalId:String", "purpose:String", "deadline:Instant");
        ResolutionContext context = new ResolutionContext("s", "p", "q", Instant.EPOCH);
        assertThat(context.deadline()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void exceptionCarriesOnlyAReasonAndAConstantMessage() {
        assertThat(KeyResolutionException.Reason.values()).extracting(Enum::name)
                .containsExactly("UNAVAILABLE", "TIMEOUT", "REJECTED", "TOO_MANY");
        KeyResolutionException a = new KeyResolutionException(KeyResolutionException.Reason.TIMEOUT);
        KeyResolutionException b = new KeyResolutionException(KeyResolutionException.Reason.REJECTED);
        assertThat(a.reason()).isEqualTo(KeyResolutionException.Reason.TIMEOUT);
        assertThat(a.getMessage()).isEqualTo(b.getMessage()).doesNotContain("TIMEOUT");
        assertThat(a.getCause()).isNull();
    }
}
