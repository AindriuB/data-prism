package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.jdk.JavaUtilCalendarSerializer;
import tools.jackson.databind.ser.jdk.JavaUtilDateSerializer;
import tools.jackson.databind.ser.std.ToStringSerializer;
import tools.jackson.databind.util.StdDateFormat;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.time.Month;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Reads source objects into trees, and builds the nodes that replace them.
 *
 * <p>The one mapper on the trusted side, and it never serialises anything. Three
 * places need to turn a source record into a tree — the scrubbing engine, the
 * prohibited-value extractor and the correlation service — and each having its
 * own mapper meant three objects that an architecture rule had to name
 * individually, which is a rule that gets weaker every time someone adds a
 * fourth.
 *
 * <p>The rule that matters is that exactly one mapper <em>writes</em> what a model
 * sees, so the scrubbing module cannot be bypassed. Keeping the reading side to a
 * single object here lets that rule stay short: this class reads and
 * {@code DataPrismObjectMapper} writes, a few classes build a YAML mapper to read
 * an operator-written configuration file at startup, and anything else
 * constructing or obtaining a mapper is a finding.
 *
 * <p>Converting a source object is serialisation, so this mapper is configured to give the
 * values Jackson 2 gave, with one deliberate exception (D-173-1): Jackson 2's plain mapper
 * refused java.time types, {@code Optional} and {@code OptionalInt}, and they are now accepted
 * and passed to the engine, java.time as ISO-8601 text and an {@code Optional} unwrapped (empty
 * is null). That is a change, not a pin back to Jackson 2.
 */
public final class SourceTree {

    private static final ObjectMapper READER = JsonMapper.builder()
            // Jackson 3 changed many write-side defaults, and valueToTree is serialisation, so they
            // all apply here. The engine hashes and tokenises the converted scalars, so a changed
            // default silently changes what is emitted and what a subject is derived from. Start from
            // the Jackson 2 settings: alphabetical sorting off, empty beans refused, enums by name(),
            // BigDecimal zeros stripped, a field such as xRef keeping its name, UTC as +00:00.
            .configureForJackson2()
            // The deliberate departure (D-173-1): java.time types and Duration are ISO-8601 text.
            // Jackson 2's plain mapper refused them; they are accepted now and passed to the engine.
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
            // Jackson 3 counts months from zero unless asked; Jackson 2 wrote a Month by name (see legacyTypes).
            .enable(DateTimeFeature.ONE_BASED_MONTHS)
            // A Date written as text (an @JsonFormat STRING) carries +00:00 for UTC, as Jackson 2 wrote it.
            .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true).withZeroOffsetAsZ(false))
            .addModule(legacyTypes())
            .build();

    /**
     * What Jackson 2 wrote for the legacy types, which the ISO setting above would otherwise change:
     * a Date, a Timestamp and a Calendar are epoch millis (an {@code @JsonFormat} on the property is
     * still honoured), a java.sql.Time is its time text, and a
     * Locale is {@code toString()} ("en_IE") rather than a language tag.
     */
    private static SimpleModule legacyTypes() {
        return new SimpleModule()
                .setSerializerModifier(new RecordsOnly())
                .addSerializer(Date.class, new JavaUtilDateSerializer(Boolean.TRUE, null))
                .addSerializer(Calendar.class, new JavaUtilCalendarSerializer(Boolean.TRUE, null))
                .addSerializer(java.sql.Time.class, ToStringSerializer.instance)
                .addSerializer(Month.class, ToStringSerializer.instance)
                .addSerializer(Locale.class, ToStringSerializer.instance);
    }

    /**
     * Refuses, while a serializer is being built, any user class that is not a record. Only a record
     * or an enum, a JDK type or a Jackson tree node reaches the engine, so the declared check in
     * {@link SourceModels#require} cannot be bypassed by an {@code Object}-typed component.
     */
    private static final class RecordsOnly extends ValueSerializerModifier {
        @Override
        public ValueSerializer<?> modifySerializer(SerializationConfig config, BeanDescription.Supplier beanDesc,
                                                   ValueSerializer<?> serializer) {
            SourceModels.refuseIfNotAllowed(beanDesc.getBeanClass());
            return serializer;
        }
    }

    private SourceTree() {
    }

    /** A source object as a tree. Never used to produce output. */
    public static JsonNode of(Object source) {
        SourceModels.refuseUnlessRecord(source.getClass());
        try {
            return READER.valueToTree(source);
        } catch (JacksonException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof PrivacyRefusedException refused) {
                    throw refused;
                }
            }
            throw e;
        }
    }

    public static ObjectNode newObject() {
        return READER.createObjectNode();
    }

    public static ArrayNode newArray() {
        return READER.createArrayNode();
    }

    public static StringNode text(String value) {
        return READER.getNodeFactory().stringNode(value);
    }
}
