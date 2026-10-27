package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.introspect.AnnotatedField;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.AnnotatedMethod;
import tools.jackson.databind.ser.AnyGetterWriter;
import tools.jackson.databind.ser.PropertyWriter;
import tools.jackson.databind.ser.bean.BeanSerializerBase;
import tools.jackson.databind.ser.impl.UnknownSerializer;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Proxy;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.net.URL;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Spliterator;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.stream.BaseStream;

/**
 * The shape a source model may have: a record, whose components are records, enums, JDK value
 * types, Jackson tree nodes, or collections, maps, optionals and arrays of those.
 *
 * <p>Anything else is a user class that Jackson would read through its getters and fields by
 * conventions that differ between Jackson majors, so the property names a rule matches would
 * depend on the library version. A record has exactly one reading. Such a class is refused with
 * {@link #CODE}, naming the class's simple name only, never a value.
 *
 * <p>A model author's explicit choices stay allowed: a {@code @JsonSerialize(using/keyUsing)} on a record
 * component or record class, {@code @JsonAnyGetter} and {@code @JsonValue}. The engine still classifies
 * what they produce, and any bean looked up through them is refused. A property the
 * serializer emits that is not backed by a record component (an annotated method on the record or on an
 * interface it implements, however inherited) is refused, since the property name it implies depends on
 * the Jackson major.
 *
 * <p>{@link #require(Class)} checks the declared types at startup. A component declared as
 * {@code Object} or an interface can hold a bean at runtime, so {@link SourceTree#of} checks the
 * actual object graph too, through serializer modifiers that call back into this class.
 */
public final class SourceModels {

    public static final String CODE = "SOURCE_MODEL_NOT_A_RECORD";

    /** A record declaring type parameters, or a component whose declared type is a parameterized user type. */
    public static final String GENERIC_CODE = "SOURCE_MODEL_GENERIC";

    /** A JDK map whose value type contains a record at any depth. */
    public static final String MAP_OF_RECORD_CODE = "SOURCE_MODEL_MAP_OF_RECORD";

    /** A record at array level two or deeper: inside a collection or array that is itself inside one. */
    public static final String NESTED_COLLECTION_OF_RECORD_CODE = "SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD";

    /** A non-null record value at a position whose declared type is not exactly that record class. */
    public static final String RECORD_NOT_DECLARED_CODE = "SOURCE_MODEL_RECORD_NOT_DECLARED";

    /** A record class whose fields the engine may not read (a named module that does not open its package). */
    public static final String NOT_READABLE_CODE = "SOURCE_MODEL_NOT_READABLE";

    /** A {@code JsonNode} that is not made of Jackson's own node classes, exactly. */
    public static final String FOREIGN_NODE_CODE = "SOURCE_MODEL_FOREIGN_NODE";

    /** A built record node whose property names are not exactly the record's component names. */
    public static final String TREE_NOT_COMPONENT_EXACT_CODE = "SOURCE_TREE_NOT_COMPONENT_EXACT";

    /** A read of the source value threw. */
    public static final String VALUE_READ_FAILED_CODE = "SOURCE_VALUE_READ_FAILED";

    /** A source value nested beyond the walker's depth bound. */
    public static final String TOO_DEEP_CODE = "TOO_DEEP";

    private SourceModels() {
    }

    /**
     * Refuses {@code type} unless it is a record whose declared component types are all allowed (a
     * response type that is not a record, a JDK type such as {@code String} included, could never be
     * exposed, so it fails at startup rather than on the first request).
     */
    public static void require(Class<?> type) {
        if (!type.isRecord()) {
            throw refusal(type);
        }
        walk(type, new HashSet<>(), null);
    }

    /**
     * The top-level object must be a record, or a Jackson tree node (a configured JSON source reads its
     * payload as one); its components are checked as the tree is built.
     */
    static void refuseUnlessRecord(Class<?> type) {
        if (!type.isRecord() && !JsonNode.class.isAssignableFrom(type)) {
            throw refusal(type);
        }
    }

    /**
     * Called as a serializer is built for {@code type}. A record is read by its components, so it
     * passes. Anything else is refused if it would be read by getters (a bean serializer, which
     * includes JDK classes such as Throwable and Color, and an enum written as an object) or is a user
     * class at all, whatever serializer annotations it carries. JDK value types with a dedicated
     * serializer, enums, and Jackson tree nodes pass.
     */
    static void refuseSerializer(Class<?> type, ValueSerializer<?> serializer) {
        // A record is judged by the walk (generic, readable) and by refuseForeignProperties, called directly.
        if (type.isRecord() || JsonNode.class.isAssignableFrom(type)) {
            return;
        }
        boolean gettersRead = serializer instanceof BeanSerializerBase || serializer instanceof UnknownSerializer;
        if (gettersRead || (!platform(type) && !isEnum(type))) {
            throw refusal(type);
        }
    }

