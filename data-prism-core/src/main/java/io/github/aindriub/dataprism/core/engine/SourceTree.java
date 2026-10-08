package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.AnnotationIntrospectorPair;
import tools.jackson.databind.introspect.JacksonAnnotationIntrospector;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.type.CollectionLikeType;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapLikeType;
import tools.jackson.databind.type.MapType;
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
 * <p>A source model must be a record (D-173-2), read by its components only: an extra getter on a
 * record is not a property. Reading anything by getters or fields follows conventions that differ
 * between Jackson majors, so the property names a rule matches would depend on the library version.
 * The top-level object must be a record (or a Jackson tree node), and nested values may be records,
 * enums, JDK value types with a dedicated serializer (String, numbers, dates, Locale, UUID, URI...),
 * tree nodes, or collections, maps, optionals and arrays of those; map keys must have a defined text
 * form. Any other class, at any depth, is refused with {@link SourceModels#CODE}: a user class, a user
 * subclass of a collection or map, a class with its own class-level serializer, an enum written as an
 * object, and a JDK class that would be read by getters (a Throwable can carry personal data in its
 * message). {@link SourceModels#require} checks the declared types at startup and this class checks the
 * actual object graph.
 *
 * <p>Within that shape the mapper starts from the Jackson 2 settings so values are unchanged,
 * with one deliberate exception (D-173-1): Jackson 2's plain mapper refused java.time types,
 * {@code Optional} and {@code OptionalInt}. They are now accepted and passed to the engine,
 * java.time as ISO-8601 text and an {@code Optional} unwrapped (empty is null). That is a
 * change, not a pin back to Jackson 2.
 */
public final class SourceTree {

    private static final ObjectMapper READER = JsonMapper.builder()
            // Jackson 3 changed many write-side defaults, and valueToTree is serialisation, so they
            // all apply here. The engine hashes and tokenises the converted scalars, so a changed
            // default silently changes what is emitted and what a subject is derived from. Start from
            // the Jackson 2 settings: alphabetical sorting off, empty beans refused, enums by name(),
            // BigDecimal zeros stripped, a field such as xRef keeping its name, UTC as +00:00.
            .configureForJackson2()
            // A record is read by its components only: an extra getX()/isX() method, or an interface
            // default getter, is not a property.
            .enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY)
            // The deliberate departure (D-173-1): java.time types and Duration are ISO-8601 text.
            // Jackson 2's plain mapper refused them; they are accepted now and passed to the engine.
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
            // Jackson 3 counts months from zero unless asked; Jackson 2 wrote a Month by name (see legacyTypes).
            .enable(DateTimeFeature.ONE_BASED_MONTHS)
            // A Date written as text (an @JsonFormat STRING) carries +00:00 for UTC, as Jackson 2 wrote it.
            .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true).withZeroOffsetAsZ(false))
            .annotationIntrospector(new AnnotationIntrospectorPair(new ClassLevelSerializers(),
                    new JacksonAnnotationIntrospector()))
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
     * Refuses, while a serializer is being built, anything that would be read by getters or by a user
     * class's own conventions rather than by record components: see {@link SourceModels#refuseSerializer}
     * and {@link SourceModels#refuseKey}. The declared check in {@link SourceModels#require} cannot be
     * bypassed by an {@code Object}-typed component, a subclass of a collection, or a map key.
     */
    private static final class RecordsOnly extends ValueSerializerModifier {
        @Override
        public ValueSerializer<?> modifySerializer(SerializationConfig config, BeanDescription.Supplier beanDesc,
                                                   ValueSerializer<?> serializer) {
            SourceModels.refuseSerializer(beanDesc.getBeanClass(), serializer);
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyCollectionSerializer(SerializationConfig config, CollectionType type,
                                                             BeanDescription.Supplier beanDesc,
                                                             ValueSerializer<?> serializer) {
            SourceModels.refuseContainer(type.getRawClass());
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyCollectionLikeSerializer(SerializationConfig config, CollectionLikeType type,
                                                                 BeanDescription.Supplier beanDesc,
                                                                 ValueSerializer<?> serializer) {
            SourceModels.refuseContainer(type.getRawClass());
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyMapSerializer(SerializationConfig config, MapType type,
                                                      BeanDescription.Supplier beanDesc,
                                                      ValueSerializer<?> serializer) {
            SourceModels.refuseContainer(type.getRawClass());
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyMapLikeSerializer(SerializationConfig config, MapLikeType type,
                                                          BeanDescription.Supplier beanDesc,
                                                          ValueSerializer<?> serializer) {
            SourceModels.refuseContainer(type.getRawClass());
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyKeySerializer(SerializationConfig config, JavaType type,
                                                      BeanDescription.Supplier beanDesc,
                                                      ValueSerializer<?> serializer) {
            SourceModels.refuseKey(type.getRawClass());
            return serializer;
        }
    }

    /**
     * A class-level {@code @JsonSerialize(using=...)} supplies a serializer before any serializer
     * modifier runs, so a user class could name its own way out of {@link RecordsOnly}. Look at the
     * annotation instead and refuse the class.
     */
    private static final class ClassLevelSerializers extends NopAnnotationIntrospector {
        @Override
        public Object findSerializer(MapperConfig<?> config, Annotated annotated) {
            if (annotated instanceof AnnotatedClass c) {
                SourceModels.refuseUserClass(c.getRawType());
            }
            return null;
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
