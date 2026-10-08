package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A source model is a record all the way down; any other user class is refused with one stable code. */
class SourceModelsTest {

    public static class Bean {
        public String secret = "value-that-must-not-appear";
    }

    public enum Tier { GOLD }

    public record Inner(String name, Tier tier) {
    }

    public record Allowed(Inner inner, List<Inner> list, Map<String, Optional<Inner>> map, Inner[] array,
                          Instant at, UUID id, JsonNode node, Object any, Runnable iface) {
    }

    public record WithBean(String name, Bean bean) {
    }

    public record NestedBean(Inner inner, WithBean deeper) {
    }

    public record InList(List<Bean> beans) {
    }

    public record InMap(Map<String, Bean> beans) {
    }

    public record InOptional(Optional<Bean> bean) {
    }

    public record InArray(Bean[] beans) {
    }

    public record Untyped(Object any) {
    }

    public record Wild(List<? extends Bean> beans) {
    }

    private static void assertRefused(Runnable action) {
        assertRefused(action, "Bean");
    }

    private static void assertRefused(Runnable action, String name) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
                    assertThat(e.getMessage()).contains(name).doesNotContain("value-that-must-not-appear");
                });
    }

    @Test
    void theCodeIsAValidRefusalToken() {
        assertThat(io.github.aindriub.dataprism.core.refusal.RefusalCodes.sanitise(SourceModels.CODE))
                .isEqualTo(SourceModels.CODE);
    }

    @Test
    void aBeanAtTopLevelIsRefusedAtStartupAndAtRuntime() {
        assertRefused(() -> SourceModels.require(Bean.class));
        assertRefused(() -> SourceTree.of(new Bean()));
    }

    @Test
    void aTopLevelTypeThatIsNotARecordIsRefusedAtStartupJdkTypesIncluded() {
        assertRefused(() -> SourceModels.require(String.class), "String");
        assertRefused(() -> SourceModels.require(Tier.class), "Tier");
    }

    @Test
    void aBeanInARecordComponentIsRefusedAtStartup() {
        assertRefused(() -> SourceModels.require(WithBean.class));
        assertRefused(() -> SourceModels.require(NestedBean.class));
    }

    @Test
    void aBeanInsideACollectionMapOptionalArrayOrWildcardIsRefusedAtStartup() {
        assertRefused(() -> SourceModels.require(InList.class));
        assertRefused(() -> SourceModels.require(InMap.class));
        assertRefused(() -> SourceModels.require(InOptional.class));
        assertRefused(() -> SourceModels.require(InArray.class));
        assertRefused(() -> SourceModels.require(Wild.class));
    }

    @Test
    void aBeanIsRefusedAtRuntimeWhereverItSits() {
        assertRefused(() -> SourceTree.of(new WithBean("n", new Bean())));
        assertRefused(() -> SourceTree.of(new InList(List.of(new Bean()))));
        assertRefused(() -> SourceTree.of(new InMap(Map.of("k", new Bean()))));
        assertRefused(() -> SourceTree.of(new InOptional(Optional.of(new Bean()))));
        assertRefused(() -> SourceTree.of(new InArray(new Bean[]{new Bean()})));
    }

    @Test
    void aBeanBehindAnObjectTypedComponentIsPassedAtStartupButRefusedAtRuntime() {
        assertThatCode(() -> SourceModels.require(Untyped.class)).doesNotThrowAnyException();
        assertRefused(() -> SourceTree.of(new Untyped(new Bean())));
        assertRefused(() -> SourceTree.of(new Untyped(List.of(Map.of("k", new Bean())))));
    }

    @Test
    void aTopLevelNonRecordOfAnyKindIsRefused() {
        assertThatThrownBy(() -> SourceTree.of(Map.of("k", "v"))).isInstanceOf(PrivacyRefusedException.class);
        assertThatThrownBy(() -> SourceTree.of(Tier.GOLD)).isInstanceOf(PrivacyRefusedException.class);
    }

    @Test
    void recordsEnumsJdkTypesAndTreeNodesAreAllowed() {
        assertThatCode(() -> SourceModels.require(Allowed.class)).doesNotThrowAnyException();
        ObjectNode node = SourceTree.newObject();
        node.put("a", 1);
        Inner inner = new Inner("n", Tier.GOLD);
        JsonNode tree = SourceTree.of(new Allowed(inner, List.of(inner), Map.of("k", Optional.of(inner)),
                new Inner[]{inner}, Instant.parse("2026-10-08T12:00:00Z"), UUID.fromString(
                "00000000-0000-0000-0000-000000000001"), node, inner, null));
        assertThat(tree.get("inner").get("tier").asString()).isEqualTo("GOLD");
        assertThat(tree.get("node").get("a").intValue()).isEqualTo(1);
        assertThat(tree.get("any").get("name").asString()).isEqualTo("n");
    }

    @Test
    void aRecursiveRecordTypeTerminates() {
        assertThatCode(() -> SourceModels.require(Tree.class)).doesNotThrowAnyException();
    }

    public interface Urled {
        default String getURL() {
            return "from-interface";
        }
    }

    public record WithExtraGetters(String name) {
        public String getURL() {
            return "extra";
        }

        public boolean isXFlag() {
            return true;
        }
    }

    public record WithInterfaceGetter(String name) implements Urled {
    }

    public record Annotated(@JsonProperty("renamed") String a, @JsonIgnore String b, String c) {
    }

    public record WithAny(String a) {
        @JsonAnyGetter
        public Map<String, Object> any() {
            return Map.of("extra", 1);
        }
    }

    public record Valued(String a) {
        @JsonValue
        public String v() {
            return "as-value";
        }
    }

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    public enum ObjectEnum {
        A;

        public String getURL() {
            return "x";
        }
    }

    public enum ValueEnum {
        A;

        @JsonValue
        public String text() {
            return "text-a";
        }
    }

    public enum RenamedEnum {
        @JsonProperty("renamed-b") B
    }

    public static class Custom extends ValueSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString("custom");
        }
    }

    @JsonSerialize(using = Custom.class)
    public static class AnnotatedBean {
    }

    public static class MyList extends ArrayList<Object> {
    }

    public static class MyMap extends HashMap<String, Object> {
    }

    public record Key(String id) {
    }

    public record Boxed<T extends Bean>(T t) {
    }

    @Test
    void aRecordIsReadByItsComponentsOnly() {
        assertThat(SourceTree.of(new WithExtraGetters("n")).propertyNames()).containsExactly("name");
        assertThat(SourceTree.of(new WithInterfaceGetter("n")).propertyNames()).containsExactly("name");
    }

    @Test
    void recordComponentAnnotationsStillApply() {
        assertThat(SourceTree.of(new Annotated("a", "b", "c")).propertyNames()).containsExactly("renamed", "c");
        assertThat(SourceTree.of(new WithAny("a")).propertyNames()).containsExactlyInAnyOrder("a", "extra");
        assertThat(SourceTree.of(new Valued("a")).isString()).isTrue();
    }

    @Test
    void enumsAreAllowedUnlessReadByGetters() {
        assertThat(SourceTree.of(new Untyped(ValueEnum.A)).get("any").asString()).isEqualTo("text-a");
        assertThat(SourceTree.of(new Untyped(RenamedEnum.B)).get("any").asString()).isEqualTo("renamed-b");
        assertRefused(() -> SourceTree.of(new Untyped(ObjectEnum.A)), "ObjectEnum");
    }

    @Test
    void jdkClassesReadByGettersAreRefused() {
        assertRefused(() -> SourceTree.of(new Untyped(new IllegalStateException("personal data"))),
                "IllegalStateException");
        assertRefused(() -> SourceTree.of(new Untyped(java.awt.Color.RED)), "Color");
        assertRefused(() -> SourceModels.require(WithThrowable.class), "Throwable");
    }

    public record WithThrowable(Throwable t) {
    }

    @Test
    void aMapKeyMustHaveADefinedTextForm() {
        assertRefused(() -> SourceTree.of(new Untyped(Map.of(new Bean(), 1))), "Bean");
        assertRefused(() -> SourceTree.of(new Untyped(Map.of(new Key("k"), 1))), "Key");
        assertRefused(() -> SourceTree.of(new InMapKey(Map.of(new Key("k"), 1))), "Key");
        assertThat(SourceTree.of(new Untyped(Map.of(Tier.GOLD, 1))).get("any").propertyNames())
                .containsExactly("GOLD");
        assertThat(SourceTree.of(new Untyped(Map.of(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)))
                .get("any").propertyNames()).containsExactly("00000000-0000-0000-0000-000000000001");
    }

    public record InMapKey(Map<Object, Integer> m) {
    }

    @Test
    void aClassLevelSerializerAnnotationDoesNotLetAUserClassThrough() {
        assertRefused(() -> SourceTree.of(new Untyped(new AnnotatedBean())), "AnnotatedBean");
    }

    @Test
    void aUserSubclassOfACollectionOrMapIsRefused() {
        assertRefused(() -> SourceTree.of(new Untyped(new MyList())), "MyList");
        assertRefused(() -> SourceTree.of(new Untyped(new MyMap())), "MyMap");
        assertRefused(() -> SourceTree.of(new Untyped(List.of(new MyMap()))), "MyMap");
    }

    public record Attrs(Map<String, Object> attrs) {
    }

    public static final class NumKey extends Number {
        @Override public int intValue() { return 1; }
        @Override public long longValue() { return 1; }
        @Override public float floatValue() { return 1; }
        @Override public double doubleValue() { return 1; }
        @Override public String toString() {
            return "personal-data";
        }
    }

    public record NumKeyed(Map<Number, String> m, Map<Object, String> o) {
    }

    public record RenamedGetter(String name) {
        @JsonProperty("extra")
        public String getURL() {
            return "x";
        }
    }

    public record GetterAnnotated(String name) {
        @JsonGetter("extra")
        public String computed() {
            return "x";
        }
    }

    public record AccessorAnnotated(@JsonProperty("n") String name) {
        @Override
        @JsonProperty("n")
        public String name() {
            return name;
        }
    }

    public record WithPoint(java.awt.Point p) {
    }

    public record Collected(List<java.awt.Color> colours) {
    }

    @Test
    void anEnumMapReachedThroughAnObjectComponentIsAllowed() {
        Map<java.time.Month, Integer> months = new java.util.EnumMap<>(java.time.Month.class);
        months.put(java.time.Month.OCTOBER, 1);
        JsonNode tree = SourceTree.of(new Attrs(Map.of("m", months)));
        assertThat(tree.get("attrs").get("m").propertyNames()).containsExactly("OCTOBER");
    }

    @Test
    void aUserClassExtendingNumberIsNotAMapKey() {
        assertRefused(() -> SourceTree.of(new NumKeyed(Map.of(new NumKey(), "v"), Map.of())), "NumKey");
        assertRefused(() -> SourceTree.of(new NumKeyed(Map.of(), Map.of(new NumKey(), "v"))), "NumKey");
    }

    @Test
    void jsonPropertyOrJsonGetterOnAMethodThatIsNotAComponentIsRefused() {
        assertRefused(() -> SourceTree.of(new RenamedGetter("n")), "RenamedGetter");
        assertRefused(() -> SourceModels.require(RenamedGetter.class), "RenamedGetter");
        assertRefused(() -> SourceTree.of(new GetterAnnotated("n")), "GetterAnnotated");
        assertThat(SourceTree.of(new AccessorAnnotated("n")).propertyNames()).containsExactly("n");
        assertThat(SourceTree.of(new WithAny("a")).propertyNames()).containsExactlyInAnyOrder("a", "extra");
    }

    public interface AnnotatedUrled {
        @JsonProperty
        default String getURL() {
            return "x";
        }
    }

    public interface GetterUrled {
        @JsonGetter("url")
        default String computed() {
            return "x";
        }
    }

    public interface SuperUrled extends AnnotatedUrled {
    }

    public interface NamedAccessor {
        @JsonProperty("renamed")
        String name();
    }

    public record ViaInterfaceProperty(String name) implements AnnotatedUrled {
    }

    public record ViaInterfaceGetter(String name) implements GetterUrled {
    }

    public record ViaSuperInterface(String name) implements SuperUrled {
    }

    public record RenamedViaInterface(String name) implements NamedAccessor {
    }

    @Test
    void anAnnotatedMethodInheritedFromAnInterfaceIsRefusedAtStartupAndRuntime() {
        assertRefused(() -> SourceTree.of(new ViaInterfaceProperty("n")), "ViaInterfaceProperty");
        assertRefused(() -> SourceModels.require(ViaInterfaceProperty.class), "ViaInterfaceProperty");
        assertRefused(() -> SourceTree.of(new ViaInterfaceGetter("n")), "ViaInterfaceGetter");
        assertRefused(() -> SourceModels.require(ViaInterfaceGetter.class), "ViaInterfaceGetter");
        assertRefused(() -> SourceTree.of(new ViaSuperInterface("n")), "ViaSuperInterface");
        assertRefused(() -> SourceModels.require(ViaSuperInterface.class), "ViaSuperInterface");
    }

    @Test
    void aComponentAccessorRenamedOnTheInterfaceItImplementsKeepsTheName() {
        assertThat(SourceTree.of(new RenamedViaInterface("n")).propertyNames()).containsExactly("renamed");
        assertThatCode(() -> SourceModels.require(RenamedViaInterface.class)).doesNotThrowAnyException();
    }

    @Test
    void theAllowedExplicitChoicesStillPassAtStartup() {
        assertThatCode(() -> SourceModels.require(Annotated.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceModels.require(WithAny.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceModels.require(Valued.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceModels.require(WithExtraGetters.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceModels.require(WithInterfaceGetter.class)).doesNotThrowAnyException();
    }

    public record ComponentSerialized(@JsonSerialize(using = Custom.class) String secret, @JsonIgnore String hidden) {
    }

    @Test
    void aComponentSerializerAndAnIgnoredComponentStillPass() {
        assertThatCode(() -> SourceModels.require(ComponentSerialized.class)).doesNotThrowAnyException();
        JsonNode tree = SourceTree.of(new ComponentSerialized("s", "h"));
        assertThat(tree.propertyNames()).containsExactly("secret");
        assertThat(tree.get("secret").asString()).isEqualTo("custom");
    }

    @Test
    void aJdkClassReadByGettersIsRefusedAtStartup() {
        assertRefused(() -> SourceModels.require(WithPoint.class), "Point");
        assertRefused(() -> SourceModels.require(Collected.class), "Color");
        assertThatCode(() -> SourceModels.require(Allowed.class)).doesNotThrowAnyException();
    }

    public record Recursive<T extends Comparable<T>>(T t) {
    }

    public record ObjectEnumHolder(ObjectEnum e) {
    }

    @Test
    void aRecursiveTypeVariableBoundTerminatesAndAnObjectShapedEnumIsRefusedAtStartup() {
        assertThatCode(() -> SourceModels.require(Recursive.class)).doesNotThrowAnyException();
        assertRefused(() -> SourceModels.require(ObjectEnumHolder.class), "ObjectEnum");
    }

    @Test
    void anAnonymousClassIsNamedWithoutAPackage() {
        assertRefused(() -> SourceTree.of(new Untyped(new ArrayList<>() { })), "SourceModelsTest$");
    }

    @Test
    void aTypeVariableBoundIsWalkedAtStartup() {
        assertRefused(() -> SourceModels.require(Boxed.class));
    }

    public record Tree(String name, List<Tree> children) {
    }
}
