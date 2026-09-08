package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * Extracts the raw values a response must not contain.
 *
 * <p>Everything classified sensitive, plus the correlation identifier, read
 * straight off the source. The validator compares against these, which is what
 * makes it independent of the engine: it checks what was actually in the input
 * rather than trusting the transformation to report on itself.
 */
public final class SourceValues {

    private static final ObjectMapper READER = new ObjectMapper();

    private SourceValues() {
    }

    public static Set<String> prohibited(Object source, FieldMetadataResolver resolver) {
        Set<String> out = new HashSet<>();
        JsonNode tree = READER.valueToTree(source);
        for (FieldMetadata md : resolver.resolve(source.getClass())) {
            if (!md.sensitive() && !md.internalIdentifier()) {
                continue;
            }
            JsonNode value = tree.get(md.fieldName());
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                out.add(value.asText());
            }
        }
        return out;
    }
}
