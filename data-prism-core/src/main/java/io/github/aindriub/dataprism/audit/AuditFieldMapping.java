package io.github.aindriub.dataprism.audit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stub: red phase. */
public final class AuditFieldMapping {

    public static final List<String> CANONICAL_FIELDS = List.of();

    public static AuditFieldMapping canonical() {
        throw new UnsupportedOperationException();
    }

    public static AuditFieldMapping ecs() {
        throw new UnsupportedOperationException();
    }

    public AuditFieldMapping withOverrides(Map<String, String> overrides) {
        throw new UnsupportedOperationException();
    }

    public Map<String, String> paths() {
        throw new UnsupportedOperationException();
    }

    public String pathOf(String field) {
        throw new UnsupportedOperationException();
    }

    public Optional<String> outcomePath() {
        throw new UnsupportedOperationException();
    }
}
