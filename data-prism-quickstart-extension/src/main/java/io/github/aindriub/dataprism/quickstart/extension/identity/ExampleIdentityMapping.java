package io.github.aindriub.dataprism.quickstart.extension.identity;

import java.util.Map;

/**
 * The fixed table {@link MappedIdentityResolver} in this package is built
 * from — kept in its own class purely so the tutorial and the unit test can
 * both cite exactly the same rows, rather than one copying the other by
 * hand.
 *
 * <p>Two subjects: {@code cust-001} is known to all three sources; {@code
 * cust-002} has never been billed, so it is absent from {@code billing}
 * entirely — the case {@link MappedIdentityResolver#expand} must omit rather
 * than invent a key for.
 */
// --8<-- [start:sample-data]
final class ExampleIdentityMapping {

    private ExampleIdentityMapping() {
    }

    static Map<String, Map<String, String>> keysByCanonicalId() {
        return Map.of(
                "cust-001", Map.of(
                        "customer", "C-1001",
                        "billing", "B-77",
                        "crm", "CRM-42"),
                "cust-002", Map.of(
                        "customer", "C-1002",
                        "crm", "CRM-43"));
    }
}
// --8<-- [end:sample-data]
