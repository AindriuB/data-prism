package io.github.aindriub.dataprism.core.engine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    public record Box(Object v) {
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
    void utcAndMillisecondJavaTimeValuesAreIsoText() {
        assertThat(text(OffsetDateTime.parse("2026-10-08T12:00:00Z"))).isEqualTo("2026-10-08T12:00:00Z");
        assertThat(text(ZonedDateTime.parse("2026-10-08T12:00:00Z[UTC]"))).isEqualTo("2026-10-08T12:00:00Z");
        assertThat(text(Instant.parse("2026-10-08T12:00:00.120Z"))).isEqualTo("2026-10-08T12:00:00.120Z");
    }

    public record Flagged(boolean isFlag, URL URL) {
    }

    public record Shaped(@JsonFormat(shape = JsonFormat.Shape.STRING) Date d) {
    }

    public record Formatted(@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd", timezone = "UTC") Date d) {
    }

    private static String text(Object value) {
        return SourceTree.of(new Box(value)).get("v").asString();
    }

    private static String json(Object value) {
        return SourceTree.of(new Box(value)).get("v").toString();
    }

    @Test
    void legacyDateTypesAreEpochMillisOrTextAsInJackson2() {
        Calendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(1_700_000_000_123L);
        assertThat(json(new java.sql.Timestamp(1_700_000_000_123L))).isEqualTo("1700000000123");
        assertThat(json(new java.sql.Date(1_700_000_000_123L))).isEqualTo("1700000000123");
        assertThat(json(java.sql.Time.valueOf("12:30:00"))).isEqualTo("\"12:30:00\"");
        assertThat(json(utc)).isEqualTo("1700000000123");
        assertThat(SourceTree.of(new Box(Map.of(new Date(0L), 1))).get("v").propertyNames())
                .containsExactly("1970-01-01T00:00:00.000+00:00");
        assertThat(SourceTree.of(new Box(Map.of(utc, 1))).get("v").propertyNames()).containsExactly("2023-11-14T22:13:20.123+00:00");
    }

    @Test
    void aJsonFormatOnADatePropertyIsHonoured() {
        assertThat(SourceTree.of(new Formatted(new Date(1_700_000_000_123L))).get("d").asString())
                .isEqualTo("2023-11-14");
    }

    @Test
    void jdkValueTypesMatchJackson2() {
        assertThat(json(TimeZone.getTimeZone("Europe/Dublin"))).isEqualTo("\"Europe/Dublin\"");
        assertThat(json(Locale.forLanguageTag("en-IE"))).isEqualTo("\"en_IE\"");
        assertThat(json(Locale.forLanguageTag("sr-Latn-RS"))).isEqualTo("\"sr_RS_#Latn\"");
        assertThat(SourceTree.of(new Box(Map.of(Locale.forLanguageTag("en-IE"), 1))).get("v").propertyNames()).containsExactly("en_IE");
        assertThat(json(Path.of("/tmp/x"))).isEqualTo("\"file:///tmp/x\"");
        assertThat(json(new File("/tmp/x"))).isEqualTo("\"/tmp/x\"");
        assertThat(json(URI.create("http://h/x"))).isEqualTo("\"http://h/x\"");
        assertThat(json(urlOf("http://h/x"))).isEqualTo("\"http://h/x\"");
        assertThat(json(new byte[]{1, 2, 3})).isEqualTo("\"AQID\"");
        assertThat(json(new char[]{'a', 'b'})).isEqualTo("\"ab\"");
        assertThat(json(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isEqualTo("\"00000000-0000-0000-0000-000000000001\"");
        assertThat(json(Currency.getInstance("EUR"))).isEqualTo("\"EUR\"");
        assertThat(json(StandardCharsets.UTF_8)).isEqualTo("\"UTF-8\"");
        assertThat(json(String.class)).isEqualTo("\"java.lang.String\"");
        assertThat(json(java.util.regex.Pattern.compile("a+"))).isEqualTo("\"a+\"");
        assertThat(json(new java.util.concurrent.atomic.AtomicInteger(3))).isEqualTo("3");
        assertThat(json(new StringBuilder("sb"))).isEqualTo("\"sb\"");
        assertThat(json(inet("127.0.0.1"))).isEqualTo("\"127.0.0.1\"");
    }

    @Test
    void decimalsKeepJackson2Forms() {
        assertThat(json(new BigDecimal("1E+3"))).isEqualTo("1E+3");
        assertThat(json(new BigDecimal("1.50"))).isEqualTo("1.5");
        assertThat(json(new BigDecimal("0.000"))).isEqualTo("0");
        assertThat(json(BigDecimal.ZERO)).isEqualTo("0");
    }

    @Test
    void recordComponentsKeepTheirNames() {
        assertThat(SourceTree.of(new Flagged(true, urlOf("http://h"))).propertyNames())
                .containsExactly("isFlag", "URL");
    }

    @Test
    void optionalsAreUnwrapped() {
        assertThat(json(OptionalInt.of(3))).isEqualTo("3");
        assertThat(json(java.util.Optional.of(3))).isEqualTo("3");
    }

    @SuppressWarnings("deprecation")
    private static URL urlOf(String value) {
        try {
            return new URL(value);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress inet(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aDateMapKeyIsWrittenAsInJackson2() {
        JsonNode t = SourceTree.of(new Box(Map.of(new Date(0L), 1))).get("v");
        assertThat(t.propertyNames()).containsExactly("1970-01-01T00:00:00.000+00:00");
    }

    @Test
    void anOptionalIsUnwrappedAndAnEmptyOneIsNull() {
        JsonNode t = SourceTree.of(new Maybe(Optional.of("x"), Optional.empty()));
        assertThat(t.get("present").asString()).isEqualTo("x");
        assertThat(t.get("absent").isNull()).isTrue();
    }

    @Test
    void aMonthIsItsNameAsValueAndKey() {
        assertThat(json(Month.OCTOBER)).isEqualTo("\"OCTOBER\"");
        assertThat(SourceTree.of(new Box(Map.of(Month.OCTOBER, 1))).get("v").propertyNames())
                .containsExactly("OCTOBER");
        assertThat(json(DayOfWeek.MONDAY)).isEqualTo("\"MONDAY\"");
    }

    @Test
    void aJsonFormatStringDateWithoutAPatternCarriesAColonOffsetForUtc() {
        assertThat(SourceTree.of(new Shaped(new Date(1_700_000_000_123L))).get("d").asString())
                .isEqualTo("2023-11-14T22:13:20.123+00:00");
    }
}
