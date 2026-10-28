package io.github.aindriub.dataprism.validation;

import io.github.aindriub.dataprism.annotations.DataClassification;

import java.util.Set;

/**
 * An operator-supplied check on one string leaf of a response, for example a call to an
 * entity-recognition service.
 *
 * <p>A recogniser can only add refusals: a non-empty result refuses the response, and nothing it
 * returns can relax another validator's refusal. It is deliberately given only the value, never
 * the privacy context, the prohibited or emitted sets, or the JSON path. A recogniser that throws
 * or returns {@code null} also refuses the response.
 */
@FunctionalInterface
public interface FreeTextRecogniser {

    /**
     * @param value one string leaf of the response
     * @return the classifications recognised in it; empty for none, never {@code null}
     */
    Set<DataClassification> recognise(String value);
}
