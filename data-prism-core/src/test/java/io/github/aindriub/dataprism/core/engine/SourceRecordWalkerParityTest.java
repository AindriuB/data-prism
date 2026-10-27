package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walker's tree equals the inspecting reader's plain {@code valueToTree} of the same value, through the one
 * package-private seam {@link SourceTree#referenceTree}, over the whole {@link SourceTreeJacksonParityTest}
 * corpus and the annotation-free shapes the engine tests use (task 288).
 *
 * <p>The intended divergences are listed at the end, each with its reason, and there are only these: an
 * explicit accessor whose value differs from its field; an enum Jackson would not write by {@code name()}, as a
 * value or a key; the {@code @JsonFormat} cases ({@code Shaped}, {@code Formatted}); the reference and holder
 * types, now refused. Any other divergence invokes the documented fallback.
 */
class SourceRecordWalkerParityTest {

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

    public record Maybe(Optional<String> present, Optional<String> absent, OptionalInt i, OptionalLong l,
                        OptionalDouble d) {
    }

    public record Box(Object v) {
    }

    public record Flagged(boolean isFlag, java.net.URL URL) {
    }

    public record Scalars(String s, int i, long l, short sh, byte b, float f, double d, boolean z, char c,
                          Integer boxed, Long boxedLong, Double boxedDouble, Boolean boxedBoolean,
                          BigDecimal decimal, BigInteger big, String nothing) {
    }

    public record Containers(List<String> list, Set<Integer> set, Map<String, Integer> map, String[] array,
                             int[] ints, long[] longs, List<List<String>> nested, Map<String, List<Integer>> lists,
                             Optional<List<String>> optionalList, List<Optional<String>> optionalElements,
                             byte[] bytes, char[] chars) {
    }

    public record Inner(String name, Status status) {
    }

    public record Records(Inner inner, List<Inner> list, Inner[] array, Optional<Inner> optional, Inner nothing) {
    }

    public record Deep(Records records, Map<String, Object> attrs) {
    }

    private static void assertParity(Object value) {
        JsonNode walked = SourceTree.of(value);
        JsonNode reference = SourceTree.referenceTree(value);
        // Compared as text: a byte[] leaf is a POJONode, which is equal only to a node holding the same array.
        assertThat(walked.toString()).as(value.getClass().getSimpleName()).isEqualTo(reference.toString());
    }

    private static InetAddress inet(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static java.net.URL urlOf(String value) {
        try {
            return new java.net.URL(value);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("records of scalars, java.time types, optionals and enums")
    void scalarsAndTime() {
        assertParity(new Money(new BigDecimal("1.50")));
        assertParity(new Money(new BigDecimal("1E+3")));
        assertParity(new Money(new BigDecimal("0.000")));
        assertParity(new Pair("z", "a"));
        assertParity(new Account(Status.ACTIVE, Map.of()));
        Map<Status, String> notes = new LinkedHashMap<>();
        notes.put(Status.ACTIVE, "a");
        notes.put(Status.CLOSED, "c");
        assertParity(new Account(Status.CLOSED, notes));
        assertParity(new Stamp(new Date(1_700_000_000_123L)));
        assertParity(new Moments(LocalDate.of(1980, 4, 12), LocalDateTime.of(1980, 4, 12, 6, 30),
                Instant.parse("2026-10-08T12:00:00Z"), OffsetDateTime.parse("2026-10-08T12:00:00+02:00"),
                ZonedDateTime.parse("2026-10-08T12:00:00+02:00[Europe/Paris]"), Duration.ofMinutes(90)));
        assertParity(new Moments(LocalDate.of(1980, 4, 12), LocalDateTime.of(1980, 4, 12, 6, 30),
                Instant.parse("2026-10-08T12:00:00.120Z"), OffsetDateTime.parse("2026-10-08T12:00:00Z"),
                ZonedDateTime.parse("2026-10-08T12:00:00Z[UTC]"), Duration.ZERO));
        assertParity(new Maybe(Optional.of("x"), Optional.empty(), OptionalInt.of(3), OptionalLong.empty(),
                OptionalDouble.of(1.5)));
        assertParity(new Flagged(true, urlOf("http://h")));
        assertParity(new Scalars("s", 1, 2L, (short) 3, (byte) 4, 1.5f, 2.5d, true, 'c', 5, 6L, 7.5d, false,
                new BigDecimal("1.50"), BigInteger.TEN, null));
    }

    @Test
    @DisplayName("every JDK value type the base commit wrote, as an Object component")
    void jdkValueTypes() {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(1_700_000_000_123L);
        List<Object> values = List.of(TimeZone.getTimeZone("Europe/Dublin"), Locale.forLanguageTag("en-IE"),
                Locale.forLanguageTag("sr-Latn-RS"), Path.of("/tmp/x"), new File("/tmp/x"), URI.create("http://h/x"),
                urlOf("http://h/x"), new byte[]{1, 2, 3}, new char[]{'a', 'b'},
                UUID.fromString("00000000-0000-0000-0000-000000000001"), Currency.getInstance("EUR"),
                StandardCharsets.UTF_8, String.class, java.util.regex.Pattern.compile("a+"), new AtomicInteger(3),
                new AtomicLong(4), new AtomicBoolean(true), new StringBuilder("sb"), inet("127.0.0.1"),
                new java.sql.Timestamp(1_700_000_000_123L), new java.sql.Date(1_700_000_000_123L),
                java.sql.Time.valueOf("12:30:00"), calendar, Month.OCTOBER, DayOfWeek.MONDAY, Optional.of(3),
                OptionalInt.of(3), new BigDecimal("1.50"), BigDecimal.ZERO, new Date(0L), Status.CLOSED);
        for (Object value : values) {
            assertParity(new Box(value));
        }
        assertParity(new Box(Map.of(Locale.forLanguageTag("en-IE"), 1)));
        assertParity(new Box(Map.of(new Date(0L), 1)));
        assertParity(new Box(Map.of(calendar, 1)));
        assertParity(new Box(Map.of(Month.OCTOBER, 1)));
        assertParity(new Box(new java.util.EnumMap<>(Map.of(Month.OCTOBER, 1))));
    }

    @Test
    @DisplayName("containers, arrays and nested records")
    void containersAndRecords() {
        assertParity(new Containers(List.of("a", "b"), new TreeSet<>(Set.of(3, 1, 2)), new LinkedHashMap<>(Map.of("k", 1)),
                new String[]{"x", "y"}, new int[]{1, 2}, new long[]{3L}, List.of(List.of("n")),
                Map.of("k", List.of(1, 2)), Optional.of(List.of("o")), List.of(Optional.of("p"), Optional.empty()),
                new byte[]{9}, new char[]{'q'}));
        Inner inner = new Inner("n", Status.ACTIVE);
        assertParity(new Records(inner, List.of(inner, inner), new Inner[]{inner}, Optional.of(inner), null));
        assertParity(new Deep(new Records(inner, new ArrayList<>(), new Inner[0], Optional.empty(), null),
                Map.of("a", "b", "c", List.of(1, 2))));
        assertParity(new Box(List.of(Map.of("k", List.of("v")), "s", 1)));
    }

    // ---- the intended divergences --------------------------------------------------------------

    public record Shaped(@JsonFormat(shape = JsonFormat.Shape.STRING) Date d) {
    }

    public record Formatted(@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd", timezone = "UTC") Date d) {
    }

    public record Accessor(String a, String b) {
        @Override
        public String a() {
            return b;
        }
    }

    @Test
    @DisplayName("the intended divergences, each with its reason")
    void intendedDivergences() {
        // 1. An explicit accessor whose value differs from its field: the reference reads the accessor, the
        //    walker reads the field, so no accessor can swap a sensitive value into a non-sensitive slot.
        assertThat(SourceTree.referenceTree(new Accessor("one", "two")).get("a").asString()).isEqualTo("two");
        assertThat(SourceTree.of(new Accessor("one", "two")).get("a").asString()).isEqualTo("one");

        // 2. The @JsonFormat cases: the walker ignores the annotation.
        Date date = new Date(1_700_000_000_123L);
        assertThat(SourceTree.referenceTree(new Shaped(date)).get("d").asString())
                .isEqualTo("2023-11-14T22:13:20.123+00:00");
        assertThat(SourceTree.of(new Shaped(date)).get("d").asString()).isEqualTo("1700000000123");
        assertThat(SourceTree.referenceTree(new Formatted(date)).get("d").asString()).isEqualTo("2023-11-14");
        assertThat(SourceTree.of(new Formatted(date)).get("d").asString()).isEqualTo("1700000000123");

        // 3. An enum Jackson would not write by name() is written by name(): see SourceModelsTest and
        //    SourceRecordWalkerTest.enumsAreWrittenByName for the @JsonValue, @JsonProperty and key cases.

        // 4. The reference and holder types are refused by the walker: see SourceRecordReprosTest.
    }
}
