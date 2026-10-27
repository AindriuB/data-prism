package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The corpus of task 270 (retired), run against the engine-owned walker (task 288). Each repro moved a sensitive
 * value out from under its own classification by changing what Jackson wrote. Under the DEFAULT profile the
 * walker writes each record by its components, so the value is protected by its own classification, or the
 * response is refused. All values are invented; the reserved domain is {@code example.invalid}.
 */
class SourceRecordReprosTest {

    private static final String EMAIL = "ada@example.invalid";

    @SensitiveObject
    record Email(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String value) {
        @JsonValue
        public String value() {
            return value;
        }
    }

    // ---- repro 1 -------------------------------------------------------------------------------

    @LlmExposedModel
    record Holder(@InternalIdentifier String ref, @NonSensitive(reason = "wrapper") Email email) {
    }

    // ---- repro 2 -------------------------------------------------------------------------------

    @SensitiveObject
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    record ArrContact(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @LlmExposedModel
    record ArrHolder(@InternalIdentifier String ref, @NonSensitive(reason = "x") ArrContact contact) {
    }

    @SensitiveObject
    record PContact(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @LlmExposedModel
    record PropArrHolder(@InternalIdentifier String ref,
                         @NonSensitive(reason = "x") @JsonFormat(shape = JsonFormat.Shape.ARRAY) PContact contact) {
    }

    // ---- repro 3 -------------------------------------------------------------------------------

    @SensitiveObject
    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "email")
    record IdContact(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @LlmExposedModel
    record IdHolder(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<IdContact> contacts) {
    }

    // ---- repro 4 -------------------------------------------------------------------------------

    @LlmExposedModel
    record OptEmail(@InternalIdentifier String ref, @NonSensitive(reason = "x") Optional<Email> v) {
    }

    @LlmExposedModel
    record OptArr(@InternalIdentifier String ref, @NonSensitive(reason = "x") Optional<ArrContact> v) {
    }

    @LlmExposedModel
    record ListEmail(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<Email> v) {
    }

    @LlmExposedModel
    record ArrayEmail(@InternalIdentifier String ref, @NonSensitive(reason = "x") Email[] v) {
    }

    // ---- repro 5 -------------------------------------------------------------------------------

    @SensitiveObject
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    record WrapArray(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @SensitiveObject
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    record WrapObject(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @LlmExposedModel
    record WrapArrayHolder(@InternalIdentifier String ref, @NonSensitive(reason = "x") WrapArray v) {
    }

    @LlmExposedModel
    record WrapObjectHolder(@InternalIdentifier String ref, @NonSensitive(reason = "x") WrapObject v) {
    }

    @SensitiveObject
    @tools.jackson.databind.annotation.JsonAppend(props = @tools.jackson.databind.annotation.JsonAppend.Prop(
            value = Appender.class, name = "email"))
    record Appended(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    public static class Appender extends tools.jackson.databind.ser.VirtualBeanPropertyWriter {
        public Appender() {
        }

        protected Appender(tools.jackson.databind.introspect.BeanPropertyDefinition propDef,
                           tools.jackson.databind.util.Annotations ctxtAnn, tools.jackson.databind.JavaType type) {
            super(propDef, ctxtAnn, type);
        }

        @Override
        protected Object value(Object bean, JsonGenerator g, SerializationContext prov) {
            return EMAIL;
        }

        @Override
        public tools.jackson.databind.ser.VirtualBeanPropertyWriter withConfig(
                tools.jackson.databind.cfg.MapperConfig<?> config,
                tools.jackson.databind.introspect.AnnotatedClass declaringClass,
                tools.jackson.databind.introspect.BeanPropertyDefinition propDef, tools.jackson.databind.JavaType type) {
            return new Appender(propDef, declaringClass.getAnnotations(), type);
        }
    }

    @LlmExposedModel
    record AppendedHolder(@InternalIdentifier String ref, @NonSensitive(reason = "x") Appended v) {
    }

    // ---- repro 6: untyped positions ------------------------------------------------------------

    interface Contactish {
    }

    @SensitiveObject
    record IfaceEmail(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String value) implements Contactish {
        @JsonValue
        public String value() {
            return value;
        }
    }

    @LlmExposedModel
    record AsObject(@InternalIdentifier String ref, @NonSensitive(reason = "x") Object v) {
    }

    @LlmExposedModel
    record AsInterface(@InternalIdentifier String ref, @NonSensitive(reason = "x") Contactish v) {
    }

    @LlmExposedModel
    record AsRecord(@InternalIdentifier String ref, @NonSensitive(reason = "x") Record v) {
    }

    @LlmExposedModel
    record AsWildList(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<?> v) {
    }

    @LlmExposedModel
    @SuppressWarnings("rawtypes")
    record AsRawList(@InternalIdentifier String ref, @NonSensitive(reason = "x") List v) {
    }

    @LlmExposedModel
    record AsOptionalObject(@InternalIdentifier String ref, @NonSensitive(reason = "x") Optional<Object> v) {
    }

    @LlmExposedModel
    record Typed<T>(@InternalIdentifier String ref, @NonSensitive(reason = "x") T payload) {
    }

    // ---- repro 7: transitive wrappers ----------------------------------------------------------

    @SensitiveObject
    record WrapperOfScalar(@NonSensitive(reason = "x") Email inner) {
        @JsonValue
        public String v() {
            return inner.value();
        }
    }

    @SensitiveObject
    record WrapperOfRecord(@NonSensitive(reason = "x") Email inner) {
        @JsonValue
        public Email inner() {
            return inner;
        }
    }

    @SensitiveObject
    record PlainInner(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @SensitiveObject
    record WrapperOfPlain(@NonSensitive(reason = "x") PlainInner inner) {
        @JsonValue
        public String v() {
            return inner.email();
        }
    }

    @LlmExposedModel
    record W1(@InternalIdentifier String ref, @NonSensitive(reason = "x") WrapperOfScalar w) {
    }

    @LlmExposedModel
    record W2(@InternalIdentifier String ref, @NonSensitive(reason = "x") WrapperOfRecord w) {
    }

    @LlmExposedModel
    record W3(@InternalIdentifier String ref, @NonSensitive(reason = "x") WrapperOfPlain w) {
    }

    // ---- repro 8: values swapped under the right names -----------------------------------------

    public static class Swap extends ValueSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            SwapRecord c = (SwapRecord) value;
            gen.writeStartObject();
            gen.writeStringProperty("email", c.ref());
            gen.writeStringProperty("ref", c.email());
            gen.writeEndObject();
        }
    }

    interface SwapRecord {
        String email();

        String ref();
    }

    @SensitiveObject
    @JsonSerialize(using = Swap.class)
    record TypeSwap(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email,
            @NonSensitive(reason = "x") String ref) implements SwapRecord {
    }

    @SensitiveObject
    record PlainSwap(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email,
            @NonSensitive(reason = "x") String ref) implements SwapRecord {
    }

    @SensitiveObject
    record NameSwap(
            @JsonProperty("ref") @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT) String email,
            @JsonProperty("email") @NonSensitive(reason = "x") String ref) {
    }

    @LlmExposedModel
    record SwapA(@InternalIdentifier String id, @NonSensitive(reason = "x") TypeSwap contact) {
    }

    @LlmExposedModel
    record SwapB(@InternalIdentifier String id,
                 @NonSensitive(reason = "x") @JsonSerialize(using = Swap.class) PlainSwap contact) {
    }

    @LlmExposedModel
    record SwapC(@InternalIdentifier String id,
                 @NonSensitive(reason = "x") @JsonSerialize(contentUsing = Swap.class) List<PlainSwap> contacts) {
    }

    @LlmExposedModel
    record SwapD(@InternalIdentifier String id, @NonSensitive(reason = "x") NameSwap contact) {
    }

    @LlmExposedModel
    @JsonSerialize(using = RootSwap.class)
    record SwapE(@InternalIdentifier String id,
                 @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                         suggestedAction = PrivacyAction.REDACT) String email,
                 @NonSensitive(reason = "x") String ref) {
    }

    public static class RootSwap extends ValueSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            SwapE c = (SwapE) value;
            gen.writeStartObject();
            gen.writeStringProperty("id", c.id());
            gen.writeStringProperty("email", c.ref());
            gen.writeStringProperty("ref", c.email());
            gen.writeEndObject();
        }
    }

