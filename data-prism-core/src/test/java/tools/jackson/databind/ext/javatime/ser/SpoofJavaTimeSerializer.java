package tools.jackson.databind.ext.javatime.ser;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** A user class in Jackson's java.time serializer package; it writes an object from an enum constant. */
public final class SpoofJavaTimeSerializer extends ValueSerializer<Enum<?>> {
    @Override
    public void serialize(Enum<?> value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeStartObject();
        gen.writeStringProperty("inner", value.name());
        gen.writeEndObject();
    }
}