    /**
     * A record is read by its components only. Whatever route adds another property (an annotated
     * method on the record, on an interface or a super-interface it implements, or a mix-in), the
     * serializer then emits a property whose backing member is not a component accessor, and its name
     * would depend on the Jackson major (getURL is "URL" or "url"). Every property the bean serializer
     * emits is therefore compared with the record's components; a component renamed with
     * {@code @JsonProperty}, or left out with {@code @JsonIgnore}, is still its component. {@code
     * @JsonAnyGetter} output (a real any-getter writer; {@code enabled=false} makes it an ordinary
     * property) is not a bean property and {@code @JsonValue} replaces the bean shape, so
     * both stay allowed.
     */
    static void refuseForeignProperties(Class<?> record, ValueSerializer<?> serializer) {
        if (!(serializer instanceof BeanSerializerBase bean)) {
            return;
        }
        Set<String> components = new HashSet<>();
        for (RecordComponent component : record.getRecordComponents()) {
            components.add(component.getName());
        }
        for (Iterator<PropertyWriter> properties = bean.properties(); properties.hasNext(); ) {
            PropertyWriter writer = properties.next();
            if (writer instanceof AnyGetterWriter) {
                continue;
            }
            AnnotatedMember member = writer.getMember();
            // A real field or method of the record: a virtual property (@JsonAppend) is neither, even
            // when it is named like a component.
            boolean real = member instanceof AnnotatedField
                    || (member instanceof AnnotatedMethod method && method.getParameterCount() == 0);
            boolean backed = real && components.contains(member.getName());
            if (!backed) {
                throw new PrivacyRefusedException(CODE, "$", simpleName(record)
                        + " has a property that is not a record component");
            }
        }
    }

    /** A user class that is not a record or an enum, however its serializer is supplied. */
    static void refuseUserClass(Class<?> type) {
        if (!type.isArray() && !type.isRecord() && !platform(type) && !isEnum(type) && !JsonNode.class.isAssignableFrom(type)
                && !SourceTree.fromJacksonDatabind(type)) {
            throw refusal(type);
        }
    }

    /** A collection or map: a user subclass of one can add getters or its own conventions. */
    static void refuseContainer(Class<?> type) {
        if (!type.isRecord() && !platform(type)) {
            throw refusal(type);
        }
    }

    /**
     * A declared map key type. An interface or abstract type is deferred to the per-key check at
     * write time; anything else is checked now by {@link #refuseKey(Class)}.
     */
    static void refuseDeclaredKey(Class<?> type) {
        // An interface or abstract class says nothing about the key actually used (CharSequence,
        // Comparable, Serializable): each key's own class is checked as it is written.
        if (type.isInterface() || (java.lang.reflect.Modifier.isAbstract(type.getModifiers()) && !isEnum(type))) {
            return;
        }
        refuseKey(type);
    }

    /**
     * A map key is written as text; only types with a defined text form are allowed. Any other key
     * would be written by {@code toString()}, which for a bean or record is not a defined form.
     */
    static void refuseKey(Class<?> type) {
        if (type == Object.class) {
            return;
        }
        // The type tests below match subclasses, so they apply to JDK classes only: a user class that
        // extends Number or Date can define its own toString(), and that would become the field name.
        boolean jdk = platform(type);
        boolean allowed = type == String.class || isEnum(type) || type == Enum.class || type.isPrimitive()
                || (jdk && (Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class
                || type == UUID.class || type == Locale.class || Date.class.isAssignableFrom(type)
                || Calendar.class.isAssignableFrom(type) || type == Class.class || type == URI.class
                || type == URL.class || type == Currency.class || TimeZone.class.isAssignableFrom(type)
                || TemporalAccessor.class.isAssignableFrom(type) || TemporalAmount.class.isAssignableFrom(type)
                || ZoneId.class.isAssignableFrom(type) || type == byte[].class));
        if (!allowed) {
            throw refusal(type);
        }
    }

    /** An enum written as an object is read through its getters, like a bean. */
    private static boolean objectShaped(Class<?> enumType) {
        JsonFormat format = enumType.getAnnotation(JsonFormat.class);
        return format != null && format.shape() == JsonFormat.Shape.OBJECT;
    }

    private static boolean isEnum(Class<?> c) {
        return c.isEnum() || (c.getSuperclass() != null && c.getSuperclass().isEnum());
    }

    /**
     * The JDK-class test: loaded by the bootstrap or platform loader, not a dynamic proxy class (every
     * method of a proxy runs the caller's handler, and a proxy for JDK interfaces can be defined by the
     * bootstrap loader) and not hidden (a lambda, which wraps a function this class cannot read).
     */
    static boolean platform(Class<?> c) {
        ClassLoader loader = c.getClassLoader();
        return (loader == null || loader == ClassLoader.getPlatformClassLoader())
                && !Proxy.isProxyClass(c) && !c.isHidden();
    }

