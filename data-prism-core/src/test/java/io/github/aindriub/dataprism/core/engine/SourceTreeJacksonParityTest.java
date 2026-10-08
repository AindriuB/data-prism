package io.github.aindriub.dataprism.core.engine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidDefinitionException;

import java.math.BigDecimal;
import java.time.*;
import java.util.Date;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Jackson 3 changed defaults that touch a source tree; each is pinned back to the Jackson 2 value. */
class SourceTreeJacksonParityTest {

    public record Money(BigDecimal amount) {
    }

    public record Pair(String zeta, String alpha) {
    }

    public enum Status {
        ACTIVE, CLOSED;

        @Override
        public String toString() {
            return this == ACTIVE ? "Active customer" : "Closed account";
        }
    }

    public record Account(Status status, Map<Status, String> notes) {
    }

    public record Stamp(Date at) {
    }

    public record Moments(LocalDate day, LocalDateTime local, Instant instant, OffsetDateTime offset,
                          ZonedDateTime zoned, Duration duration) {
    }

    public record Maybe(Optional<String> present, Optional<String> absent) {
    }

    public static final class Empty {
    }

    @Test
    void trailingZerosOfADecimalAreStripped() {
        JsonNode tree = SourceTree.of(new Money(new BigDecimal("1.50")));
        assertThat(tree.get("amount").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(tree.get("amount").decimalValue().toPlainString()).isEqualTo("1.5");
    }

    @Test
    void propertiesKeepDeclaredOrder() {
        assertThat(SourceTree.of(new Pair("z", "a")).propertyNames()).containsExactly("zeta", "alpha");
    }

    @Test
    void anEmptyBeanIsRefusedNotReadAsAnEmptyObject() {
        assertThatThrownBy(() -> SourceTree.of(new Empty())).isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void anEnumValueIsItsNameNotItsToString() {
        JsonNode tree = SourceTree.of(new Account(Status.ACTIVE, Map.of()));
        assertThat(tree.get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void anEnumMapKeyIsItsNameNotItsToString() {
        Map<Status, String> notes = new LinkedHashMap<>();
        notes.put(Status.ACTIVE, "a");
        notes.put(Status.CLOSED, "c");
        JsonNode tree = SourceTree.of(new Account(Status.CLOSED, notes));
        assertThat(tree.get("notes").propertyNames()).containsExactly("ACTIVE", "CLOSED");
    }

    @Test
    void aLegacyDateIsEpochMillisAsInJackson2() {
        JsonNode tree = SourceTree.of(new Stamp(new Date(1_700_000_000_123L)));
        assertThat(tree.get("at").isNumber()).isTrue();
        assertThat(tree.get("at").longValue()).isEqualTo(1_700_000_000_123L);
    }

    @Test
    void javaTimeTypesAreIsoText() {
        JsonNode t = SourceTree.of(new Moments(LocalDate.of(1980, 4, 12), LocalDateTime.of(1980, 4, 12, 6, 30),
                Instant.parse("2026-10-08T12:00:00Z"), OffsetDateTime.parse("2026-10-08T12:00:00+02:00"),
                ZonedDateTime.parse("2026-10-08T12:00:00+02:00[Europe/Paris]"), Duration.ofMinutes(90)));
        assertThat(t.get("day").asString()).isEqualTo("1980-04-12");
        assertThat(t.get("local").asString()).isEqualTo("1980-04-12T06:30:00");
        assertThat(t.get("instant").asString()).isEqualTo("2026-10-08T12:00:00Z");
        assertThat(t.get("offset").asString()).isEqualTo("2026-10-08T12:00:00+02:00");
        assertThat(t.get("zoned").asString()).isEqualTo("2026-10-08T12:00:00+02:00");
        assertThat(t.get("duration").asString()).isEqualTo("PT1H30M");
    }

    @Test
    void aDateMapKeyIsWrittenAsInJackson2() {
        JsonNode t = SourceTree.of(Map.of(new Date(0L), 1));
        assertThat(t.propertyNames()).containsExactly("1970-01-01T00:00:00.000+00:00");
    }

    @Test
    void anOptionalIsUnwrappedAndAnEmptyOneIsNull() {
        JsonNode t = SourceTree.of(new Maybe(Optional.of("x"), Optional.empty()));
        assertThat(t.get("present").asString()).isEqualTo("x");
        assertThat(t.get("absent").isNull()).isTrue();
    }
}
