package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.bean.BeanSerializerBase;
import tools.jackson.databind.ser.impl.UnknownSerializer;

import java.lang.reflect.GenericArrayType;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Set;

/**
 * The shape a source model may have: a record, whose components are records, enums, JDK value
 * types, Jackson tree nodes, or collections, maps, optionals and arrays of those.
 *
 * <p>Anything else is a user class that Jackson would read through its getters and fields by
 * conventions that differ between Jackson majors, so the property names a rule matches would
 * depend on the library version. A record has exactly one reading. Such a class is refused with
 * {@link #CODE}, naming the class's simple name only, never a value.
 *
 * <p>{@link #require(Class)} checks the declared types at startup. A component declared as
 * {@code Object} or an interface can hold a bean at runtime, so {@link SourceTree#of} checks the
 * actual object graph too, through serializer modifiers that call back into this class.
 */
public final class SourceModels {

    public static final String CODE = "SOURCE_MODEL_NOT_A_RECORD";

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
        walk(type, new HashSet<>());
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
        if (type.isRecord() || JsonNode.class.isAssignableFrom(type) || type.getName().startsWith("tools.jackson.")) {
            return;
        }
        boolean gettersRead = serializer instanceof BeanSerializerBase || serializer instanceof UnknownSerializer;
        if (gettersRead || (!platform(type) && !isEnum(type))) {
            throw refusal(type);
        }
    }

    /** A user class that is not a record or an enum, however its serializer is supplied. */
    static void refuseUserClass(Class<?> type) {
        if (!type.isArray() && !type.isRecord() && !platform(type) && !isEnum(type) && !JsonNode.class.isAssignableFrom(type)
                && !type.getName().startsWith("tools.jackson.")) {
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
     * A map key is written as text; only types with a defined text form are allowed. Any other key
     * would be written by {@code toString()}, which for a bean or record is not a defined form.
     */
    static void refuseKey(Class<?> type) {
        if (type == Object.class) {
            return;
        }
        boolean allowed = type == String.class || type.isEnum() || isEnum(type) || Number.class.isAssignableFrom(type)
                || type.isPrimitive() || type == Boolean.class || type == Character.class
                || type == UUID.class || type == Locale.class || Date.class.isAssignableFrom(type)
                || Calendar.class.isAssignableFrom(type) || type == Class.class || type == URI.class
                || type == URL.class || type == Currency.class || TimeZone.class.isAssignableFrom(type)
                || TemporalAccessor.class.isAssignableFrom(type) || TemporalAmount.class.isAssignableFrom(type)
                || ZoneId.class.isAssignableFrom(type) || type == byte[].class;
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

    private static boolean platform(Class<?> c) {
        ClassLoader loader = c.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    private static void walk(Type type, Set<Object> seen) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                walk(c.getComponentType(), seen);
            } else if (c.isRecord()) {
                if (seen.add(c)) {
                    for (RecordComponent component : c.getRecordComponents()) {
                        walk(component.getGenericType(), seen);
                    }
                }
            } else if (c.isEnum() && objectShaped(c)) {
                throw refusal(c);
            } else if (Throwable.class.isAssignableFrom(c)
                    || (!allowedLeaf(c) && !c.isInterface() && c != Object.class)) {
                throw refusal(c);
            }
        } else if (type instanceof ParameterizedType p) {
            walk(p.getRawType(), seen);
            for (Type argument : p.getActualTypeArguments()) {
                walk(argument, seen);
            }
        } else if (type instanceof GenericArrayType g) {
            walk(g.getGenericComponentType(), seen);
        } else if (type instanceof TypeVariable<?> v) {
            for (Type bound : seen.add(v) ? v.getBounds() : new Type[0]) {
                walk(bound, seen);
            }
        } else if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) {
                walk(bound, seen);
            }
        }
    }

    private static boolean allowedLeaf(Class<?> c) {
        if (c.isPrimitive() || c.isEnum() || (c.getSuperclass() != null && c.getSuperclass().isEnum())
                || JsonNode.class.isAssignableFrom(c)) {
            return true;
        }
        // A JDK type is one the platform defines, whatever its package (a TimeZone is a sun.* class).
        ClassLoader loader = c.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader()
                || c.getName().startsWith("tools.jackson.");
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

    private static PrivacyRefusedException refusal(Class<?> type) {
        return new PrivacyRefusedException(CODE, "$",
                simpleName(type) + " is not a record; source models must be records");
    }
}
