package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonAppend;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup refuses a Jackson annotation on a source model with {@code SOURCE_MODEL_JACKSON_ANNOTATION}, except
 * the closed allowlist of deserialisation-only ones. Every fixture uses invented values only.
 */
class SourceModelAnnotationRefusalTest {

    private static final String CODE = "SOURCE_MODEL_JACKSON_ANNOTATION";
    private static final String TAIL = " has no effect on a source model; remove it";

    public static class Swap extends ValueSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString("swapped");
        }
    }

    public static class Appender extends tools.jackson.databind.ser.VirtualBeanPropertyWriter {
        public Appender() {
        }

        @Override
        protected Object value(Object bean, JsonGenerator g, SerializationContext prov) {
            return "appended";
        }
    }

    private static void assertRefusedWith(Class<?> model, String where, String annotation) {
        assertThatThrownBy(() -> SourceModels.require(model)).as(model.getSimpleName())
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo(CODE);
                    assertThat(e.path()).isEqualTo("$");
                    assertThat(e.getMessage()).isEqualTo(where + ": @" + annotation + TAIL);
                });
    }

    private static void assertRefusedContaining(Class<?> model, String annotation) {
        assertThatThrownBy(() -> SourceModels.require(model)).as(model.getSimpleName())
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo(CODE);
                    assertThat(e.getMessage()).contains("@" + annotation + TAIL)
                            .doesNotContain("ada@example.invalid", "io.github");
                });
    }

    @Test
    void theCodeIsAPublicConstantAndAValidToken() {
        assertThat(SourceModels.JACKSON_ANNOTATION_CODE).isEqualTo(CODE);
        assertThat(io.github.aindriub.dataprism.core.refusal.RefusalCodes.sanitise(CODE)).isEqualTo(CODE);
    }

    // ---- the owner's list, on the record and on a component ------------------------------------

    @JsonSerialize(using = Swap.class)
    public record ClassSerialized(String a) {
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record ClassFormatted(String a) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClassNamed(String firstName) {
    }

    public record ComponentSerialized(@JsonSerialize(using = Swap.class) String a) {
    }

    public record ComponentFormatted(@JsonFormat(shape = JsonFormat.Shape.STRING) String a) {
    }

    public record ComponentRenamed(@JsonProperty("other") String a) {
    }

    public record ComponentIgnored(@JsonIgnore String a) {
    }

    public record Plain(String a) {
    }

    public record ComponentUnwrapped(@JsonUnwrapped Plain inner) {
    }

    public record AnyGetter(String a) {
        @JsonAnyGetter
        public Map<String, Object> extra() {
            return Map.of("k", "v");
        }
    }

    public record AnyGetterOnComponent(@JsonAnyGetter Map<String, Object> extra) {
    }

    public record Valued(String a) {
        @JsonValue
        public String as() {
            return "as-value";
        }
    }

    @Test
    void eachAnnotationInTheOwnersListIsRefusedOnTheRecordAndOnAComponent() {
        assertRefusedWith(ClassSerialized.class, "ClassSerialized", "JsonSerialize");
        assertRefusedWith(ClassFormatted.class, "ClassFormatted", "JsonFormat");
        assertRefusedWith(ClassNamed.class, "ClassNamed", "JsonNaming");
        assertRefusedWith(ComponentSerialized.class, "ComponentSerialized.a", "JsonSerialize");
        assertRefusedWith(ComponentFormatted.class, "ComponentFormatted.a", "JsonFormat");
        assertRefusedWith(ComponentRenamed.class, "ComponentRenamed.a", "JsonProperty");
        assertRefusedWith(ComponentIgnored.class, "ComponentIgnored.a", "JsonIgnore");
        assertRefusedWith(ComponentUnwrapped.class, "ComponentUnwrapped.inner", "JsonUnwrapped");
        assertRefusedWith(AnyGetter.class, "AnyGetter.extra", "JsonAnyGetter");
        assertRefusedWith(AnyGetterOnComponent.class, "AnyGetterOnComponent.extra", "JsonAnyGetter");
        assertRefusedWith(Valued.class, "Valued.as", "JsonValue");
    }

    // ---- 270's repro models 1 to 5, 7 and 8 ---------------------------------------------------

    public record Email(String value) {
        @JsonValue
        public String value() {
            return value;
        }
    }

    public record Holder(String ref, Email email) {
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record ArrContact(String email) {
    }

    public record HoldsArr(ArrContact contact) {
    }

    public record HoldsComponentArray(@JsonFormat(shape = JsonFormat.Shape.ARRAY) Plain contact) {
    }

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "email")
    public record IdContact(String email) {
    }

    public record HoldsId(List<IdContact> contacts) {
    }

    public record OptionalEmail(Optional<Email> email) {
    }

    public record OptionalArr(Optional<ArrContact> contact) {
    }

    public record ListEmail(List<Email> emails) {
    }

    public record ArrayEmail(Email[] emails) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    public record WrapperArray(String email) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    public record WrapperObject(String email) {
    }

    public record HoldsWrapperArray(WrapperArray w) {
    }

    public record HoldsWrapperObject(WrapperObject w) {
    }

    @JsonAppend(props = @JsonAppend.Prop(value = Appender.class, name = "virtual"))
    public record Appended(String email) {
    }

    public record HoldsAppended(Appended a) {
    }

    public record Wrapper(Email inner) {
        @JsonValue
        public String v() {
            return inner.value();
        }
    }

    public record HoldsWrapper(Wrapper w) {
    }

    public record InnerPlain(String email) {
    }

    public record WrapperOfPlain(InnerPlain inner) {
        @JsonValue
        public String v() {
            return inner.email();
        }
    }

    public record SwapA(String email, String ref) {
    }

    public record SwapB(@JsonSerialize(using = Swap.class) SwapA contact) {
    }

    public record SwapC(@JsonSerialize(contentUsing = Swap.class) List<SwapA> contacts) {
    }

    public record SwapD(@JsonProperty("ref") String email, @JsonProperty("email") String ref) {
    }

    @JsonSerialize(using = Swap.class)
    public record SwapE(String email, String ref) {
    }

    public record SwapF(@JsonProperty("ref") String email, @JsonProperty("email") String ref) {
    }

    @JsonSerialize(using = Swap.class)
    public record SwapTypeLevel(String email, String ref) {
    }

    public record SwapAHolder(SwapTypeLevel contact) {
    }

    @Test
    void eachOf270sReproModelsIsRefused() {
        assertRefusedContaining(Holder.class, "JsonValue");
        assertRefusedContaining(HoldsArr.class, "JsonFormat");
        assertRefusedContaining(HoldsComponentArray.class, "JsonFormat");
        assertRefusedContaining(HoldsId.class, "JsonIdentityInfo");
        assertRefusedContaining(OptionalEmail.class, "JsonValue");
        assertRefusedContaining(OptionalArr.class, "JsonFormat");
        assertRefusedContaining(ListEmail.class, "JsonValue");
        assertRefusedContaining(ArrayEmail.class, "JsonValue");
        assertRefusedContaining(HoldsWrapperArray.class, "JsonTypeInfo");
        assertRefusedContaining(HoldsWrapperObject.class, "JsonTypeInfo");
        assertRefusedContaining(HoldsAppended.class, "JsonAppend");
        assertRefusedContaining(HoldsWrapper.class, "JsonValue");
        assertRefusedContaining(WrapperOfPlain.class, "JsonValue");
        assertRefusedContaining(SwapAHolder.class, "JsonSerialize");
        assertRefusedContaining(SwapB.class, "JsonSerialize");
        assertRefusedContaining(SwapC.class, "JsonSerialize");
        assertRefusedContaining(SwapD.class, "JsonProperty");
        assertRefusedContaining(SwapE.class, "JsonSerialize");
        assertRefusedContaining(SwapF.class, "JsonProperty");
    }

    // ---- other places an annotation can sit ---------------------------------------------------

    public interface AnnotatedInterface {
        @JsonProperty("renamed")
        default String getURL() {
            return "x";
        }
    }

    public interface SuperInterface extends AnnotatedInterface {
    }

    public record ViaInterface(String a) implements AnnotatedInterface {
    }

    public record ViaSuperInterface(String a) implements SuperInterface {
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public interface AnnotatedType {
    }

    public record ViaInterfaceType(String a) implements AnnotatedType {
    }

    public record RenamedGetter(String a) {
        @JsonProperty("extra")
        public String getURL() {
            return "x";
        }
    }

    public record GetterAnnotated(String a) {
        @JsonGetter("extra")
        public String computed() {
            return "x";
        }
    }

    public enum ConstantAnnotated {
        @JsonProperty("renamed") A
    }

    public record HoldsConstantAnnotated(ConstantAnnotated e) {
    }

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    public enum ObjectEnum {
        A;

        public String getURL() {
            return "x";
        }
    }

    public record HoldsObjectEnum(List<ObjectEnum> e) {
    }

    public enum ValueEnum {
        A;

        @JsonValue
        public String text() {
            return "text-a";
        }
    }

    public record HoldsValueEnum(java.util.Map<String, ValueEnum> m) {
    }

    @JacksonAnnotationsInside
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Bundle {
    }

    @Bundle
    public record Bundled(String a) {
    }

    @Test
    void anAnnotationOnAnImplementedInterfaceAnEnumConstantOrAnotherMethodIsRefused() {
        assertRefusedWith(ViaInterface.class, "AnnotatedInterface.getURL", "JsonProperty");
        assertRefusedWith(ViaSuperInterface.class, "AnnotatedInterface.getURL", "JsonProperty");
        assertRefusedWith(ViaInterfaceType.class, "AnnotatedType", "JsonFormat");
        assertRefusedWith(RenamedGetter.class, "RenamedGetter.getURL", "JsonProperty");
        assertRefusedWith(GetterAnnotated.class, "GetterAnnotated.computed", "JsonGetter");
        assertRefusedWith(HoldsConstantAnnotated.class, "ConstantAnnotated.A", "JsonProperty");
        assertRefusedContaining(HoldsObjectEnum.class, "JsonFormat");
        assertRefusedContaining(HoldsValueEnum.class, "JsonValue");
    }

    @Test
    void aCustomAnnotationMetaAnnotatedJacksonAnnotationsInsideIsRefusedEvenWhenItBundlesAllowedOnes() {
        assertRefusedWith(Bundled.class, "Bundled", "Bundle");
    }

    @Test
    void aRefusalNamesTheAnnotationAndNeverAValue() {
        assertThatThrownBy(() -> SourceModels.require(ComponentRenamed.class))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e ->
                        assertThat(e.getMessage()).doesNotContain("other"));
    }

    // ---- the allowlist -------------------------------------------------------------------------

    public record AliasOk(@com.fasterxml.jackson.annotation.JsonAlias({"old_name"}) String name, String ref) {
    }

    public record CreatorOk(String name, String ref) {
        @JsonCreator
        public CreatorOk {
        }
    }

    public record DeserializeOk(@JsonDeserialize String name, String ref) {
    }

    public record SetterOk(@JsonSetter("other") String name, String ref) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IgnoreUnknownOk(String name, String ref) {
    }

    public record PlainTwo(String name, String ref) {
    }

    @JsonIgnoreProperties({"secret"})
    public record IgnoresNamed(String name, String ref) {
    }

    @JsonIgnoreProperties(value = {"secret"}, ignoreUnknown = true)
    public record IgnoresNamedAndUnknown(String name, String ref) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record IgnoresNothing(String name, String ref) {
    }

    @JsonPropertyOrder({"ref", "name"})
    public record Ordered(String name, String ref) {
    }

    public record Raw(@JsonRawValue String name, String ref) {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @JacksonAnnotationsInside
    public @interface JsonAlias {
    }

    @JsonAlias
    public record FakeAlias(String name, String ref) {
    }

    @Test
    void theAllowlistIsPinned() {
        assertThat(SourceModels.ALLOWED_JACKSON_ANNOTATIONS).containsExactlyInAnyOrder(
                com.fasterxml.jackson.annotation.JsonAlias.class,
                JsonCreator.class,
                JsonDeserialize.class,
                JsonSetter.class,
                JsonIgnoreProperties.class);

        PlainTwo plain = new PlainTwo("n", "r");
        String expected = SourceTree.of(plain).toString();
        for (Object annotated : new Object[] {new AliasOk("n", "r"), new CreatorOk("n", "r"),
                new DeserializeOk("n", "r"), new SetterOk("n", "r"), new IgnoreUnknownOk("n", "r")}) {
            Class<?> type = annotated.getClass();
            assertThatCode(() -> SourceModels.require(type)).as(type.getSimpleName()).doesNotThrowAnyException();
            assertThat(SourceTree.of(annotated).toString()).as(type.getSimpleName()).isEqualTo(expected);
        }

        assertRefusedWith(IgnoresNamed.class, "IgnoresNamed", "JsonIgnoreProperties");
        assertRefusedWith(IgnoresNamedAndUnknown.class, "IgnoresNamedAndUnknown", "JsonIgnoreProperties");
        assertRefusedWith(IgnoresNothing.class, "IgnoresNothing", "JsonIgnoreProperties");
        assertRefusedWith(Ordered.class, "Ordered", "JsonPropertyOrder");
        assertRefusedWith(Raw.class, "Raw.name", "JsonRawValue");
        // Matched by annotation type, not simple name.
        assertRefusedWith(FakeAlias.class, "FakeAlias", "JsonAlias");
    }

    @Test
    void aModelWithoutJacksonAnnotationsStillPasses() {
        assertThatCode(() -> SourceModels.require(Plain.class)).doesNotThrowAnyException();
        assertThatCode(() -> SourceModels.require(PlainTwo.class)).doesNotThrowAnyException();
    }
}
