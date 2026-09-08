package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves metadata from record components.
 *
 * <p>S0 handles records only, which is what the example models are. Classes with
 * fields and accessors are S1; this throws rather than returning nothing for
 * them, because returning nothing would look identical to a record whose author
 * annotated nothing, and that case must stay loud.
 */
public final class RecordFieldMetadataResolver implements FieldMetadataResolver {

    /**
     * ClassValue rather than a Map: it has the right classloader semantics, so an
     * undeployed application's classes can still be collected.
     */
    private final ClassValue<List<FieldMetadata>> cache = new ClassValue<>() {
        @Override
        protected List<FieldMetadata> computeValue(Class<?> type) {
            return read(type);
        }
    };

    @Override
    public List<FieldMetadata> resolve(Class<?> type) {
        return cache.get(type);
    }

    private static List<FieldMetadata> read(Class<?> type) {
        if (!type.isRecord()) {
            throw new UnsupportedOperationException(
                    "S0 resolves record components only; " + type.getName()
                            + " is not a record. Class and accessor support is S1.");
        }
        List<FieldMetadata> out = new ArrayList<>();
        for (RecordComponent rc : type.getRecordComponents()) {
            SensitiveData sensitive = rc.getAnnotation(SensitiveData.class);
            NonSensitive nonSensitive = rc.getAnnotation(NonSensitive.class);
            boolean identifier = rc.getAnnotation(InternalIdentifier.class) != null;

            if (sensitive != null && nonSensitive != null) {
                throw new IllegalStateException(
                        type.getName() + "." + rc.getName()
                                + " is annotated both @SensitiveData and @NonSensitive");
            }

            out.add(new FieldMetadata(
                    rc.getName(),
                    identifier,
                    sensitive == null ? List.of() : List.of(sensitive.classifications()),
                    sensitive == null ? PrivacyNamespace.NONE : sensitive.namespace(),
                    sensitive == null ? null : sensitive.suggestedAction(),
                    sensitive == null ? "" : sensitive.subject(),
                    nonSensitive == null ? null : nonSensitive.reason()));
        }
        return List.copyOf(out);
    }
}
