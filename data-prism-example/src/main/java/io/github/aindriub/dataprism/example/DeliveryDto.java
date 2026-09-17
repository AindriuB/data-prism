package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.SensitiveObject;

import java.util.Collection;

/**
 * A block nested inside {@link OrderDto}: the courier handling one order, and
 * whatever free-text notes were left about the delivery. Exists so
 * {@code PiiLogScanTest}'s banned-value derivation has a real record and a
 * real collection to descend into — see that class's {@code deriveBannedValues}.
 *
 * <p>{@code @SensitiveObject} carries no {@code classifications()}: both of
 * this type's own components already declare their own annotation, exactly
 * {@code NestedScrubbingTest}'s {@code Reviewed} record does. The annotation
 * is present purely to satisfy its own contract — "the engine has no way to
 * tell a reviewed sub-structure from an arbitrary blob" without it — not to
 * apply a blanket classification.
 */
@SensitiveObject
public record DeliveryDto(

        @NonSensitive(reason = "Opaque courier reference, meaningless outside the courier's own system")
        String courierRef,

        @NonSensitive(reason = "Free-text delivery notes; carried as ordinary free text, not identifying")
        Collection<String> notes) {
}