    /**
     * A JDK class that holds another object, or can be consumed once: refused at startup by declared
     * type and at scrub time by runtime class. {@code Optional} is unwrapped by the walker and is not one.
     */
    static boolean referenceOrSingleUse(Class<?> c) {
        return AtomicReference.class.isAssignableFrom(c)
                || AtomicReferenceArray.class.isAssignableFrom(c)
                || AtomicStampedReference.class.isAssignableFrom(c)
                || AtomicMarkableReference.class.isAssignableFrom(c)
                || java.lang.ref.Reference.class.isAssignableFrom(c)
                || ThreadLocal.class.isAssignableFrom(c)
                || Future.class.isAssignableFrom(c)
                || BaseStream.class.isAssignableFrom(c)
                || Iterator.class.isAssignableFrom(c)
                || Spliterator.class.isAssignableFrom(c)
                || Enumeration.class.isAssignableFrom(c);
    }

    static boolean isOptional(Class<?> c) {
        return c == java.util.Optional.class || c == java.util.OptionalInt.class
                || c == java.util.OptionalLong.class || c == java.util.OptionalDouble.class;
    }

    /** A refusal with {@link #CODE} that names the class's simple name only, at {@code path}. */
    static PrivacyRefusedException refusalAt(Class<?> type, String path) {
        return new PrivacyRefusedException(CODE, path, simpleName(type) + " is not a record; source models must be records");
    }

    /**
     * The one rule for "may this class be a leaf", shared by startup (on declared classes) and the
     * walker (on runtime classes): a JDK class that is not a container, a holder, a single-use type or a
     * record, that the reader would not write through getters, and that the inspecting reader does not
     * write with a container, reference or bean serializer. {@code Path} is a leaf; a {@code ServiceLoader}
     * is not.
     */
    static boolean leafClass(Class<?> c) {
        if (c.isPrimitive()) {
            return true;
        }
        if (!platform(c) || c.isRecord() || (c.isArray() && c != byte[].class && c != char[].class)
                || Throwable.class.isAssignableFrom(c) || referenceOrSingleUse(c)
                || java.util.Collection.class.isAssignableFrom(c) || java.util.Map.class.isAssignableFrom(c)) {
            return false;
        }
        return !SourceTree.writtenAsStructure(c);
    }

