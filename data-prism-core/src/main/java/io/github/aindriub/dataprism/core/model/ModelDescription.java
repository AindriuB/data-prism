package io.github.aindriub.dataprism.core.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The output shape of one model and how each field is protected, computed from the scrub plan and
 * carrying no data.
 *
 * @param fields     in ascending {@code path} order
 * @param undeclared what happens to a property the model does not declare
 */
public record ModelDescription(List<FieldProtection> fields, Undeclared undeclared) {

    /** What the profile does to a property the model does not declare. */
    public enum Undeclared {
        /** The whole response is refused. */
        REFUSE,
        /** The property is kept under a placeholder name. */
        RENAME,
        /** The property is dropped. */
        DROP
    }

    private static final Comparator<FieldProtection> BY_PATH = Comparator.comparing(FieldProtection::path);

    public ModelDescription {
        Objects.requireNonNull(undeclared, "undeclared");
        fields = fields.stream().sorted(BY_PATH).toList();
    }
}
