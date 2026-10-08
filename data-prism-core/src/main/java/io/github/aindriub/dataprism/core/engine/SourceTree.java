package io.github.aindriub.dataprism.core.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategy;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.AnnotatedMethod;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.jdk.JavaUtilCalendarSerializer;
import tools.jackson.databind.ser.jdk.JavaUtilDateSerializer;
import tools.jackson.databind.ser.std.ToStringSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

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
            .propertyNamingStrategy(new Jackson2GetterNames())
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
                .addSerializer(Date.class, new JavaUtilDateSerializer(Boolean.TRUE, null))
                .addSerializer(Calendar.class, new JavaUtilCalendarSerializer(Boolean.TRUE, null))
                .addSerializer(java.sql.Time.class, ToStringSerializer.instance)
                .addSerializer(Locale.class, ToStringSerializer.instance);
    }

    /**
     * Jackson 2 named a bean property from its getter by lower-casing the whole leading run of capitals
     * ({@code getXRef} is "xref", {@code getURL} is "url"); Jackson 3 keeps the JavaBeans form ("XRef").
     * The name is what a rule's field path matches, so it has to be the one the rules were written
     * against. A record accessor keeps its component name, as it always did.
     */
    private static final class Jackson2GetterNames extends PropertyNamingStrategy {
        @Override
        public String nameForGetterMethod(MapperConfig<?> config, AnnotatedMethod method, String defaultName) {
            if (method.getDeclaringClass().isRecord()) {
                return defaultName;
            }
            String name = method.getName();
            String base = name.startsWith("get") ? name.substring(3) : name.startsWith("is") ? name.substring(2) : "";
            if (base.isEmpty()) {
                return defaultName;
            }
            char[] chars = base.toCharArray();
            for (int i = 0; i < chars.length && Character.isUpperCase(chars[i]); i++) {
                chars[i] = Character.toLowerCase(chars[i]);
            }
            return new String(chars);
        }
    }

    private SourceTree() {
    }

    /** A source object as a tree. Never used to produce output. */
    public static JsonNode of(Object source) {
        return READER.valueToTree(source);
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