    @LlmExposedModel
    record SwapF(@InternalIdentifier String id,
                 @JsonProperty("ref") @SensitiveData(classifications = DataClassification.CONTACT,
                         namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT) String email,
                 @JsonProperty("email") @NonSensitive(reason = "x") String ref) {
    }

    // ---- the explicit-accessor swap ------------------------------------------------------------

    @LlmExposedModel
    record Accessor(@InternalIdentifier String id,
                    @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                            suggestedAction = PrivacyAction.REDACT) String email,
                    @NonSensitive(reason = "x") String ref) {
        public String ref() {
            return email;
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine defaultEngine() {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                    new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(in)),
                    (subjectId, namespace, ctx) -> "synthetic:" + Integer.toHexString(subjectId.hashCode()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The scrubbed tree as text; the email must not be in it, and the value's own classification must have run. */
    private static String scrubbed(Object source) {
        String out = defaultEngine().scrub(source, context()).tree().toString();
        assertThat(out).doesNotContain(EMAIL);
        return out;
    }

    private static void assertRefusedAs(Object source, String code) {
        assertThatThrownBy(() -> defaultEngine().scrub(source, context()))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.getMessage()).doesNotContain(EMAIL);
                });
        assertThat(SourceValues.prohibited(source, new DefaultFieldMetadataResolver()))
                .as("the lenient walk never throws and collects the email").contains(EMAIL);
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("repro 1: an @JsonValue on a classified record no longer collapses it to a scalar")
    void jsonValueRecord() {
        assertThat(scrubbed(new Holder("r", new Email(EMAIL)))).contains("[REDACTED]");
    }

    @Test
    @DisplayName("repro 2: an array shape, on the class or on the holding property, has no effect")
    void arrayShapes() {
        assertThat(scrubbed(new ArrHolder("r", new ArrContact(EMAIL)))).contains("[REDACTED]");
        assertThat(scrubbed(new PropArrHolder("r", new PContact(EMAIL)))).contains("[REDACTED]");
    }

    @Test
    @DisplayName("repro 3: an object id on the second occurrence has no effect")
    void objectIds() {
        IdContact contact = new IdContact(EMAIL);
        String out = scrubbed(new IdHolder("r", List.of(contact, contact)));
        assertThat(out).isEqualTo("{\"contacts\":[{\"email\":\"[REDACTED]\"},{\"email\":\"[REDACTED]\"}]}");
    }

    @Test
    @DisplayName("repro 4: Optional, List and array variants")
    void containerVariants() {
        assertThat(scrubbed(new OptEmail("r", Optional.of(new Email(EMAIL))))).contains("[REDACTED]");
        assertThat(scrubbed(new OptArr("r", Optional.of(new ArrContact(EMAIL))))).contains("[REDACTED]");
        assertThat(scrubbed(new ListEmail("r", List.of(new Email(EMAIL))))).contains("[REDACTED]");
        assertThat(scrubbed(new ArrayEmail("r", new Email[]{new Email(EMAIL)}))).contains("[REDACTED]");
    }

    @Test
    @DisplayName("repro 5: type-info wrappers and a virtual property have no effect")
    void typeInfoWrappersAndVirtualProperties() {
        assertThat(scrubbed(new WrapArrayHolder("r", new WrapArray(EMAIL)))).contains("[REDACTED]");
        assertThat(scrubbed(new WrapObjectHolder("r", new WrapObject(EMAIL)))).contains("[REDACTED]");
        assertThat(scrubbed(new AppendedHolder("r", new Appended(EMAIL)))).contains("[REDACTED]");
    }

    @Test
    @DisplayName("repro 6: a record at a position that does not declare it is refused")
    void untypedPositions() {
        String code = "SOURCE_MODEL_RECORD_NOT_DECLARED";
        assertRefusedAs(new AsObject("r", new Email(EMAIL)), code);
        assertRefusedAs(new AsInterface("r", new IfaceEmail(EMAIL)), code);
        assertRefusedAs(new AsRecord("r", new Email(EMAIL)), code);
        assertRefusedAs(new AsWildList("r", List.of(new Email(EMAIL))), code);
        assertRefusedAs(new AsRawList("r", List.of(new Email(EMAIL))), code);
        assertRefusedAs(new AsOptionalObject("r", Optional.of(new Email(EMAIL))), code);
        // A type variable: a generic record is refused as generic first.
        assertRefusedAs(new Typed<>("r", new Email(EMAIL)), "SOURCE_MODEL_GENERIC");
    }

    @Test
    @DisplayName("repro 7: a wrapper's @JsonValue has no effect, so the inner record is classified as itself")
    void transitiveWrappers() {
        assertThat(scrubbed(new W1("r", new WrapperOfScalar(new Email(EMAIL))))).doesNotContain(EMAIL);
        assertThat(scrubbed(new W2("r", new WrapperOfRecord(new Email(EMAIL))))).doesNotContain(EMAIL);
        assertThat(scrubbed(new W3("r", new WrapperOfPlain(new PlainInner(EMAIL))))).contains("[REDACTED]");
    }

    @Test
    @DisplayName("repro 8: a swapping serializer or a @JsonProperty name swap has no effect")
    void swappedValues() {
        assertThat(scrubbed(new SwapA("r", new TypeSwap(EMAIL, "plain")))).contains("\"ref\":\"plain\"");
        assertThat(scrubbed(new SwapB("r", new PlainSwap(EMAIL, "plain")))).contains("\"ref\":\"plain\"");
        assertThat(scrubbed(new SwapC("r", List.of(new PlainSwap(EMAIL, "plain"))))).contains("\"ref\":\"plain\"");
        assertThat(scrubbed(new SwapD("r", new NameSwap(EMAIL, "plain")))).contains("\"ref\":\"plain\"");
        assertThat(scrubbed(new SwapE("r", EMAIL, "plain"))).contains("\"ref\":\"plain\"");
        assertThat(scrubbed(new SwapF("r", EMAIL, "plain"))).contains("\"ref\":\"plain\"");
    }

    @Test
    @DisplayName("an explicit accessor that returns another component's value is not used")
    void accessorsAreNotUsed() {
        String out = scrubbed(new Accessor("r", EMAIL, "plain"));
        assertThat(out).contains("\"ref\":\"plain\"").contains("\"email\":\"[REDACTED]\"");
    }

    // ---- leaves (review P1) --------------------------------------------------------------------

    public static class JsonValued {
        @JsonValue
        public String v() {
            return EMAIL;
        }
    }

    @LlmExposedModel
    record HolderOfReference(@InternalIdentifier String ref,
                             @NonSensitive(reason = "x") AtomicReference<Object> payload) {
    }

    @LlmExposedModel
    record HolderOfString(@InternalIdentifier String ref, @NonSensitive(reason = "x") AtomicReference<String> p) {
    }

    @LlmExposedModel
    record HolderOfWeak(@InternalIdentifier String ref, @NonSensitive(reason = "x") WeakReference<String> p) {
    }

    @LlmExposedModel
    record HolderOfLocal(@InternalIdentifier String ref, @NonSensitive(reason = "x") ThreadLocal<String> p) {
    }

    @LlmExposedModel
    record HolderOfCounter(@InternalIdentifier String ref, @NonSensitive(reason = "x") AtomicInteger n) {
    }

    private static void assertNotARecord(Object source, Class<?> model) {
        assertThatThrownBy(() -> SourceModels.require(model))
                .isInstanceOfSatisfying(PrivacyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD"));
        assertThatThrownBy(() -> SourceTree.of(source))
                .isInstanceOfSatisfying(PrivacyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD"));
    }

    @Test
    @DisplayName("an AtomicReference holding a @JsonValue class, or a record, is refused at startup and at scrub time")
    void atomicReferenceIsRefused() {
        assertNotARecord(new HolderOfReference("r", new AtomicReference<>(new JsonValued())),
                HolderOfReference.class);
        assertNotARecord(new HolderOfReference("r", new AtomicReference<>(new Email(EMAIL))),
                HolderOfReference.class);
        assertNotARecord(new HolderOfString("r", new AtomicReference<>("x")), HolderOfString.class);
        assertNotARecord(new HolderOfWeak("r", new WeakReference<>("x")), HolderOfWeak.class);
        assertNotARecord(new HolderOfLocal("r", new ThreadLocal<>()), HolderOfLocal.class);
    }

    @Test
    @DisplayName("an AtomicInteger holds no object and is its number")
    void atomicIntegerIsAScalar() {
        assertThat(SourceTree.of(new HolderOfCounter("r", new AtomicInteger(7))).get("n").intValue()).isEqualTo(7);
        assertThatCodeStarts(HolderOfCounter.class);
    }

    private static void assertThatCodeStarts(Class<?> model) {
        SourceModels.require(model);
    }

    @Test
    @DisplayName("the review's AtomicReference holder puts the email in the prohibited set")
    void referenceContentIsProhibited() {
        assertThat(SourceValues.prohibited(new HolderOfReference("r", new AtomicReference<>(new Email(EMAIL))),
                new DefaultFieldMetadataResolver())).contains(EMAIL);
    }

    @Test
    @DisplayName("sets of immutable records are ordinary collections")
    void aSetOfRecordsIsDescended() {
        record Box(Set<PContact> contacts) {
        }
        assertThat(SourceTree.of(new Box(Set.of(new PContact(EMAIL)))).get("contacts").get(0).get("email").asString())
                .isEqualTo(EMAIL);
    }
}
