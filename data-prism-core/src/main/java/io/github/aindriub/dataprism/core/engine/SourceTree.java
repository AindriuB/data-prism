package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.AnnotationIntrospectorPair;
import tools.jackson.databind.introspect.JacksonAnnotationIntrospector;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.BinaryNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.FloatNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.POJONode;
import tools.jackson.databind.node.ShortNode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.bean.BeanSerializerBase;
import tools.jackson.databind.ser.impl.UnknownSerializer;
import tools.jackson.databind.ser.jdk.JavaUtilCalendarSerializer;
import tools.jackson.databind.ser.jdk.JavaUtilDateSerializer;
import tools.jackson.databind.ser.jdk.MapSerializer;
import tools.jackson.databind.ser.std.ArraySerializerBase;
import tools.jackson.databind.ser.std.ReferenceTypeSerializer;
import tools.jackson.databind.ser.std.StdContainerSerializer;
import tools.jackson.databind.ser.std.ToStringSerializer;
import tools.jackson.databind.type.CollectionLikeType;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapLikeType;
import tools.jackson.databind.type.MapType;
import tools.jackson.databind.util.StdDateFormat;

import java.time.Month;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * Reads source objects into trees, and builds the nodes that replace them.
 *
 * <p>The engine builds the tree of a source record itself ({@link SourceRecordWalker}): each record component is
 * read from the record's private field and written under the component's own name, so no Jackson annotation,
 * accessor or serializer can move a value out from under its own classification. Jackson is handed only scalar
 * leaf values whose whole object graph is JDK classes, through {@link #leaf}.
 *
 * <p>Two private readers share one configuration, which starts from the Jackson 2 settings so leaf values are
 * unchanged, with one deliberate exception (D-173-1): java.time types are ISO-8601 text and an {@code Optional}
 * is unwrapped (empty is null).
 * <ul>
 * <li>The <em>leaf reader</em> is guarded: while a serializer is built for any class it refuses every record,
 * every class not loaded by the bootstrap or platform loader, every class-level serializer annotation and every
 * bean, reference-type or container serializer. It is the only reader whose {@code valueToTree} main code calls,
 * and only from {@link #leaf}.
 * <li>The <em>inspecting reader</em> never writes a source value. Startup uses it to look serializers up
 * ({@link #refuseIfReadByGetters}, {@link #writtenAsStructure}), and the parity oracle reaches its output through
 * the test seam {@link #referenceTree}, which no main class calls.
 * </ul>
 *
 * <p>The rule that matters is that exactly one mapper <em>writes</em> what a model sees, so the scrubbing module
 * cannot be bypassed: this class reads and {@code DataPrismObjectMapper} writes, a few classes build a YAML mapper
 * to read an operator-written configuration file at startup, and anything else constructing or obtaining a mapper
 * is a finding. {@code SourceTreeReadersArchitectureTest} pins which methods may write with which reader.
 *
 * <p>The top-level object must be a record or a Jackson tree node. {@link SourceModels#require} checks the
 * declared types at startup and the walker checks the actual object graph.
 */
public final class SourceTree {

    /**
     * The inspecting reader. Main code uses it only to look serializers up
     * ({@link #refuseIfReadByGetters}, the foreign-property lookup, {@link #writtenAsStructure}); it never
     * writes a value with it. Its modifier refuses what would be read by getters or by a user class's own
     * conventions while a serializer is built, which is how startup finds them. The parity oracle reaches
     * its output through {@link #referenceTree}, a test seam that no main class calls.
     */
    private static final ObjectMapper READER = shared()
            .annotationIntrospector(new AnnotationIntrospectorPair(new ClassLevelSerializers(),
                    new JacksonAnnotationIntrospector()))
            .addModule(legacyTypes(new RecordsOnly()))
            .build();

    /**
     * The leaf reader, guarded. The only reader whose {@code valueToTree} main code calls, and only from
     * {@link #leaf}, the walker's leaf method. While a serializer is built for any class the guard refuses
     * every record, every class not loaded by the bootstrap or platform loader, every class-level
     * serializer annotation and every bean, reference-type or container serializer, so whatever a
     * delegated value contains, Jackson can never write a user class or a record through it.
     */
    private static final JsonMapper LEAF = shared()
            .annotationIntrospector(new AnnotationIntrospectorPair(new LeafClassGuard(),
                    new JacksonAnnotationIntrospector()))
            .addModule(legacyTypes(new LeafGuard()))
            .build();

    /** The configuration both readers share. */
    private static JsonMapper.Builder shared() {
        return JsonMapper.builder()
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
                .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true).withZeroOffsetAsZ(false));
    }

    /**
     * What Jackson 2 wrote for the legacy types, which the ISO setting above would otherwise change:
     * a Date, a Timestamp and a Calendar are epoch millis (an {@code @JsonFormat} on the property is
     * still honoured), a java.sql.Time is its time text, and a
     * Locale is {@code toString()} ("en_IE") rather than a language tag.
     */
    private static SimpleModule legacyTypes(ValueSerializerModifier modifier) {
        return new SimpleModule()
                .setSerializerModifier(modifier)
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
            SourceModels.refuseDeclaredKey(type.getRawClass());
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

    /**
     * The leaf guard's class-level half: while the leaf reader looks at any class it refuses a record, a
     * class that is not a JDK class, and so any class-level serializer annotation on one.
     */
    private static final class LeafClassGuard extends NopAnnotationIntrospector {
        @Override
        public Object findSerializer(MapperConfig<?> config, Annotated annotated) {
            if (annotated instanceof AnnotatedClass c) {
                refuseLeafClass(c.getRawType());
            }
            return null;
        }
    }

    private static void refuseLeafClass(Class<?> type) {
        if (type.isRecord() || !SourceModels.platform(type)) {
            throw SourceModels.refusalAt(type, "$");
        }
    }

    /**
     * The leaf guard's serializer half: while a serializer is built for any class on the leaf reader, every
     * record, every class that is not a JDK class and every bean, reference-type, container or map
     * serializer is refused. The one container allowed is the single-entry map the walker builds to write a
     * map key ({@link #leaf}).
     */
    private static final class LeafGuard extends ValueSerializerModifier {
        private static final Class<?> KEY_CARRIER = Collections.singletonMap("k", "v").getClass();

        @Override
        public ValueSerializer<?> modifySerializer(SerializationConfig config, BeanDescription.Supplier beanDesc,
                                                   ValueSerializer<?> serializer) {
            Class<?> type = beanDesc.getBeanClass();
            if (type != KEY_CARRIER) {
                refuseLeafClass(type);
                refuseStructure(type, serializer);
            }
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyEnumSerializer(SerializationConfig config, JavaType type,
                                                       BeanDescription.Supplier beanDesc,
                                                       ValueSerializer<?> serializer) {
            refuseLeafClass(type.getRawClass());
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyArraySerializer(SerializationConfig config,
                                                        tools.jackson.databind.type.ArrayType type,
                                                        BeanDescription.Supplier beanDesc,
                                                        ValueSerializer<?> serializer) {
            Class<?> raw = type.getRawClass();
            if (raw != byte[].class && raw != char[].class) {
                throw SourceModels.refusalAt(raw, "$");
            }
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyCollectionSerializer(SerializationConfig config, CollectionType type,
                                                             BeanDescription.Supplier beanDesc,
                                                             ValueSerializer<?> serializer) {
            throw SourceModels.refusalAt(type.getRawClass(), "$");
        }

        @Override
        public ValueSerializer<?> modifyCollectionLikeSerializer(SerializationConfig config, CollectionLikeType type,
                                                                 BeanDescription.Supplier beanDesc,
                                                                 ValueSerializer<?> serializer) {
            throw SourceModels.refusalAt(type.getRawClass(), "$");
        }

        @Override
        public ValueSerializer<?> modifyMapSerializer(SerializationConfig config, MapType type,
                                                      BeanDescription.Supplier beanDesc,
                                                      ValueSerializer<?> serializer) {
            if (type.getRawClass() != KEY_CARRIER) {
                throw SourceModels.refusalAt(type.getRawClass(), "$");
            }
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyMapLikeSerializer(SerializationConfig config, MapLikeType type,
                                                          BeanDescription.Supplier beanDesc,
                                                          ValueSerializer<?> serializer) {
            throw SourceModels.refusalAt(type.getRawClass(), "$");
        }

        @Override
        public ValueSerializer<?> modifyKeySerializer(SerializationConfig config, JavaType type,
                                                      BeanDescription.Supplier beanDesc,
                                                      ValueSerializer<?> serializer) {
            Class<?> raw = type.getRawClass();
            if (raw != Object.class) {
                refuseLeafClass(raw);
            }
            return serializer;
        }
    }

    private static void refuseStructure(Class<?> type, ValueSerializer<?> serializer) {
        if (isStructure(serializer)) {
            throw SourceModels.refusalAt(type, "$");
        }
    }

    /** A serializer that writes a bean, a container, a map or the content of a reference, or no known way at all. */
    private static boolean isStructure(ValueSerializer<?> serializer) {
        return serializer instanceof BeanSerializerBase
                || serializer instanceof UnknownSerializer
                || serializer instanceof ReferenceTypeSerializer<?>
                || serializer instanceof StdContainerSerializer<?>
                || serializer instanceof MapSerializer
                || serializer instanceof ArraySerializerBase<?>;
    }

    /**
     * Whether the inspecting reader writes {@code type} with a bean, container, map, reference or unknown
     * serializer: the leaf rule's container test, shared by startup and the walker. If the lookup throws, the
     * answer is true (fail closed).
     */
    static boolean writtenAsStructure(Class<?> type) {
        try {
            return isStructure(READER._serializationContext().findValueSerializer(type));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Refuses a JDK class that this reader would write through getters, found by building its serializer. */
    static void refuseIfReadByGetters(Class<?> type) {
        try {
            READER.writerFor(type);
            if (type.isRecord()) {
                SourceModels.refuseForeignProperties(type, READER._serializationContext().findValueSerializer(type));
            }
        } catch (JacksonException e) {
            PrivacyRefusedException refused = SourceRecordWalker.refusalIn(e);
            if (refused != null) {
                throw refused;
            }
            // Any other failure is left for the runtime check, which sees the actual object.
        }
    }

    /** Whether the class was loaded from the same code source as {@code ValueSerializer}; a null on either side is no. */
    static boolean fromJacksonDatabind(Class<?> c) {
        try {
            var own = ValueSerializer.class.getProtectionDomain().getCodeSource();
            var other = c.getProtectionDomain().getCodeSource();
            return own != null && other != null && own.getLocation() != null
                    && own.getLocation().equals(other.getLocation());
        } catch (SecurityException e) {
            return false;
        }
    }

    private SourceTree() {
    }

    /**
     * A source object as a tree. Never used to produce output. The engine builds it itself
     * ({@link SourceRecordWalker}); Jackson writes only JDK scalar leaves.
     */
    public static JsonNode of(Object source) {
        return SourceRecordWalker.of(source);
    }

    /**
     * The walker's leaf method, and the only place main code writes a value with the leaf reader. The value
     * is a JDK scalar whose whole object graph is JDK classes, or (with {@code asKey}) a map key, written
     * as the single entry of a map and returned as the key's text. The result is a scalar node, a null node
     * or a {@code POJONode} holding exactly a {@code byte[]}; anything else is refused.
     */
    static JsonNode leaf(Object value, boolean asKey) {
        if (asKey) {
            JsonNode written = LEAF.valueToTree(Collections.singletonMap(value, ""));
            if (written.getClass() != ObjectNode.class || written.size() != 1) {
                throw SourceModels.refusalAt(value.getClass(), "$");
            }
            return LEAF.getNodeFactory().stringNode(written.propertyNames().iterator().next());
        }
        return checkedLeaf(LEAF.valueToTree(value), value.getClass());
    }

    /**
     * The leaf result check: a scalar node, a null node, or a {@code POJONode} whose held object's class is
     * exactly {@code byte[]} (checked before anything else is called on it). Anything else, an object, an
     * array or any other embedded object, is refused.
     */
    static JsonNode checkedLeaf(JsonNode written, Class<?> source) {
        Class<?> c = written.getClass();
        boolean scalar = c == StringNode.class || c == IntNode.class || c == LongNode.class
                || c == ShortNode.class || c == FloatNode.class || c == DoubleNode.class
                || c == DecimalNode.class || c == BigIntegerNode.class || c == BooleanNode.class
                || c == NullNode.class || c == BinaryNode.class
                || (c == POJONode.class && ((POJONode) written).getPojo().getClass() == byte[].class);
        if (!scalar) {
            throw SourceModels.refusalAt(source, "$");
        }
        return written;
    }

    /** The node factory the walker builds every node with: the leaf reader's own, exactly {@code JsonNodeFactory}. */
    static JsonNodeFactory nodes() {
        return LEAF.getNodeFactory();
    }

    /**
     * Test seam for the parity oracle: what the inspecting reader writes for {@code source}. No main class
     * calls it, and the architecture test fails if one does.
     */
    static JsonNode referenceTree(Object source) {
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
