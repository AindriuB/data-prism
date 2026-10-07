package io.github.aindriub.dataprism.core.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CorrelationIdPolicyTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT = "b7ad6b7169203331";
    private static final String TP = "00-" + TRACE + "-" + PARENT + "-01";

    private static CorrelationIdPolicy defaults() {
        return CorrelationIdPolicy.opaque(CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN);
    }

    @Test
    @DisplayName("a pattern that does not compile is refused without echoing it")
    void badPattern() {
        assertThatThrownBy(() -> CorrelationIdPolicy.opaque("(unclosed-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CORRELATION_PATTERN")
                .hasMessageNotContaining("unclosed-secret");
    }

    @Test
    @DisplayName("the default accepts a UUID, 16-128 hex, and a traceparent")
    void defaultAccepts() {
        assertThat(defaults().validate("123e4567-e89b-12d3-a456-426614174000")).isPresent();
        assertThat(defaults().validate("a".repeat(16))).isPresent();
        assertThat(defaults().validate("a".repeat(128))).isPresent();
        assertThat(defaults().validate(TP)).isPresent();
    }

    @Test
    @DisplayName("the default rejects name-like tokens such as jane.doe")
    void defaultRejectsNames() {
        assertThat(defaults().validate("jane.doe")).isEmpty();
        assertThat(defaults().validate("a".repeat(15))).isEmpty();
        assertThat(defaults().validate("a".repeat(129))).isEmpty();
        assertThat(defaults().validate("")).isEmpty();
        assertThat(defaults().validate(null)).isEmpty();
    }

    @Test
    @DisplayName("the broad pattern is available when set explicitly")
    void broadExplicit() {
        assertThat(CorrelationIdPolicy.opaque("[A-Za-z0-9._:-]{1,128}").validate("jane.doe")).isPresent();
    }

    @Test
    @DisplayName("the ceiling rejects unsafe characters whatever the pattern allows")
    void ceilingCharacters() {
        CorrelationIdPolicy any = CorrelationIdPolicy.opaque(".*");
        for (String bad : List.of("a b", "a|b", "a\"b", "a@b", "a\nb", "aéb")) {
            assertThat(any.validate(bad)).as("rejected: %s", bad.length()).isEmpty();
        }
        assertThat(any.validate("ab")).isPresent();
    }

    @Test
    @DisplayName("the ceiling rejects more than 256 characters")
    void ceilingLength() {
        CorrelationIdPolicy any = CorrelationIdPolicy.opaque(".*");
        assertThat(any.validate("a".repeat(256))).isPresent();
        assertThat(any.validate("a".repeat(257))).isEmpty();
    }

    @Test
    @DisplayName("opaque matching is a full match")
    void fullMatch() {
        assertThat(CorrelationIdPolicy.opaque("b").validate("abc")).isEmpty();
        assertThat(CorrelationIdPolicy.opaque("b").validate("b")).isPresent();
    }

    @Test
    @DisplayName("traceparent mode accepts version 00 and exposes trace-id as value")
    void traceparentAccepts() {
        ExternalCorrelationId id = CorrelationIdPolicy.traceparent().validate(TP).orElseThrow();
        assertThat(id.value()).isEqualTo(TRACE);
        assertThat(id.traceparent()).contains(TP);
    }

    @Test
    @DisplayName("traceparent: all-zero trace-id is rejected")
    void zeroTrace() {
        assertThat(CorrelationIdPolicy.traceparent().validate("00-" + "0".repeat(32) + "-" + PARENT + "-01")).isEmpty();
    }

    @Test
    @DisplayName("traceparent: all-zero parent-id is rejected")
    void zeroParent() {
        assertThat(CorrelationIdPolicy.traceparent().validate("00-" + TRACE + "-" + "0".repeat(16) + "-01")).isEmpty();
    }

    @Test
    @DisplayName("traceparent: uppercase hex is rejected")
    void upper() {
        assertThat(CorrelationIdPolicy.traceparent().validate(TP.toUpperCase())).isEmpty();
        assertThat(CorrelationIdPolicy.traceparent().validate("00-" + TRACE.toUpperCase() + "-" + PARENT + "-01"))
                .isEmpty();
    }

    @Test
    @DisplayName("traceparent: a version other than 00 is rejected")
    void version() {
        assertThat(CorrelationIdPolicy.traceparent().validate("01-" + TRACE + "-" + PARENT + "-01")).isEmpty();
        assertThat(CorrelationIdPolicy.traceparent().validate("ff-" + TRACE + "-" + PARENT + "-01")).isEmpty();
    }

    @Test
    @DisplayName("traceparent: extra trailing fields are rejected")
    void trailing() {
        assertThat(CorrelationIdPolicy.traceparent().validate(TP + "-extra")).isEmpty();
        assertThat(CorrelationIdPolicy.traceparent().validate(TP + "-01")).isEmpty();
    }

    @Test
    @DisplayName("an opaque value is not a traceparent")
    void opaqueModeNoTraceparent() {
        ExternalCorrelationId id = defaults().validate("a".repeat(32)).orElseThrow();
        assertThat(id.value()).isEqualTo("a".repeat(32));
        assertThat(id.traceparent()).isEmpty();
        assertThat(id.childTraceparent()).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("the only way to obtain an ExternalCorrelationId is through a policy")
    void noPublicConstructor() {
        assertThat(ExternalCorrelationId.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(ExternalCorrelationId.class.getModifiers())).isTrue();
        assertThat(ExternalCorrelationId.class.isRecord()).isFalse();
    }

    @Test
    @DisplayName("a child traceparent keeps trace-id and flags and changes the parent-id")
    void child() {
        ExternalCorrelationId id = CorrelationIdPolicy.traceparent()
                .validate("00-" + TRACE + "-" + PARENT + "-03").orElseThrow();
        String child = id.childTraceparent().orElseThrow();
        assertThat(child).matches("00-" + TRACE + "-[0-9a-f]{16}-03");
        String parent = child.split("-")[2];
        assertThat(parent).isNotEqualTo(PARENT).isNotEqualTo("0".repeat(16));
        assertThat(CorrelationIdPolicy.traceparent().validate(child)).isPresent();
    }

    @Test
    @DisplayName("resolve: no values is absent")
    void resolveAbsent() {
        assertThat(InboundCorrelation.resolve(List.of(), defaults())).isEqualTo(InboundCorrelation.absent());
        assertThat(InboundCorrelation.resolve(null, defaults())).isEqualTo(InboundCorrelation.absent());
    }

    @Test
    @DisplayName("resolve: an invalid value is rejected")
    void resolveRejected() {
        assertThat(InboundCorrelation.resolve(List.of("jane.doe"), defaults()))
                .isEqualTo(InboundCorrelation.rejected());
    }

    @Test
    @DisplayName("resolve: more than one value is rejected even if each is valid")
    void resolveMany() {
        assertThat(InboundCorrelation.resolve(List.of("a".repeat(16), "b".repeat(16)), defaults()))
                .isEqualTo(InboundCorrelation.rejected());
    }

    @Test
    @DisplayName("resolve: a valid value is present")
    void resolvePresent() {
        InboundCorrelation r = InboundCorrelation.resolve(List.of("a".repeat(16)), defaults());
        assertThat(r.isPresent()).isTrue();
        assertThat(r.id().orElseThrow().value()).isEqualTo("a".repeat(16));
    }

    @Test
    @DisplayName("rejected carries no copy of the rejected text")
    void rejectedNoCopy() {
        InboundCorrelation r = InboundCorrelation.resolve(List.of("jane.doe"), defaults());
        assertThat(r.toString()).doesNotContain("jane");
        assertThat(r.isRejected()).isTrue();
        assertThat(r.id()).isEmpty();
    }
}