    /**
     * @param where the {@code Model.field} being walked, or null at the top level; it prefixes the
     *              message of a refusal so the author can find the component
     */
    private static void walk(Type type, Set<Object> seen, String where) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                walk(c.getComponentType(), seen, where);
            } else if (c.isRecord()) {
                if (c.getTypeParameters().length > 0) {
                    throw genericRefusal(where, c);
                }
                if (seen.add(c)) {
                    if (!SourceRecordWalker.readable(c)) {
                        throw new PrivacyRefusedException(NOT_READABLE_CODE, "$", at(where) + simpleName(c)
                                + " cannot be read: its package is not opened to the engine");
                    }
                    // Components first, so a refusal names the component; then ask the reader to
                    // build it: the serializer modifier compares its properties.
                    for (RecordComponent component : c.getRecordComponents()) {
                        String componentWhere = simpleName(c) + "." + component.getName();
                        walk(component.getGenericType(), seen, componentWhere);
                        // After the walk, so a generic or a map of records keeps its own code.
                        if (recordBeyondFirstArray(component.getGenericType(), 0, new HashSet<>())) {
                            throw nestedCollectionRefusal(componentWhere);
                        }
                    }
                    SourceTree.refuseIfReadByGetters(c);
                }
            } else if (c.isEnum() && objectShaped(c)) {
                throw refusal(c);
            } else if (referenceOrSingleUse(c)) {
                // AtomicReference, Future, ThreadLocal, a Stream or an Iterator: by declared type, interfaces too.
                throw refusal(c);
            } else if (Throwable.class.isAssignableFrom(c)
                    || (!allowedLeaf(c) && !c.isInterface() && c != Object.class)) {
                throw refusal(c);
            } else if (platform(c) && !c.isInterface() && !c.isEnum() && !c.isPrimitive() && c != Object.class
                    && !JsonNode.class.isAssignableFrom(c)) {
                // A JDK class read by getters (java.awt.Point) is found out by asking the reader.
                SourceTree.refuseIfReadByGetters(c);
                // The leaf rule the walker applies to a runtime value of this class, so a model that starts
                // never fails every request on a leaf type. Containers and optionals are walked, not leaves.
                if (!java.util.Collection.class.isAssignableFrom(c) && !java.util.Map.class.isAssignableFrom(c)
                        && !isOptional(c) && !leafClass(c)) {
                    throw refusal(c);
                }
            }
        } else if (type instanceof ParameterizedType p) {
            if (p.getRawType() instanceof Class<?> raw) {
                if (!platform(raw)) {
                    // Envelope<Leaf>, Box<Leaf>: what it holds is its own, not its argument's.
                    throw genericRefusal(where, raw);
                }
                Type[] arguments = p.getActualTypeArguments();
                if (java.util.Map.class.isAssignableFrom(raw) && arguments.length == 2
                        && containsRecord(arguments[1], new HashSet<>())) {
                    throw mapOfRecordRefusal(where, raw);
                }
            }
            walk(p.getRawType(), seen, where);
            for (Type argument : p.getActualTypeArguments()) {
                walk(argument, seen, where);
            }
        } else if (type instanceof GenericArrayType g) {
            walk(g.getGenericComponentType(), seen, where);
        } else if (type instanceof TypeVariable<?> v) {
            for (Type bound : seen.add(v) ? v.getBounds() : new Type[0]) {
                walk(bound, seen, where);
            }
        } else if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) {
                walk(bound, seen, where);
            }
        }
    }

    /** Whether a record appears anywhere in {@code type}: its arguments, array components and bounds. */
    private static boolean containsRecord(Type type, Set<Object> seen) {
        if (type instanceof Class<?> c) {
            return c.isArray() ? containsRecord(c.getComponentType(), seen) : c.isRecord();
        }
        if (type instanceof ParameterizedType p) {
            if (containsRecord(p.getRawType(), seen)) {
                return true;
            }
            for (Type argument : p.getActualTypeArguments()) {
                if (containsRecord(argument, seen)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType g) {
            return containsRecord(g.getGenericComponentType(), seen);
        }
        if (type instanceof TypeVariable<?> v) {
            if (seen.add(v)) {
                for (Type bound : v.getBounds()) {
                    if (containsRecord(bound, seen)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) {
                if (containsRecord(bound, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a record sits at array level two or deeper in {@code type}. An array class, a generic
     * array and a parameterized JDK {@code Iterable} each add a level; an {@code Optional} adds none;
     * type variables and wildcards are read through their upper bounds. Counting restarts inside each
     * record, which {@link #walk} handles on its own.
     */
    private static boolean recordBeyondFirstArray(Type type, int level, Set<Object> seen) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                return recordBeyondFirstArray(c.getComponentType(), level + 1, seen);
            }
            return c.isRecord() && level >= 2;
        }
        if (type instanceof ParameterizedType p) {
            int inner = level;
            if (p.getRawType() instanceof Class<?> raw && Iterable.class.isAssignableFrom(raw)) {
                inner = level + 1;
            }
            for (Type argument : p.getActualTypeArguments()) {
                if (recordBeyondFirstArray(argument, inner, seen)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType g) {
            return recordBeyondFirstArray(g.getGenericComponentType(), level + 1, seen);
        }
        if (type instanceof TypeVariable<?> v) {
            if (seen.add(v)) {
                for (Type bound : v.getBounds()) {
                    if (recordBeyondFirstArray(bound, level, seen)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) {
                if (recordBeyondFirstArray(bound, level, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean allowedLeaf(Class<?> c) {
        if (c.isPrimitive() || c.isEnum() || (c.getSuperclass() != null && c.getSuperclass().isEnum())
                || JsonNode.class.isAssignableFrom(c)) {
            return true;
        }
        // A JDK type is one the platform defines, whatever its package (a TimeZone is a sun.* class). A name
        // is not ownership: a user class in a tools.jackson package is refused, and a Jackson class that
        // is not a JsonNode is not delegated.
        return platform(c);
    }

    /** The simple name; an anonymous or hidden class has none, so its name without the package stands in. */
    private static String simpleName(Class<?> type) {
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String at(String where) {
        return where == null ? "" : where + ": ";
    }

    private static PrivacyRefusedException genericRefusal(String where, Class<?> type) {
        return new PrivacyRefusedException(GENERIC_CODE, "$",
                at(where) + simpleName(type) + " is generic; source models must not be");
    }

    private static PrivacyRefusedException mapOfRecordRefusal(String where, Class<?> map) {
        return new PrivacyRefusedException(MAP_OF_RECORD_CODE, "$",
                at(where) + simpleName(map) + " values must not be records; source models must not hold them");
    }

    private static PrivacyRefusedException nestedCollectionRefusal(String where) {
        return new PrivacyRefusedException(NESTED_COLLECTION_OF_RECORD_CODE, "$",
                at(where) + "a record must not sit inside a collection inside a collection;"
                        + " source models must not hold one");
    }

    private static PrivacyRefusedException refusal(Class<?> type) {
        return new PrivacyRefusedException(CODE, "$",
                simpleName(type) + " is not a record; source models must be records");
    }
}
