package io.github.aindriub.dataprism.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Where one record came from.
 *
 * <p>Held on the trusted side and never emitted whole. {@code sourceRecordId} in
 * particular is a key into the source system: handing it to a model gives it
 * something to quote back at anyone with access to that system, which is the
 * lookup the pseudonymisation exists to prevent. The specification is explicit
 * that source identifiers are not exposed without permission (docs/pack.md §32),
 * and there is currently no permission that grants it.
 *
 * <p>{@code sourceSystem} is aliased per scope before it reaches a response, so a
 * model learns that two systems disagree without learning the shape of the
 * estate behind them.
 */
public record SourceProvenance(
        String sourceSystem,
        String sourceType,
        String sourceRecordId,
        Instant retrievedAt,
        String version) {

    public SourceProvenance {
        Objects.requireNonNull(sourceSystem, "sourceSystem");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }

    public static SourceProvenance of(String sourceSystem, Instant retrievedAt) {
        return new SourceProvenance(sourceSystem, "REST", null, retrievedAt, null);
    }
}
