package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
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
import tools.jackson.databind.node.ShortNode;
import tools.jackson.databind.node.StringNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * Builds the scrub tree of a source value itself, instead of asking Jackson to write it.
 *
 * <p>The scrubber applies a holding field's policy to whatever tree it is given, so a tree that Jackson
 * shaped (an {@code @JsonValue}, a custom serializer, an array shape, an object id, a renamed property)
 * could move a sensitive value out from under its own classification. This walker writes each record
 * component under the component's own name, reading its value from the record's private field, and
 * hands Jackson only JDK scalar leaves ({@link SourceTree#leaf}). It is deliberately ignorant of every
 * Jackson annotation.
 *
 * <p>It has two modes. The strict mode ({@link #of}) refuses anything it cannot place with a
 * {@link PrivacyRefusedException}. The lenient mode ({@link #lenient}) feeds
 * {@link SourceValues#prohibited}: it never throws (but for a {@code VirtualMachineError}), builds what
 * it can, and collects every scalar under a position the strict mode would refuse.
 *
 * <p>Trust model: source-adapter code is trusted deployer code, and a hostile adapter is out of scope.
 * The walker defends against mistakes in it. It never calls a method directly on an object whose class
 * code outside the JDK and Jackson supplies; the exceptions are named where they are made.
 */
final class SourceRecordWalker {

    private static final Logger LOG = LoggerFactory.getLogger(SourceRecordWalker.class);

    /** Nesting beyond this many levels is refused. A record, collection, array, map and node container each count one. */
    static final int MAX_DEPTH = 64;

    /** A cause chain is followed for at most this many steps. */
    private static final int MAX_CAUSES = 32;

    /** Gives the lookup that reads a record's private fields. A test seam for the unreadable-record cases. */
    @FunctionalInterface
    interface LookupProvider {
        MethodHandles.Lookup lookup(Class<?> type) throws IllegalAccessException;
    }

    static volatile LookupProvider lookups = type -> MethodHandles.privateLookupIn(type, MethodHandles.lookup());

    /** What the lenient walk found: the tree it could build, and every scalar it must treat as prohibited. */
    record Lenient(JsonNode tree, Set<String> prohibited) {
    }

    private record Handles(RecordComponent[] components, Type[] types, MethodHandle[] getters) {
        boolean readable() {
            return getters != null;
        }
    }

    /**
     * Field handles per record class. A {@code ClassValue} holds nothing beyond the class's own lifetime.
     * Reading by field (never through the accessors) is what stops an explicit accessor that returns
     * another component's value from swapping a sensitive value into a non-sensitive slot.
     */
    private static final ClassValue<Handles> HANDLES = new ClassValue<>() {
        @Override
        protected Handles computeValue(Class<?> type) {
            RecordComponent[] components = type.getRecordComponents();
            Type[] types = new Type[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getGenericType();
            }
            try {
                MethodHandles.Lookup lookup = lookups.lookup(type);
                MethodHandle[] getters = new MethodHandle[components.length];
                for (int i = 0; i < components.length; i++) {
                    Field field = type.getDeclaredField(components[i].getName());
                    getters[i] = lookup.unreflectGetter(field)
                            .asType(MethodType.methodType(Object.class, Object.class));
                }
                return new Handles(components, types, getters);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return new Handles(components, types, null);
            }
        }
    };

    /** Whether "may this runtime class be a leaf" holds, per class. */
    private static final ClassValue<Boolean> LEAF_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return SourceModels.leafClass(type);
        }
    };

    /** Where in the source value the walker is. */
    private record Pos(String path, Type declared, int depth, int level, boolean inMap) {
        Pos child(String childPath, Type childDeclared, int childLevel, boolean childInMap) {
            return new Pos(childPath, childDeclared, depth + 1, childLevel, childInMap);
        }
    }

    @FunctionalInterface
    private interface Read<T> {
        T get() throws Throwable;
    }

    /** Control flow of the lenient mode: this position is dropped from the tree. Never escapes the walker. */
    private static final class Skip extends RuntimeException {
        Skip() {
            super(null, null, false, false);
        }
    }

    private static final Skip SKIP = new Skip();

    private final boolean lenient;
    private final Set<String> prohibited = new HashSet<>();

    private SourceRecordWalker(boolean lenient) {
        this.lenient = lenient;
    }

    // ------------------------------------------------------------------ entry points

    /** The strict walk: a tree, or a {@link PrivacyRefusedException}. */
    static JsonNode of(Object source) {
        Objects.requireNonNull(source, "source");
        return new SourceRecordWalker(false).root(source);
    }

    /** The lenient walk behind {@link SourceValues#prohibited}. Throws nothing but a {@code VirtualMachineError}. */
    static Lenient lenient(Object source) {
        Objects.requireNonNull(source, "source");
        SourceRecordWalker walker = new SourceRecordWalker(true);
        JsonNode tree = null;
        try {
            tree = walker.root(source);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // Unreadable: nothing more is known about the value.
        }
        return new Lenient(tree, walker.prohibited);
    }

    /** Whether the engine can read the record class's private fields (a named module may not open its package). */
    static boolean readable(Class<?> record) {
        return HANDLES.get(record).readable();
    }

    private JsonNode root(Object source) {
        Class<?> c = source.getClass();
        Pos pos = new Pos("$", c, 0, 0, false);
        try {
            if (!(source instanceof JsonNode) && !c.isRecord()) {
                throw refuse(SourceModels.CODE, pos, notARecord(c), source);
            }
            return build(source, pos);
        } catch (Skip s) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the walk

    private JsonNode node(Object value, Pos pos) {
        try {
            return build(value, pos);
        } catch (Skip s) {
            return null;
        }
    }

    private JsonNode build(Object v, Pos pos) {
        if (v == null) {
            return nodes().nullNode();
        }
        Class<?> c = v.getClass();
        // A position that declares a record holds that record or nothing: a heap-polluted list that holds a
        // string where Contact was declared is refused, not written as a string. (A record value is judged by
        // the ordered rules in record().)
        if (!c.isRecord()) {
            Class<?> declared = rawOf(pos.declared());
            if (declared != null && declared.isRecord()) {
                throw refuse(SourceModels.RECORD_NOT_DECLARED_CODE, pos,
                        name(declared) + " is declared here, and this is not one", v);
            }
        }
        if (v instanceof JsonNode node) {
            return jsonNode(node, pos);
        }
        if (c == Optional.class) {
            Optional<?> optional = (Optional<?>) v;
            if (!guarded(pos, optional::isPresent)) {
                return nodes().nullNode();
            }
            enter(pos);
            return build(guarded(pos, optional::get), new Pos(pos.path(), optionalArgument(pos.declared()),
                    pos.depth() + 1, pos.level(), pos.inMap()));
        }
        if (c == OptionalInt.class) {
            OptionalInt optional = (OptionalInt) v;
            return guarded(pos, optional::isPresent)
                    ? leafNode(guarded(pos, optional::getAsInt), Integer.class, pos) : nodes().nullNode();
        }
        if (c == OptionalLong.class) {
            OptionalLong optional = (OptionalLong) v;
            return guarded(pos, optional::isPresent)
                    ? leafNode(guarded(pos, optional::getAsLong), Long.class, pos) : nodes().nullNode();
        }
        if (c == OptionalDouble.class) {
            OptionalDouble optional = (OptionalDouble) v;
            return guarded(pos, optional::isPresent)
                    ? leafNode(guarded(pos, optional::getAsDouble), Double.class, pos) : nodes().nullNode();
        }
        if (c.isRecord()) {
            return record(v, c, pos);
        }
        if (v instanceof Enum<?> e) {
            return nodes().stringNode(guarded(pos, e::name));
        }
        if (c == byte[].class || c == char[].class) {
            return leafNode(v, c, pos);
        }
        if (c.isArray()) {
            return array(v, pos);
        }
        if (v instanceof Collection<?> collection) {
            return collection(collection, c, pos);
        }
        if (v instanceof Map<?, ?> map) {
            return map(map, c, pos);
        }
        if (SourceModels.referenceOrSingleUse(c)) {
            throw refuse(SourceModels.CODE, pos, notARecord(c), v);
        }
        return leafNode(v, c, pos);
    }

    // ------------------------------------------------------------------ records

    private JsonNode record(Object v, Class<?> c, Pos pos) {
        // In this order, so that a single offending record gets the code startup gives it.
        if (pos.inMap()) {
            throw refuse(SourceModels.MAP_OF_RECORD_CODE, pos, "a record must not be a map value", v);
        }
        if (c.getTypeParameters().length > 0) {
            throw refuse(SourceModels.GENERIC_CODE, pos, name(c) + " is generic; source models must not be", v);
        }
        if (pos.level() >= 2) {
            throw refuse(SourceModels.NESTED_COLLECTION_OF_RECORD_CODE, pos,
                    "a record must not sit inside a collection inside a collection", v);
        }
        if (rawOf(pos.declared()) != c) {
            throw refuse(SourceModels.RECORD_NOT_DECLARED_CODE, pos,
                    name(c) + " is not the declared type of its position", v);
        }
        enter(pos);
        Handles handles = guarded(pos, () -> HANDLES.get(c));
        if (!handles.readable()) {
            throw refuse(SourceModels.NOT_READABLE_CODE, pos,
                    name(c) + " cannot be read: its package is not opened to the engine", v);
        }
        ObjectNode out = nodes().objectNode();
        for (int i = 0; i < handles.components().length; i++) {
            String component = handles.components()[i].getName();
            MethodHandle getter = handles.getters()[i];
            Pos child = pos.child(pos.path() + "." + component, handles.types()[i], 0, false);
            Object value;
            try {
                value = guarded(child, () -> (Object) getter.invokeExact(v));
            } catch (Skip s) {
                continue;
            }
            JsonNode converted = node(value, child);
            if (converted != null) {
                out.set(component, converted);
            }
        }
        if (!lenient) {
            requireComponentExact(out, c);
        }
        return out;
    }

    /** The defence-in-depth check: a built record node has exactly its record's component names, in order. */
    static void requireComponentExact(ObjectNode node, Class<?> record) {
        List<String> expected = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            expected.add(component.getName());
        }
        List<String> actual = new ArrayList<>(node.propertyNames());
        if (!expected.equals(actual)) {
            throw new PrivacyRefusedException(SourceModels.TREE_NOT_COMPONENT_EXACT_CODE, "$",
                    name(record) + " was not built from exactly its components");
        }
    }

    // ------------------------------------------------------------------ containers

    private JsonNode array(Object v, Pos pos) {
        enter(pos);
        int length = guarded(pos, () -> Array.getLength(v));
        ArrayNode out = nodes().arrayNode();
        Pos child = pos.child(pos.path() + "[*]", elementType(pos.declared()),
                pos.level() + (isLevelType(pos.declared()) ? 1 : 0), pos.inMap());
        for (int i = 0; i < length; i++) {
            int index = i;
            Object element;
            try {
                element = guarded(child, () -> Array.get(v, index));
            } catch (Skip s) {
                break;
            }
            JsonNode converted = node(element, child);
            if (converted != null) {
                out.add(converted);
            }
        }
        return out;
    }

    private JsonNode collection(Collection<?> v, Class<?> c, Pos pos) {
        if (!SourceModels.platform(c)) {
            throw refuse(SourceModels.CODE, pos, notARecord(c), v);
        }
        enter(pos);
        Iterator<?> iterator = guarded(pos, v::iterator);
        ArrayNode out = nodes().arrayNode();
        Pos child = pos.child(pos.path() + "[*]", elementType(pos.declared()),
                pos.level() + (isLevelType(pos.declared()) ? 1 : 0), pos.inMap());
        while (true) {
            Object element;
            try {
                if (!guarded(pos, iterator::hasNext)) {
                    break;
                }
                element = guarded(pos, iterator::next);
            } catch (Skip s) {
                break;
            }
            JsonNode converted = node(element, child);
            if (converted != null) {
                out.add(converted);
            }
        }
        return out;
    }

    private JsonNode map(Map<?, ?> v, Class<?> c, Pos pos) {
        if (!SourceModels.platform(c)) {
            throw refuse(SourceModels.CODE, pos, notARecord(c), v);
        }
        enter(pos);
        Iterator<?> iterator = guarded(pos, () -> v.entrySet().iterator());
        ObjectNode out = nodes().objectNode();
        Pos child = pos.child(pos.path() + "." + "<undeclared>", mapValueType(pos.declared()), pos.level(), true);
        while (true) {
            Map.Entry<?, ?> entry;
            Object key;
            Object value;
            try {
                if (!guarded(pos, iterator::hasNext)) {
                    break;
                }
                entry = guarded(pos, () -> (Map.Entry<?, ?>) iterator.next());
                key = guarded(pos, entry::getKey);
                value = guarded(pos, entry::getValue);
            } catch (Skip s) {
                break;
            }
            String text;
            try {
                text = keyText(key, pos);
            } catch (Skip s) {
                // The key is refused (lenient): its value is still prohibited.
                collectAll(value, pos.depth() + 1);
                continue;
            }
            JsonNode converted = node(value, child);
            if (converted != null) {
                out.set(text, converted);
            }
        }
        return out;
    }

    /** A map key's text: an enum by {@code name()}, a string as it is, every other allowed JDK key as Jackson writes it. */
    private String keyText(Object key, Pos pos) {
        if (key == null) {
            throw refuse(SourceModels.CODE, pos, "a map key must not be null", null);
        }
        Class<?> kc = key.getClass();
        if (key instanceof Enum<?> e) {
            return guarded(pos, e::name);
        }
        if (kc == String.class) {
            return (String) key;
        }
        boolean allowed;
        try {
            SourceModels.refuseKey(kc);
            allowed = kc != Object.class;
        } catch (PrivacyRefusedException e) {
            allowed = false;
        }
        if (!allowed) {
            throw refuse(SourceModels.CODE, pos, notARecord(kc), key);
        }
        return guarded(pos, () -> SourceTree.leaf(key, true).stringValue());
    }

    // ------------------------------------------------------------------ leaves

    private JsonNode leafNode(Object v, Class<?> c, Pos pos) {
        JsonNodeFactory f = nodes();
        if (c == String.class) {
            return f.stringNode((String) v);
        }
        if (c == Boolean.class) {
            return f.booleanNode((Boolean) v);
        }
        if (c == Integer.class) {
            return f.numberNode((Integer) v);
        }
        if (c == Long.class) {
            return f.numberNode((Long) v);
        }
        boolean leaf = guarded(pos, () -> LEAF_CLASS.get(c));
        if (!leaf) {
            throw refuse(SourceModels.CODE, pos, notARecord(c), v);
        }
        // The leaf reader gets a copy of a byte array, never the source's own.
        Object toWrite = c == byte[].class ? ((byte[]) v).clone() : v;
        return guarded(pos, () -> SourceTree.leaf(toWrite, false));
    }

    // ------------------------------------------------------------------ JsonNode normalisation

    private JsonNode jsonNode(JsonNode source, Pos pos) {
        boolean[] foreign = new boolean[1];
        JsonNode copy = copyNode(source, pos, pos.depth(), foreign);
        if (foreign[0]) {
            if (copy != null) {
                addNodeScalars(copy);
            }
            throw SKIP;
        }
        return copy;
    }

    /**
     * Checks one source node's class, creates its copy and attaches it, then reads its children, each
     * checked and copied the same way. The class is compared exactly before any method of the node is
     * called, and every node is created through the walker's own factory, never the source node's.
     */
    private JsonNode copyNode(JsonNode n, Pos pos, int depth, boolean[] foreign) {
        if (n == null) {
            return foreignNode(pos, foreign);
        }
        JsonNodeFactory f = nodes();
        Class<?> c = n.getClass();
        if (c == ObjectNode.class) {
            if (depth >= MAX_DEPTH) {
                return tooDeep(foreign);
            }
            ObjectNode copy = f.objectNode();
            Iterator<?> iterator = guarded(pos, () -> ((ObjectNode) n).properties().iterator());
            while (true) {
                Map.Entry<?, ?> entry;
                String key;
                Object value;
                try {
                    if (!guarded(pos, iterator::hasNext)) {
                        break;
                    }
                    entry = guarded(pos, () -> (Map.Entry<?, ?>) iterator.next());
                    key = guarded(pos, () -> (String) entry.getKey());
                    value = guarded(pos, entry::getValue);
                } catch (Skip s) {
                    break;
                }
                JsonNode child = value instanceof JsonNode jn ? copyNode(jn, pos, depth + 1, foreign)
                        : foreignNode(pos, foreign);
                if (child != null && key != null) {
                    copy.set(key, child);
                } else if (key == null) {
                    foreignNode(pos, foreign);
                }
            }
            return copy;
        }
        if (c == ArrayNode.class) {
            if (depth >= MAX_DEPTH) {
                return tooDeep(foreign);
            }
            ArrayNode copy = f.arrayNode();
            Iterator<?> iterator = guarded(pos, () -> ((ArrayNode) n).iterator());
            while (true) {
                Object element;
                try {
                    if (!guarded(pos, iterator::hasNext)) {
                        break;
                    }
                    element = guarded(pos, iterator::next);
                } catch (Skip s) {
                    break;
                }
                JsonNode child = element instanceof JsonNode jn ? copyNode(jn, pos, depth + 1, foreign)
                        : foreignNode(pos, foreign);
                if (child != null) {
                    copy.add(child);
                }
            }
            return copy;
        }
        if (c == StringNode.class) {
            return f.stringNode(n.stringValue());
        }
        if (c == IntNode.class) {
            return f.numberNode(n.intValue());
        }
        if (c == LongNode.class) {
            return f.numberNode(n.longValue());
        }
        if (c == ShortNode.class) {
            return f.numberNode(n.shortValue());
        }
        if (c == FloatNode.class) {
            return f.numberNode(n.floatValue());
        }
        if (c == DoubleNode.class) {
            return f.numberNode(n.doubleValue());
        }
        if (c == DecimalNode.class) {
            BigDecimal number = n.decimalValue();
            // Trailing zeros are stripped, as the reader the base commit used wrote them (1.50 is 1.5).
            return number.getClass() == BigDecimal.class ? f.numberNode(number.stripTrailingZeros())
                    : foreignNode(pos, foreign);
        }
        if (c == BigIntegerNode.class) {
            BigInteger number = n.bigIntegerValue();
            return number.getClass() == BigInteger.class ? f.numberNode(number) : foreignNode(pos, foreign);
        }
        if (c == BooleanNode.class) {
            return f.booleanNode(n.booleanValue());
        }
        if (c == NullNode.class) {
            return f.nullNode();
        }
        if (c == BinaryNode.class) {
            return f.binaryNode(n.binaryValue().clone());
        }
        return foreignNode(pos, foreign);
    }

    /** Past the bound: strict refuses; the lenient copy keeps what it has and drops only this position. */
    private JsonNode tooDeep(boolean[] foreign) {
        if (!lenient) {
            enterAt(MAX_DEPTH);
        }
        foreign[0] = true;
        return null;
    }

    private JsonNode foreignNode(Pos pos, boolean[] foreign) {
        if (!lenient) {
            throw new PrivacyRefusedException(SourceModels.FOREIGN_NODE_CODE, pos.path(),
                    "a JSON node is not one of Jackson's own node classes");
        }
        foreign[0] = true;
        return null;
    }

    private void addNodeScalars(JsonNode node) {
        if (node.isValueNode()) {
            addText(node.asString());
        } else {
            node.forEach(this::addNodeScalars);
        }
    }

    // ------------------------------------------------------------------ refusals and reads

    private void enter(Pos pos) {
        enterAt(pos.depth());
    }

    private void enterAt(int depth) {
        if (depth >= MAX_DEPTH) {
            if (lenient) {
                throw SKIP;
            }
            throw new PrivacyRefusedException(SourceModels.TOO_DEEP_CODE, "$",
                    "the source value is nested more than " + MAX_DEPTH + " levels deep");
        }
    }

    /**
     * The refusal to throw at {@code pos}. In the lenient mode there is none: every scalar under the
     * value is prohibited instead, and the position is dropped.
     */
    private RuntimeException refuse(String code, Pos pos, String detail, Object value) {
        if (!lenient) {
            return new PrivacyRefusedException(code, pos.path(), detail);
        }
        collectAll(value, pos.depth());
        return SKIP;
    }

    /**
     * One read of the source value. Anything it throws (a checked exception included, as Java does not
     * stop one at run time) other than a {@code VirtualMachineError} refuses this position alone. The
     * walker's own refusal found in the cause chain passes through. The interrupt flag is never touched.
     */
    private <T> T guarded(Pos pos, Read<T> read) {
        try {
            return read.get();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            PrivacyRefusedException refused = refusalIn(e);
            if (lenient) {
                throw SKIP;
            }
            if (refused != null) {
                // Same code and detail; the path is this position's, since the guard that raised it knew only "$".
                String message = refused.getMessage();
                int detail = message.indexOf(": ");
                throw new PrivacyRefusedException(refused.code(), pos.path(),
                        detail < 0 ? message : message.substring(detail + 2));
            }
            LOG.error("A source value could not be read: code={} path={} cause={}",
                    SourceModels.VALUE_READ_FAILED_CODE, pos.path(), e.getClass().getName());
            throw new PrivacyRefusedException(SourceModels.VALUE_READ_FAILED_CODE, pos.path(),
                    "a value could not be read");
        }
    }

    /**
     * The first {@link PrivacyRefusedException} in {@code t}'s cause chain, the throwable itself included, or
     * null. The walk is bounded: it stops after 32 steps or at a throwable it has already seen, so a cyclic
     * chain, or a {@code getCause()} that never returns null, cannot loop forever. A {@code getCause()} that
     * throws ends the walk.
     */
    static PrivacyRefusedException refusalIn(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = t;
        try {
            for (int steps = 0; cause != null && steps < MAX_CAUSES && seen.add(cause); steps++) {
                if (cause instanceof PrivacyRefusedException refused) {
                    return refused;
                }
                cause = cause.getCause();
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // The chain could not be read: no refusal found.
        }
        return null;
    }

    // ------------------------------------------------------------------ the lenient collection

    /**
     * Adds every scalar under {@code v} that can be reached without serialising anything, by the value's own
     * runtime class. A holder is read only from a closed allowlist and only when its class is exactly a listed
     * one; nothing else is read. Each read is caught, so this throws nothing but a {@code VirtualMachineError}.
     */
    private void collectAll(Object v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return;
        }
        try {
            Class<?> c = v.getClass();
            if (v instanceof JsonNode node) {
                boolean[] foreign = new boolean[1];
                Pos pos = new Pos("$", null, depth, 0, false);
                JsonNode copy = copyNode(node, pos, depth, foreign);
                if (copy != null) {
                    addNodeScalars(copy);
                }
            } else if (c == Optional.class) {
                Optional<?> optional = (Optional<?>) v;
                if (optional.isPresent()) {
                    collectAll(optional.get(), depth + 1);
                }
            } else if (c == OptionalInt.class) {
                OptionalInt optional = (OptionalInt) v;
                if (optional.isPresent()) {
                    addText(String.valueOf(optional.getAsInt()));
                }
            } else if (c == OptionalLong.class) {
                OptionalLong optional = (OptionalLong) v;
                if (optional.isPresent()) {
                    addText(String.valueOf(optional.getAsLong()));
                }
            } else if (c == OptionalDouble.class) {
                OptionalDouble optional = (OptionalDouble) v;
                if (optional.isPresent()) {
                    addText(String.valueOf(optional.getAsDouble()));
                }
            } else if (c.isRecord()) {
                collectRecord(v, c, depth);
            } else if (v instanceof Enum<?> e) {
                addText(e.name());
            } else if (c == AtomicReference.class) {
                collectAll(((AtomicReference<?>) v).get(), depth + 1);
            } else if (c == AtomicReferenceArray.class) {
                AtomicReferenceArray<?> array = (AtomicReferenceArray<?>) v;
                for (int i = 0; i < array.length(); i++) {
                    collectAll(array.get(i), depth + 1);
                }
            } else if (c == AtomicStampedReference.class) {
                collectAll(((AtomicStampedReference<?>) v).getReference(), depth + 1);
            } else if (c == AtomicMarkableReference.class) {
                collectAll(((AtomicMarkableReference<?>) v).getReference(), depth + 1);
            } else if (c == CompletableFuture.class) {
                CompletableFuture<?> future = (CompletableFuture<?>) v;
                if (future.state() == Future.State.SUCCESS) {
                    collectAll(future.resultNow(), depth + 1);
                }
            } else if (c == FutureTask.class) {
                FutureTask<?> future = (FutureTask<?>) v;
                if (future.state() == Future.State.SUCCESS) {
                    collectAll(future.resultNow(), depth + 1);
                }
            } else if (c.isArray() && c != byte[].class && c != char[].class) {
                int length = Array.getLength(v);
                for (int i = 0; i < length; i++) {
                    collectAll(Array.get(v, i), depth + 1);
                }
            } else if (v instanceof Collection<?> collection && SourceModels.platform(c)) {
                collectIterator(collection.iterator(), depth + 1);
            } else if (v instanceof Map<?, ?> map && SourceModels.platform(c)) {
                collectMap(map, depth + 1);
            } else if (c == String.class) {
                addText((String) v);
            } else if (!SourceModels.referenceOrSingleUse(c) && !(v instanceof Collection) && !(v instanceof Map)
                    && LEAF_CLASS.get(c)) {
                JsonNode leaf = SourceTree.leaf(c == byte[].class ? ((byte[]) v).clone() : v, false);
                String binary = SourceValues.binaryText(leaf);
                if (binary != null) {
                    addText(binary);
                } else if (leaf.getClass() != tools.jackson.databind.node.POJONode.class) {
                    addText(leaf.asString());
                }
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // Unreadable: prohibited-unknown, nothing more is added under it.
        }
    }

    private void collectIterator(Iterator<?> iterator, int depth) {
        try {
            while (iterator.hasNext()) {
                collectAll(iterator.next(), depth);
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // A container whose iteration threw is not iterated further.
        }
    }

    private void collectMap(Map<?, ?> map, int depth) {
        try {
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                Object key = entry.getKey();
                try {
                    String text = key == null ? null : keyText(key, new Pos("$", null, depth, 0, true));
                    if (text != null) {
                        addText(text);
                    }
                } catch (VirtualMachineError e) {
                    throw e;
                } catch (Throwable e) {
                    // A key that cannot be written is not collected.
                }
                collectAll(entry.getValue(), depth);
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // A container whose iteration threw is not iterated further.
        }
    }

    private void collectRecord(Object v, Class<?> c, int depth) {
        Handles handles = HANDLES.get(c);
        for (int i = 0; i < handles.components().length; i++) {
            try {
                Object value;
                if (handles.readable()) {
                    value = (Object) handles.getters()[i].invokeExact(v);
                } else {
                    // Field access is denied: whatever the (public) accessors return is prohibited.
                    value = handles.components()[i].getAccessor().invoke(v);
                }
                collectAll(value, depth + 1);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                // This component is prohibited-unknown.
            }
        }
    }

    private void addText(String text) {
        if (text != null && !text.isBlank()) {
            prohibited.add(text);
        }
    }

    // ------------------------------------------------------------------ declared types

    private static JsonNodeFactory nodes() {
        return SourceTree.nodes();
    }

    /** A type variable or wildcard read by its upper bound; an unbounded one, a raw type and Object declare nothing. */
    private static Type unbound(Type type) {
        Type t = type;
        for (int i = 0; i < 8 && t != null; i++) {
            if (t instanceof WildcardType w) {
                Type[] upper = w.getUpperBounds();
                t = upper.length > 0 ? upper[0] : null;
            } else if (t instanceof TypeVariable<?> variable) {
                Type[] bounds = variable.getBounds();
                t = bounds.length > 0 ? bounds[0] : null;
            } else {
                break;
            }
        }
        if (t == Object.class || t instanceof WildcardType || t instanceof TypeVariable<?>) {
            return null;
        }
        return t;
    }

    private static Class<?> rawOf(Type type) {
        Type t = unbound(type);
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    private static Type optionalArgument(Type declared) {
        Type t = unbound(declared);
        if (t instanceof ParameterizedType p && p.getRawType() == Optional.class) {
            return p.getActualTypeArguments()[0];
        }
        return null;
    }

    private static Type elementType(Type declared) {
        Type t = unbound(declared);
        if (t instanceof Class<?> c && c.isArray()) {
            return c.getComponentType();
        }
        if (t instanceof GenericArrayType g) {
            return g.getGenericComponentType();
        }
        if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
                && Iterable.class.isAssignableFrom(raw) && p.getActualTypeArguments().length == 1) {
            return p.getActualTypeArguments()[0];
        }
        return null;
    }

    private static Type mapValueType(Type declared) {
        Type t = unbound(declared);
        if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
                && Map.class.isAssignableFrom(raw) && p.getActualTypeArguments().length == 2) {
            return p.getActualTypeArguments()[1];
        }
        return null;
    }

    /** Whether the declared type puts the values inside it one array level down (as {@code recordBeyondFirstArray} counts). */
    private static boolean isLevelType(Type declared) {
        Type t = unbound(declared);
        if (t instanceof Class<?> c) {
            return c.isArray();
        }
        if (t instanceof GenericArrayType) {
            return true;
        }
        return t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
                && Iterable.class.isAssignableFrom(raw);
    }

    // ------------------------------------------------------------------ messages

    private static String name(Class<?> type) {
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String full = type.getName();
        return full.substring(full.lastIndexOf('.') + 1);
    }

    private static String notARecord(Class<?> type) {
        return name(type) + " is not a record; source models must be records";
    }
}
