package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
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
 * actual object graph too, through {@link #refuseIfNotAllowed(Class)}.
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

    /** Refuses a class that is neither a record, an enum, a JDK type nor a Jackson tree node. */
    static void refuseIfNotAllowed(Class<?> type) {
        if (!allowedLeaf(type) && !type.isRecord()) {
            throw refusal(type);
        }
    }

    private static void walk(Type type, Set<Class<?>> seen) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                walk(c.getComponentType(), seen);
            } else if (c.isRecord()) {
                if (seen.add(c)) {
                    for (RecordComponent component : c.getRecordComponents()) {
                        walk(component.getGenericType(), seen);
                    }
                }
            } else if (!allowedLeaf(c) && !c.isInterface() && c != Object.class) {
                throw refusal(c);
            }
        } else if (type instanceof ParameterizedType p) {
            walk(p.getRawType(), seen);
            for (Type argument : p.getActualTypeArguments()) {
                walk(argument, seen);
            }
        } else if (type instanceof GenericArrayType g) {
            walk(g.getGenericComponentType(), seen);
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

    private static PrivacyRefusedException refusal(Class<?> type) {
        return new PrivacyRefusedException(CODE, "$",
                type.getSimpleName() + " is not a record; source models must be records");
    }
}
