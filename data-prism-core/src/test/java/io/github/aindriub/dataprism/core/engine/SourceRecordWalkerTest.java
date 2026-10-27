package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.POJONode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.databind.node.ValueNode;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The strict walk {@link SourceTree#of} follows (task 288): position rule, containers, nodes, leaves, depth.
 * All values are invented; the reserved domain is {@code example.invalid}.
 */
class SourceRecordWalkerTest {

    private static final String EMAIL = "ada@example.invalid";

    record Contact(String name) {
    }

    record Decoy(String name) {
    }

    interface Named {
    }

    record NamedContact(String name) implements Named {
    }

    record AtObject(Object v) {
    }

    record AtNamed(Named v) {
    }

    record AtRecord(Record v) {
    }

    record AtWild(List<?> v) {
    }

    @SuppressWarnings("rawtypes")
    record AtRaw(List v) {
    }

    record AtOptionalObject(Optional<Object> v) {
    }

    record AtList(List<Contact> v) {
    }

    record AtExtends(List<? extends Contact> v) {
    }

    record AtOptionalElements(List<Optional<Contact>> v) {
    }

    record AtOptionalList(Optional<List<Contact>> v) {
    }

    record AtArray(Contact[] v) {
    }

    private static PrivacyRefusedException refusal(Runnable action) {
        return (PrivacyRefusedException) catchThrowable(action);
    }

    private static Throwable catchThrowable(Runnable action) {
        return org.assertj.core.api.Assertions.catchThrowable(action::run);
    }

    private static void assertRefused(Runnable action, String code, String path) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.path()).isEqualTo(path);
            assertThat(e.getMessage()).doesNotContain(EMAIL);
        });
    }

    // ---- position rule -------------------------------------------------------------------------

    @Test
    @DisplayName("a record is accepted only where the declared type, after unwrapping, is exactly that record")
    void thePositionRuleRefusesARecordWhereTheDeclaredTypeIsNotThatRecord() {
        Contact contact = new Contact("n");
        String code = "SOURCE_MODEL_RECORD_NOT_DECLARED";
        assertRefused(() -> SourceTree.of(new AtObject(contact)), code, "$.v");
        assertRefused(() -> SourceTree.of(new AtNamed(new NamedContact("n"))), code, "$.v");
        assertRefused(() -> SourceTree.of(new AtRecord(contact)), code, "$.v");
        assertRefused(() -> SourceTree.of(new AtWild(List.of(contact))), code, "$.v[*]");
        assertRefused(() -> SourceTree.of(new AtRaw(List.of(contact))), code, "$.v[*]");
        assertRefused(() -> SourceTree.of(new AtOptionalObject(Optional.of(contact))), code, "$.v");
    }

    @Test
    @DisplayName("a List<Contact> polluted through a raw cast with a Decoy or a String is refused at the element")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void heapPollutionIsRefused() {
        List polluted = new ArrayList<>(List.of(new Contact("n")));
        polluted.add(new Decoy("d"));
        assertRefused(() -> SourceTree.of(new AtList(polluted)), "SOURCE_MODEL_RECORD_NOT_DECLARED", "$.v[*]");
        List strings = new ArrayList<>(List.of(new Contact("n")));
        strings.add("a string");
        assertRefused(() -> SourceTree.of(new AtList(strings)), "SOURCE_MODEL_RECORD_NOT_DECLARED", "$.v[*]");
    }

    @Test
    @DisplayName("an exact record, a wildcard bounded by it and an Optional of it are descended into")
    void declaredPositionsAreAccepted() {
        Contact contact = new Contact("n");
        assertThat(SourceTree.of(new AtExtends(List.of(contact))).get("v").get(0).get("name").asString())
                .isEqualTo("n");
        assertThat(SourceTree.of(new AtOptionalElements(List.of(Optional.of(contact)))).get("v").get(0).get("name")
                .asString()).isEqualTo("n");
        assertThat(SourceTree.of(new AtOptionalList(Optional.of(List.of(contact)))).get("v").get(0).get("name")
                .asString()).isEqualTo("n");
        assertThat(SourceTree.of(new AtArray(new Contact[]{contact})).get("v").get(0).get("name").asString())
                .isEqualTo("n");
    }

    // ---- map values and levels -----------------------------------------------------------------

    record Leaf(String name) {
    }

    record Envelope<T>(T item) {
    }

    record MapOfList(Map<String, List<Leaf>> v) {
    }

    record MapOfListOfList(Map<String, List<List<Leaf>>> v) {
    }

    record MapOfEnvelope(Map<String, Envelope<Leaf>> v) {
    }

    record MapOfObject(Map<String, Object> v) {
    }

    record ListOfListOfEnvelope(List<List<Envelope<Leaf>>> v) {
    }

    record ListOfList(List<List<Leaf>> v) {
    }

    record ObjectHolder(Object v) {
    }

    record ObjectListHolder(List<Object> v) {
    }

    @Test
    @DisplayName("a record anywhere below a map value is SOURCE_MODEL_MAP_OF_RECORD, as at startup")
    void mapValueRule() {
        Leaf leaf = new Leaf("n");
        String code = "SOURCE_MODEL_MAP_OF_RECORD";
        assertThat(refusal(() -> SourceTree.of(new MapOfList(Map.of("k", List.of(leaf))))).code()).isEqualTo(code);
        assertThat(refusal(() -> SourceTree.of(new MapOfListOfList(Map.of("k", List.of(List.of(leaf)))))).code())
                .isEqualTo(code);
        assertThat(refusal(() -> SourceTree.of(new MapOfEnvelope(Map.of("k", new Envelope<>(leaf))))).code())
                .isEqualTo(code);
        assertThat(refusal(() -> SourceTree.of(new MapOfObject(Map.of("k", List.of(leaf))))).code()).isEqualTo(code);
        for (Class<?> model : List.of(MapOfList.class, MapOfListOfList.class, MapOfEnvelope.class)) {
            assertThat(refusal(() -> SourceModels.require(model)).code()).isEqualTo(code);
        }
        assertThatCode(() -> SourceModels.require(MapOfObject.class)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a generic record is SOURCE_MODEL_GENERIC wherever it is not a map value, before the level rule")
    void genericRule() {
        Envelope<Leaf> envelope = new Envelope<>(new Leaf("n"));
        assertRefused(() -> SourceTree.of(new ObjectHolder(envelope)), "SOURCE_MODEL_GENERIC", "$.v");
        assertRefused(() -> SourceTree.of(new ListOfListOfEnvelope(List.of(List.of(envelope)))),
                "SOURCE_MODEL_GENERIC", "$.v[*][*]");
        assertThat(refusal(() -> SourceModels.require(ListOfListOfEnvelope.class)).code())
                .isEqualTo("SOURCE_MODEL_GENERIC");
    }

    @Test
    @DisplayName("a record at array level two is refused by the declared levels; an Object position declares none")
    void levelRule() {
        Leaf leaf = new Leaf("n");
        assertRefused(() -> SourceTree.of(new ListOfList(List.of(List.of(leaf)))),
                "SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD", "$.v[*][*]");
        assertThat(refusal(() -> SourceModels.require(ListOfList.class)).code())
                .isEqualTo("SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD");
        assertRefused(() -> SourceTree.of(new ObjectHolder(List.of(List.of(leaf)))),
                "SOURCE_MODEL_RECORD_NOT_DECLARED", "$.v[*][*]");
        assertRefused(() -> SourceTree.of(new ObjectListHolder(List.of(List.of(leaf)))),
                "SOURCE_MODEL_RECORD_NOT_DECLARED", "$.v[*][*]");
    }

    @Test
    @DisplayName("an unclassified record at an Object position is refused too")
    void anObjectPositionIsRefused() {
        assertRefused(() -> SourceTree.of(new ObjectHolder(new Leaf("n"))), "SOURCE_MODEL_RECORD_NOT_DECLARED", "$.v");
    }

    // ---- JsonNode normalisation ----------------------------------------------------------------

    record WithNode(JsonNode n) {
    }

    record DeepNodes(List<Map<String, JsonNode>> v) {
    }

    /** An object node whose every override records the call and throws. */
    static final class BlockingObject extends ObjectNode {
        final AtomicBoolean entered = new AtomicBoolean();

        BlockingObject() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public Set<Map.Entry<String, JsonNode>> properties() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }

        @Override
        public int size() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }

        @Override
        public Collection<JsonNode> values() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }

        @Override
        public JsonNode get(String name) {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }
    }

    static final class BlockingArray extends ArrayNode {
        final AtomicBoolean entered = new AtomicBoolean();

        BlockingArray() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public int size() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }

        @Override
        public JsonNode get(int index) {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }

        @Override
        public Collection<JsonNode> values() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }
    }

    @Test
    @DisplayName("a POJONode in a pre-built tree is a foreign node, and the lenient walk throws for none of them")
    void foreignNodes() {
        ObjectNode root = SourceTree.newObject();
        root.putPOJO("c", new Contact(EMAIL));
        assertRefused(() -> SourceTree.of(root), "SOURCE_MODEL_FOREIGN_NODE", "$");

        ObjectNode object = SourceTree.newObject();
        ArrayNode array = object.putArray("a");
        array.addPOJO(new Contact(EMAIL));
        DeepNodes deep = new DeepNodes(List.of(Map.of("k", object)));
        assertRefused(() -> SourceTree.of(deep), "SOURCE_MODEL_FOREIGN_NODE", "$.v[*].<undeclared>");

        assertThatCode(() -> SourceValues.prohibited(root, new DefaultFieldMetadataResolver()))
                .doesNotThrowAnyException();
        assertThatCode(() -> SourceValues.prohibited(deep, new DefaultFieldMetadataResolver()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a test subclass of ObjectNode or ArrayNode is refused before any of its methods is called")
    void subclassedNodesAreNeverEntered() {
        BlockingObject rootObject = new BlockingObject();
        assertRefused(() -> SourceTree.of(rootObject), "SOURCE_MODEL_FOREIGN_NODE", "$");
        assertThat(rootObject.entered).isFalse();
        SourceValues.prohibited(rootObject, new DefaultFieldMetadataResolver());
        assertThat(rootObject.entered).isFalse();

        BlockingObject inComponent = new BlockingObject();
        assertRefused(() -> SourceTree.of(new WithNode(inComponent)), "SOURCE_MODEL_FOREIGN_NODE", "$.n");
        SourceValues.prohibited(new WithNode(inComponent), new DefaultFieldMetadataResolver());
        assertThat(inComponent.entered).isFalse();

        BlockingArray array = new BlockingArray();
        assertRefused(() -> SourceTree.of(new WithNode(array)), "SOURCE_MODEL_FOREIGN_NODE", "$.n");
        SourceValues.prohibited(new WithNode(array), new DefaultFieldMetadataResolver());
        assertThat(array.entered).isFalse();

        // And one level down, inside a Jackson container.
        BlockingObject nested = new BlockingObject();
        ObjectNode holder = SourceTree.newObject();
        holder.set("deeper", nested);
        assertRefused(() -> SourceTree.of(new WithNode(holder)), "SOURCE_MODEL_FOREIGN_NODE", "$.n");
        assertThat(nested.entered).isFalse();
    }

    /** Every public non-final method the normalisation or the scrub could call records the call. */
    static final class EvilDecimal extends BigDecimal {
        final AtomicBoolean entered = new AtomicBoolean();

        EvilDecimal() {
            super("1.50");
        }

        @Override
        public String toString() {
            entered.set(true);
            return EMAIL;
        }

        @Override
        public String toPlainString() {
            entered.set(true);
            return EMAIL;
        }

        @Override
        public BigDecimal stripTrailingZeros() {
            entered.set(true);
            return this;
        }

        @Override
        public int scale() {
            entered.set(true);
            return 2;
        }

        @Override
        public int signum() {
            entered.set(true);
            return 1;
        }

        @Override
        public int compareTo(BigDecimal other) {
            entered.set(true);
            return 0;
        }

        @Override
        public boolean equals(Object other) {
            entered.set(true);
            return false;
        }

        @Override
        public int hashCode() {
            entered.set(true);
            return 1;
        }

        @Override
        public int intValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public long longValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public double doubleValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public float floatValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public BigInteger toBigInteger() {
            entered.set(true);
            return BigInteger.ONE;
        }

        @Override
        public BigInteger unscaledValue() {
            entered.set(true);
            return BigInteger.ONE;
        }
    }

    static final class EvilInteger extends BigInteger {
        final AtomicBoolean entered = new AtomicBoolean();

        EvilInteger() {
            super("5");
        }

        @Override
        public String toString() {
            entered.set(true);
            return EMAIL;
        }

        @Override
        public int signum() {
            entered.set(true);
            return 1;
        }

        @Override
        public int compareTo(BigInteger other) {
            entered.set(true);
            return 0;
        }

        @Override
        public boolean equals(Object other) {
            entered.set(true);
            return false;
        }

        @Override
        public int hashCode() {
            entered.set(true);
            return 1;
        }

        @Override
        public int intValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public long longValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public double doubleValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public float floatValue() {
            entered.set(true);
            return 1;
        }

        @Override
        public int bitLength() {
            entered.set(true);
            return 3;
        }
    }

    @Test
    @DisplayName("a DecimalNode or BigIntegerNode over a user subclass of the number is refused, calling nothing on it")
    void numberSubclassesAreNeverCalled() {
        EvilDecimal decimal = new EvilDecimal();
        DecimalNode decimalNode = DecimalNode.valueOf(decimal);
        EvilInteger integer = new EvilInteger();
        BigIntegerNode integerNode = BigIntegerNode.valueOf(integer);
        decimal.entered.set(false);
        integer.entered.set(false);

        assertRefused(() -> SourceTree.of(new WithNode(decimalNode)), "SOURCE_MODEL_FOREIGN_NODE", "$.n");
        assertRefused(() -> SourceTree.of(new WithNode(integerNode)), "SOURCE_MODEL_FOREIGN_NODE", "$.n");
        assertThat(SourceValues.prohibited(new WithNode(decimalNode), new DefaultFieldMetadataResolver()))
                .doesNotContain(EMAIL);
        assertThat(SourceValues.prohibited(new WithNode(integerNode), new DefaultFieldMetadataResolver()))
                .doesNotContain(EMAIL);
        assertThat(decimal.entered).isFalse();
        assertThat(integer.entered).isFalse();
    }

    @Test
    @DisplayName("a tree of Jackson's own nodes, with binary and decimal nodes in it, passes and equals the base commit's result")
    void aPlainTreePassesUnchanged() {
        ObjectNode tree = SourceTree.newObject();
        tree.put("text", "t");
        tree.put("int", 1);
        tree.put("long", 2L);
        tree.put("short", (short) 3);
        tree.put("double", 1.25);
        tree.put("float", 1.5f);
        tree.put("flag", true);
        tree.putNull("nothing");
        tree.put("bytes", new byte[]{1, 2, 3});
        tree.put("decimal", new BigDecimal("1.50"));
        tree.put("big", BigInteger.TEN);
        tree.putArray("list").add("a").add(1);
        tree.putObject("object").put("k", "v");

        JsonNode read = SourceTree.of(tree);
        // Compared as text: the reference reader turns a BinaryNode into a POJONode, which equals only itself.
        assertThat(read.toString()).isEqualTo(SourceTree.referenceTree(tree).toString());
        assertThat(read.get("bytes").binaryValue()).containsExactly(1, 2, 3);
        assertThat(read.get("decimal").decimalValue()).isEqualTo(new BigDecimal("1.5"));
        assertThat(SourceTree.of(new WithNode(DecimalNode.valueOf(new BigDecimal("1.50")))).get("n"))
                .isEqualTo(SourceTree.referenceTree(new WithNode(DecimalNode.valueOf(new BigDecimal("1.50")))).get("n"));
        assertThat(SourceTree.of(new WithNode(BigIntegerNode.valueOf(BigInteger.TEN))).get("n").bigIntegerValue())
                .isEqualTo(BigInteger.TEN);
    }

    // ---- JDK-class test, proxies, hidden classes, user containers ------------------------------

    private static Object proxyOf(Class<?> type, AtomicInteger calls) {
        InvocationHandler handler = (proxy, method, args) -> {
            calls.incrementAndGet();
            return switch (method.getName()) {
                case "toString" -> EMAIL;
                case "hashCode" -> 1;
                case "equals" -> false;
                default -> null;
            };
        };
        return Proxy.newProxyInstance(null, new Class<?>[]{type}, handler);
    }

    @Test
    @DisplayName("a bootstrap-defined dynamic proxy is refused as a leaf, and its handler is never called")
    void proxiesAreNeverCalled() {
        AtomicInteger calls = new AtomicInteger();
        for (Class<?> type : List.of(Path.class, CharSequence.class)) {
            Object proxy = proxyOf(type, calls);
            assertThat(refusal(() -> SourceTree.of(new ObjectHolder(proxy))).code())
                    .isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
            SourceValues.prohibited(new ObjectHolder(proxy), new DefaultFieldMetadataResolver());
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    @DisplayName("a map keyed by a proxy for TemporalAccessor is refused, and its handler is never called")
    void proxyKeysAreNeverCalled() {
        AtomicInteger calls = new AtomicInteger();
        Object proxy = proxyOf(java.time.temporal.TemporalAccessor.class, calls);
        Map<Object, String> map = Map.of(proxy, "v");
        calls.set(0);

        assertThat(refusal(() -> SourceTree.of(new ObjectHolder(map))).code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        SourceValues.prohibited(new ObjectHolder(map), new DefaultFieldMetadataResolver());
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a JDK lambda wrapping a user function is a hidden class, refused as a leaf and as a key")
    void hiddenClassesAreRefused() {
        AtomicInteger calls = new AtomicInteger();
        Function<String, String> userFn = s -> {
            calls.incrementAndGet();
            return s;
        };
        Comparator<String> lambda = Comparator.comparing(userFn);
        assertThat(refusal(() -> SourceTree.of(new ObjectHolder(lambda))).code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        Map<Object, String> keyed = new LinkedHashMap<>();
        keyed.put(lambda, "v");
        assertThat(refusal(() -> SourceTree.of(new ObjectHolder(keyed))).code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        SourceValues.prohibited(new ObjectHolder(lambda), new DefaultFieldMetadataResolver());
        SourceValues.prohibited(new ObjectHolder(keyed), new DefaultFieldMetadataResolver());
        assertThat(calls).hasValue(0);

        AtomicInteger proxyCalls = new AtomicInteger();
        assertThat(SourceModels.platform(lambda.getClass())).isFalse();
        assertThat(SourceModels.platform(String.class)).isTrue();
        assertThat(SourceModels.platform(Path.of("x").getClass())).isTrue();
        assertThat(SourceModels.platform(Instant.class)).isTrue();
        assertThat(SourceModels.platform(proxyOf(Path.class, proxyCalls).getClass())).isFalse();
    }

    /** A user subclass of ArrayList whose overrides record the call. */
    static final class BlockingList extends ArrayList<String> {
        final AtomicBoolean entered = new AtomicBoolean();

        @Override
        public Iterator<String> iterator() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }

        @Override
        public int size() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }

        @Override
        public Object[] toArray() {
            entered.set(true);
            throw new IllegalStateException("must not be called");
        }
    }

    @Test
    @DisplayName("a user subclass of ArrayList is refused by its class, and none of its methods is called")
    void userCollectionsAreNeverIterated() {
        BlockingList list = new BlockingList();
        assertThat(refusal(() -> SourceTree.of(new ObjectHolder(list))).code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        SourceValues.prohibited(new ObjectHolder(list), new DefaultFieldMetadataResolver());
        assertThat(list.entered).isFalse();
    }

    // ---- one tree ------------------------------------------------------------------------------

    record Mixed(JsonNode node, byte[] bytes) {
    }

    @Test
    @DisplayName("the returned tree shares no node or array with the source, and a later change cannot reach it")
    void theTreeIsACopy() {
        ObjectNode source = SourceTree.newObject();
        ArrayNode array = source.putArray("list");
        array.add("a");
        source.put("bin", new byte[]{1, 2, 3});
        byte[] sourceBytes = {9, 8, 7};
        Mixed mixed = new Mixed(source, sourceBytes);

        JsonNode tree = SourceTree.of(mixed);
        JsonNode snapshot = tree.deepCopy();

        source.put("added", "x");
        source.remove("list");
        source.putPOJO("pojo", new Contact(EMAIL));
        array.add("late");
        source.get("bin").binaryValue()[0] = 99;
        sourceBytes[0] = 77;

        assertThat(tree).isEqualTo(snapshot);
        assertThat(tree.get("node")).isNotSameAs(source);
        assertThat(tree.get("node").get("list")).isNotSameAs(array);
        assertThat(tree.get("node").get("bin").binaryValue()[0]).isEqualTo((byte) 1);
        JsonNode leaf = tree.get("bytes");
        assertThat(leaf).isInstanceOf(POJONode.class);
        Object held = ((POJONode) leaf).getPojo();
        assertThat(held.getClass()).isEqualTo(byte[].class);
        assertThat(held).isNotSameAs(sourceBytes);
        assertThat((byte[]) held).containsExactly(9, 8, 7);
    }

    @LlmExposedModel
    record Lazy(@InternalIdentifier String ref,
                @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                        suggestedAction = PrivacyAction.REDACT) List<String> v) {
    }

    /** Yields one element on its first iteration and two on every later one. */
    static final class Flipping extends AbstractList<String> {
        final AtomicInteger iterators = new AtomicInteger();

        @Override
        public Iterator<String> iterator() {
            int call = iterators.incrementAndGet();
            return (call == 1 ? List.of("first@example.invalid")
                    : List.of("first@example.invalid", "second@example.invalid")).iterator();
        }

        @Override
        public String get(int index) {
            return "first@example.invalid";
        }

        @Override
        public int size() {
            return 2;
        }
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static Map<String, PrivacyProfile> defaultProfiles() {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return PrivacyProfiles.fromYaml(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonTreeScrubbingEngine engine(Map<String, PrivacyProfile> profiles) {
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(profiles), (subjectId, namespace, ctx) -> "synthetic");
    }

    @Test
    @DisplayName("a collection is read once: one iterator() call, one element, nothing in clear")
    void aCollectionIsReadOnce() {
        Flipping flipping = new Flipping();
        var source = new Lazy("r", Collections.unmodifiableList(flipping));

        ScrubResult result = engine(defaultProfiles()).scrub(source, context());

        assertThat(flipping.iterators).hasValue(1);
        assertThat(result.tree().get("v").size()).isEqualTo(1);
        assertThat(result.tree().toString()).doesNotContain("first@example.invalid", "second@example.invalid");
    }

    record Extra(JsonNode extra) {
    }

    /** A map whose first entrySet() holds a plain string and whose later ones hold a record in a POJONode. */
    static final class ChangingChildren extends AbstractMap<String, JsonNode> {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Set<Entry<String, JsonNode>> entrySet() {
            int read = reads.incrementAndGet();
            JsonNode value = read == 1 ? JsonNodeFactory.instance.stringNode("plain")
                    : JsonNodeFactory.instance.pojoNode(new Contact(EMAIL));
            return Set.of(Map.entry("note", value));
        }
    }

    @Test
    @DisplayName("a node whose backing map changes between reads is read once, even under PASS_THROUGH_UNSAFE")
    void aNodeIsReadOnce() {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE, Map.of());
        assertThat(profile.unclassified()).isEqualTo(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE);
        ChangingChildren children = new ChangingChildren();
        ObjectNode node = new ObjectNode(JsonNodeFactory.instance, children);
        @LlmExposedModel
        record Model(@InternalIdentifier String ref, JsonNode extra) {
        }

        ScrubResult result = engine(Map.of("DEFAULT", profile)).scrub(new Model("r", node), context());

        assertThat(result.tree().get("extra").toString()).isEqualTo("{\"note\":\"plain\"}");
        assertThat(result.tree().toString()).doesNotContain(EMAIL);
        assertThat(children.reads).hasValue(1);
    }

    /** A node factory that counts every call from the moment it is built. */
    static final class CountingFactory extends JsonNodeFactory {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ObjectNode objectNode() {
            calls.incrementAndGet();
            return super.objectNode();
        }

        @Override
        public ArrayNode arrayNode() {
            calls.incrementAndGet();
            return super.arrayNode();
        }

        @Override
        public StringNode stringNode(String text) {
            calls.incrementAndGet();
            return super.stringNode(text);
        }
    }

    @Test
    @DisplayName("nodes are created through the walker's own factory, never the source node's")
    void theSourceNodesFactoryIsNotUsed() {
        CountingFactory factory = new CountingFactory();
        ObjectNode node = new ObjectNode(factory, new LinkedHashMap<>(Map.of("a", JsonNodeFactory.instance.stringNode("v"))));

        JsonNode tree = SourceTree.of(new WithNode(node));

        assertThat(factory.calls).hasValue(0);
        assertThat(tree.get("n").toString()).isEqualTo("{\"a\":\"v\"}");
    }

    // ---- single-use types ----------------------------------------------------------------------

    record WithStream(Stream<String> s) {
    }

    record WithIterator(Iterator<String> i) {
    }

    @Test
    @DisplayName("a Stream or an Iterator component is refused at startup and at scrub time")
    void singleUseTypesAreRefused() {
        for (Class<?> model : List.of(WithStream.class, WithIterator.class)) {
            assertThat(refusal(() -> SourceModels.require(model)).code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        }
        assertThat(refusal(() -> SourceTree.of(new WithStream(Stream.of("a")))).code())
                .isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        assertThat(refusal(() -> SourceTree.of(new WithIterator(List.of("a").iterator()))).code())
                .isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
    }

    // ---- accessors are not used ----------------------------------------------------------------

    record Swapped(String a, String b) {
        @Override
        public String a() {
            return b;
        }

        @Override
        public String b() {
            return a;
        }
    }

    record Locked(String a, String b) {
        @Override
        public String a() {
            return b;
        }

        @Override
        public String b() {
            return a;
        }
    }

    @Test
    @DisplayName("a record whose explicit accessors return each other's values is written by field")
    void accessorsAreNotCalled() {
        JsonNode tree = SourceTree.of(new Swapped("one", "two"));
        assertThat(tree.get("a").asString()).isEqualTo("one");
        assertThat(tree.get("b").asString()).isEqualTo("two");
    }

    @Test
    @DisplayName("with field access denied, startup and scrub refuse with SOURCE_MODEL_NOT_READABLE; prohibited reads the accessors")
    void unreadableRecords() {
        SourceRecordWalker.LookupProvider before = SourceRecordWalker.lookups;
        SourceRecordWalker.lookups = type -> {
            if (type == Locked.class) {
                throw new IllegalAccessException("package not opened");
            }
            return before.lookup(type);
        };
        try {
            assertRefused(() -> SourceTree.of(new Locked("one", "two")), "SOURCE_MODEL_NOT_READABLE", "$");
            assertThat(refusal(() -> SourceModels.require(Locked.class)).code()).isEqualTo("SOURCE_MODEL_NOT_READABLE");
            assertThat(SourceValues.prohibited(new Locked("one", "two"), new DefaultFieldMetadataResolver()))
                    .containsExactlyInAnyOrder("one", "two");
        } finally {
            SourceRecordWalker.lookups = before;
        }
    }

    // ---- annotations have no effect ------------------------------------------------------------

    public static class Custom extends ValueSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString("custom");
        }
    }

    record PlainOne(String a) {
    }

    record ValueMethod(String a) {
        @JsonValue
        public String a() {
            return a;
        }
    }

    @JsonSerialize(using = Custom.class)
    record ClassSerialized(String a) {
    }

    record ComponentSerialized(@JsonSerialize(using = Custom.class) String a) {
    }

    record PlainList(List<String> a) {
    }

    record ContentSerialized(@JsonSerialize(contentUsing = Custom.class) List<String> a) {
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    record ArrayShaped(String a) {
    }

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "a")
    record IdentityInfo(String a) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    record WrapperArray(String a) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    record WrapperObject(String a) {
    }

    record PlainTwo(String a, String b) {
    }

    record RenamedSwap(@JsonProperty("b") String a, @JsonProperty("a") String b) {
    }

    record Ignored(@JsonIgnore String a, String b) {
    }

    record PlainNull(String a, String b) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NonNull(String a, String b) {
    }

    record PlainCamel(String firstName) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Snake(String firstName) {
    }

    record PlainInner(PlainOne inner) {
    }

    record Unwrapped(@JsonUnwrapped PlainOne inner) {
    }

    @tools.jackson.databind.annotation.JsonAppend(props = @tools.jackson.databind.annotation.JsonAppend.Prop(
            value = SourceRecordReprosTest.Appender.class, name = "a"))
    record Appended(String a) {
    }

    @Test
    @DisplayName("a Jackson annotation on a record, a component, a method or an interface has no effect on the tree")
    void annotationsHaveNoEffect() {
        assertThat(SourceTree.of(new ValueMethod("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new ClassSerialized("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new ComponentSerialized("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new ContentSerialized(List.of("x"))))
                .isEqualTo(SourceTree.of(new PlainList(List.of("x"))));
        assertThat(SourceTree.of(new ArrayShaped("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new IdentityInfo("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new WrapperArray("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new WrapperObject("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new Appended("x"))).isEqualTo(SourceTree.of(new PlainOne("x")));
        assertThat(SourceTree.of(new RenamedSwap("x", "y"))).isEqualTo(SourceTree.of(new PlainTwo("x", "y")));
        assertThat(SourceTree.of(new Ignored("x", "y"))).isEqualTo(SourceTree.of(new PlainTwo("x", "y")));
        assertThat(SourceTree.of(new NonNull("x", null))).isEqualTo(SourceTree.of(new PlainNull("x", null)));
        assertThat(SourceTree.of(new Snake("x"))).isEqualTo(SourceTree.of(new PlainCamel("x")));
        assertThat(SourceTree.of(new Unwrapped(new PlainOne("x")))).isEqualTo(SourceTree.of(new PlainInner(new PlainOne("x"))));
    }

    public enum Plain {
        ALPHA
    }

    public enum Valued {
        BETA;

        @JsonValue
        public String text() {
            return "text-beta";
        }

        @Override
        public String toString() {
            return "to-string-beta";
        }
    }

    public enum Renamed {
        @JsonProperty("renamed-gamma") GAMMA
    }

    record Enums(Object value, Map<Object, Integer> keyed) {
    }

    @Test
    @DisplayName("an enum is its name() as a value and as a map key, whatever its annotations")
    void enumsAreWrittenByName() {
        assertThat(SourceTree.of(new Enums(Valued.BETA, Map.of(Valued.BETA, 1))).toString())
                .isEqualTo("{\"value\":\"BETA\",\"keyed\":{\"BETA\":1}}");
        assertThat(SourceTree.of(new Enums(Renamed.GAMMA, Map.of(Renamed.GAMMA, 1))).toString())
                .isEqualTo("{\"value\":\"GAMMA\",\"keyed\":{\"GAMMA\":1}}");
        assertThat(SourceTree.of(new Enums(Plain.ALPHA, Map.of(Plain.ALPHA, 1))).toString())
                .isEqualTo("{\"value\":\"ALPHA\",\"keyed\":{\"ALPHA\":1}}");
    }

    // ---- map keys ------------------------------------------------------------------------------

    record Keyed(Map<Object, Integer> m) {
    }

    @Test
    @DisplayName("every allowed key type is written as the reader wrote it on the base commit")
    void mapKeysMatchTheReferenceReader() {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(1_700_000_000_123L);
        List<Object> keys = List.of("s", 1, 1L, (short) 2, (byte) 3, 1.5d, 1.5f, true, 'c', new BigDecimal("1.50"),
                BigInteger.TEN, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Locale.forLanguageTag("en-IE"), new Date(0L), calendar, String.class, URI.create("urn:example:x"),
                Currency.getInstance("EUR"), TimeZone.getTimeZone("Europe/Dublin"), Instant.parse("2026-10-27T12:00:00Z"),
                Duration.ofMinutes(5), LocalDate.of(2026, 10, 27), ZoneId.of("Europe/Dublin"), ZoneOffset.UTC);
        for (Object key : keys) {
            Keyed keyed = new Keyed(Map.of(key, 1));
            assertThat(SourceTree.of(keyed)).as(key.getClass().getName()).isEqualTo(SourceTree.referenceTree(keyed));
        }
        Keyed bytes = new Keyed(Map.of(new byte[]{1, 2}, 1));
        assertThat(SourceTree.of(bytes)).isEqualTo(SourceTree.referenceTree(bytes));
    }

    // ---- leaves and depth ----------------------------------------------------------------------

    @Test
    @DisplayName("a Throwable, a java.awt.Point and a user class at an Object position are refused")
    void leavesThatAreNotScalars() {
        class User {
        }
        for (Object value : List.of(new IllegalStateException("x"), new java.awt.Point(1, 2), new User())) {
            assertThat(refusal(() -> SourceTree.of(new ObjectHolder(value))).code())
                    .isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        }
    }

    record Node(Node next) {
    }

    @Test
    @DisplayName("a self-containing list and a 10,000-link chain are TOO_DEEP, and prohibited returns normally")
    void depthIsBounded() {
        List<Object> self = new ArrayList<>();
        self.add(self);
        assertRefused(() -> SourceTree.of(new ObjectListHolder(self)), "TOO_DEEP", "$");
        assertThat(SourceValues.prohibited(new ObjectListHolder(self), new DefaultFieldMetadataResolver())).isEmpty();

        Node chain = new Node(null);
        for (int i = 0; i < 10_000; i++) {
            chain = new Node(chain);
        }
        Node deep = chain;
        assertRefused(() -> SourceTree.of(deep), "TOO_DEEP", "$");
        assertThatCode(() -> SourceValues.prohibited(deep, new DefaultFieldMetadataResolver()))
                .doesNotThrowAnyException();
    }

    // ---- component-exact check -----------------------------------------------------------------

    @Test
    @DisplayName("the component-exact check rejects an extra key, a missing key and a renamed key")
    void componentExactCheck() {
        ObjectNode exact = SourceTree.newObject();
        exact.put("a", "1");
        exact.put("b", "2");
        assertThatCode(() -> SourceRecordWalker.requireComponentExact(exact, PlainTwo.class)).doesNotThrowAnyException();

        ObjectNode extra = exact.deepCopy();
        extra.put("c", "3");
        ObjectNode missing = SourceTree.newObject();
        missing.put("a", "1");
        ObjectNode renamed = SourceTree.newObject();
        renamed.put("a", "1");
        renamed.put("z", "2");
        for (ObjectNode bad : List.of(extra, missing, renamed)) {
            assertThatThrownBy(() -> SourceRecordWalker.requireComponentExact(bad, PlainTwo.class))
                    .isInstanceOfSatisfying(PrivacyRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("SOURCE_TREE_NOT_COMPONENT_EXACT"));
        }
    }

    // ---- the record outcome of OD-288-1 (a) ----------------------------------------------------

    private static final String KEY = "zzUndeclaredKeyQx7@example.invalid";

    @LlmExposedModel
    record Open(@InternalIdentifier String ref, Map<String, Object> extra) {
    }

    @LlmExposedModel
    record OpenNonSensitive(@InternalIdentifier String ref,
                            @NonSensitive(reason = "reviewed") Map<String, Object> extra) {
    }

    @LlmExposedModel
    record OpenNode(@InternalIdentifier String ref, @NonSensitive(reason = "reviewed") JsonNode extra) {
    }

    @LlmExposedModel
    record AnyOnComponent(@InternalIdentifier String ref,
                          @JsonAnyGetter @NonSensitive(reason = "reviewed") Map<String, Object> extra) {
    }

    @LlmExposedModel
    record AnyOnMethod(@InternalIdentifier String ref, @NonSensitive(reason = "reviewed") String status) {
        @JsonAnyGetter
        public Map<String, Object> more() {
            return Map.of(KEY, "v");
        }
    }

    private static Map<String, PrivacyProfile> profile(PrivacyProfile.UnclassifiedBehaviour behaviour) {
        PrivacyProfile profile = new PrivacyProfile("DEFAULT", behaviour, Map.of());
        assertThat(profile.unclassified()).isEqualTo(behaviour);
        return Map.of("DEFAULT", profile);
    }

    @Test
    @DisplayName("extra keys in a Map or JsonNode component are never descended into; the profile decides the whole value")
    void extraKeysAreNotDescended() {
        // The bundled DEFAULT profile, whose unclassified setting is FAIL_REQUEST.
        var bundled = defaultProfiles();
        assertThat(bundled.get("DEFAULT").unclassified()).isEqualTo(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST);
        var extra = Map.<String, Object>of(KEY, "v");

        assertThatThrownBy(() -> engine(bundled).scrub(new Open("r", extra), context()))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("UNDECLARED_FIELD");
                    assertThat(e.path()).isEqualTo("$.extra");
                    assertThat(e.getMessage()).doesNotContain(KEY);
                });
        assertThatThrownBy(() -> engine(bundled).scrub(new OpenNonSensitive("r", extra), context()))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("UNCLASSIFIED_STRUCTURE");
                    assertThat(e.getMessage()).doesNotContain(KEY);
                    assertThat(e.path()).doesNotContain(KEY);
                });
        ObjectNode node = SourceTree.newObject();
        node.put(KEY, "v");
        assertThatThrownBy(() -> engine(bundled).scrub(new OpenNode("r", node), context()))
                .isInstanceOfSatisfying(PrivacyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("UNCLASSIFIED_STRUCTURE"));

        var redact = profile(PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN);
        ScrubResult redacted = engine(redact).scrub(new OpenNonSensitive("r", extra), context());
        assertThat(redacted.tree().get("extra").asString()).isEqualTo("[REDACTED]");
        assertThat(redacted.dispositions().keySet()).noneMatch(k -> k.contains(KEY));

        var passThrough = profile(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE);
        ScrubResult released = engine(passThrough).scrub(new OpenNonSensitive("r", extra), context());
        assertThat(released.tree().get("extra").toString()).isEqualTo("{\"" + KEY + "\":\"v\"}");
    }

    @Test
    @DisplayName("@JsonAnyGetter on a component or on a method is ignored: the record emits exactly its components")
    void anyGetterIsIgnored() {
        assertThat(SourceTree.of(new AnyOnComponent("r", Map.of(KEY, "v"))).propertyNames())
                .containsExactly("ref", "extra");
        assertThat(SourceTree.of(new AnyOnMethod("r", "ok")).propertyNames()).containsExactly("ref", "status");
    }

    // ---- the leaf method and its guard ---------------------------------------------------------

    public static class JsonValued {
        @JsonValue
        public String v() {
            return EMAIL;
        }
    }

    @Test
    @DisplayName("the leaf guard alone refuses a holder, a record, a user class and any container")
    void theLeafGuardStandsOnItsOwn() {
        for (Object value : List.of(new AtomicReference<>(new JsonValued()), new Contact(EMAIL), new JsonValued(),
                List.of("a"), Map.of("a", "b"), new int[]{1}, Optional.of("a"), Map.entry("a", "b"),
                new java.awt.Point(1, 2))) {
            assertThatThrownBy(() -> SourceTree.leaf(value, false)).as(value.getClass().getName())
                    .isInstanceOf(RuntimeException.class);
        }
        // A scalar is delegated.
        assertThat(SourceTree.leaf(Instant.parse("2026-10-27T12:00:00Z"), false).asString())
                .isEqualTo("2026-10-27T12:00:00Z");
        assertThat(SourceTree.leaf(new AtomicInteger(4), false).intValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("the leaf result check accepts a scalar and a byte[] POJONode, and refuses anything else")
    void theLeafResultCheck() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        assertThatCode(() -> SourceTree.checkedLeaf(f.stringNode("s"), String.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceTree.checkedLeaf(f.numberNode(1), Integer.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceTree.checkedLeaf(f.nullNode(), Object.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceTree.checkedLeaf(f.pojoNode(new byte[]{1}), byte[].class)).doesNotThrowAnyException();
        for (ValueNode bad : List.of(f.pojoNode("a string"), f.pojoNode(new char[]{'a'}), f.pojoNode(new Contact("n")))) {
            assertThatThrownBy(() -> SourceTree.checkedLeaf(bad, Object.class))
                    .isInstanceOfSatisfying(PrivacyRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD"));
        }
        for (JsonNode bad : List.of(f.objectNode(), f.arrayNode())) {
            assertThatThrownBy(() -> SourceTree.checkedLeaf(bad, Object.class))
                    .isInstanceOfSatisfying(PrivacyRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD"));
        }
    }

    public static class SpoofHolder {
    }

    @Test
    @DisplayName("a user class in a tools.jackson package is not delegated, and startup refuses it as a declared type")
    void aSpoofedPackageIsNotOwnership() {
        record Spoofed(tools.jackson.spoof.Spoof v) {
        }
        assertThat(refusal(() -> SourceModels.require(Spoofed.class)).code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        assertThat(refusal(() -> SourceTree.of(new ObjectHolder(new tools.jackson.spoof.Spoof()))).code())
                .isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
        assertThat(refusal(() -> SourceTree.of(new ObjectHolder(new io.github.aindriub.dataprism.core.engine.spoof.Spoof())))
                .code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
    }

    // ---- the one leaf rule ---------------------------------------------------------------------

    record OfPath(Path v) {
    }

    record OfFile(File v) {
    }

    record OfUri(URI v) {
    }

    record OfInet(InetAddress v) {
    }

    record OfInstant(Instant v) {
    }

    record OfAtomicInteger(AtomicInteger v) {
    }

    record OfAtomicReference(AtomicReference<String> v) {
    }

    record OfStream(Stream<String> v) {
    }

    record OfIterator(Iterator<String> v) {
    }

    record OfThrowable(Throwable v) {
    }

    record OfPoint(java.awt.Point v) {
    }

    record OfServiceLoader(ServiceLoader<Runnable> v) {
    }

    record OfIterable(Iterable<String> v) {
    }

    @Test
    @DisplayName("startup accepts a component of a type exactly when the walker accepts a value of it")
    void startupAndScrubAgreeOnLeaves() {
        Map<Class<?>, Object> samples = new LinkedHashMap<>();
        samples.put(OfPath.class, new OfPath(Path.of("x")));
        samples.put(OfFile.class, new OfFile(new File("x")));
        samples.put(OfUri.class, new OfUri(URI.create("urn:example:x")));
        samples.put(OfInet.class, new OfInet(InetAddress.getLoopbackAddress()));
        samples.put(OfInstant.class, new OfInstant(Instant.parse("2026-10-27T12:00:00Z")));
        samples.put(OfAtomicInteger.class, new OfAtomicInteger(new AtomicInteger(1)));
        samples.put(OfIterable.class, new OfIterable(new ArrayList<>(List.of("a"))));
        samples.put(OfAtomicReference.class, new OfAtomicReference(new AtomicReference<>("x")));
        samples.put(OfStream.class, new OfStream(Stream.of("x")));
        samples.put(OfIterator.class, new OfIterator(List.of("x").iterator()));
        samples.put(OfThrowable.class, new OfThrowable(new RuntimeException("x")));
        samples.put(OfPoint.class, new OfPoint(new java.awt.Point(1, 2)));
        samples.put(OfServiceLoader.class, new OfServiceLoader(ServiceLoader.load(Runnable.class)));
        Set<Class<?>> accepted = Set.of(OfPath.class, OfFile.class, OfUri.class, OfInet.class, OfInstant.class,
                OfAtomicInteger.class, OfIterable.class);
        samples.forEach((model, sample) -> {
            boolean startup = catchThrowable(() -> SourceModels.require(model)) == null;
            boolean scrub = catchThrowable(() -> SourceTree.of(sample)) == null;
            assertThat(startup).as("startup " + model.getSimpleName()).isEqualTo(accepted.contains(model));
            assertThat(scrub).as("scrub " + model.getSimpleName()).isEqualTo(accepted.contains(model));
        });
        // A Path is written as its URI text, as on the base commit.
        assertThat(SourceTree.of(new OfPath(Path.of("x")))).isEqualTo(SourceTree.referenceTree(new OfPath(Path.of("x"))));
    }

    // ---- startup: cause chains -----------------------------------------------------------------

    static final class CyclicException extends JacksonException {
        CyclicException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final AtomicInteger CYCLES = new AtomicInteger();

    public static class CyclicSerializer extends ValueSerializer<Object> {
        @Override
        public ValueSerializer<?> createContextual(SerializationContext ctxt, BeanProperty property) {
            CYCLES.incrementAndGet();
            RuntimeException x = new RuntimeException("x");
            RuntimeException y = new RuntimeException("y");
            x.initCause(y);
            y.initCause(x);
            throw new CyclicException("cyclic", x);
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString("never");
        }
    }

    @JsonSerialize(using = CyclicSerializer.class)
    record CyclicComponent(String s) {
    }

    record CyclicHolder(CyclicComponent c) {
    }

    @Test
    @DisplayName("a cyclic cause chain thrown while a serializer is built does not hang refuseIfReadByGetters")
    void aCyclicChainAtStartupTerminates() {
        CYCLES.set(0);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> SourceTree.refuseIfReadByGetters(CyclicHolder.class));
        // Whether the exception reached the catch is Jackson's: writerFor's prefetch swallows it on 3.1.8.
        assertThat(CYCLES.get()).isPositive();
    }
}
