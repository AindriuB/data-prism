package io.github.aindriub.dataprism.audit.format;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRoutingTest {

    @Test
    void acceptsElasticDataStreamNames() {
        AuditRouting r = new AuditRouting("dataprism.audit", "logs", "dataprism.audit", "prod_eu");
        assertThat(r.eventDataset()).isEqualTo("dataprism.audit");
        assertThat(r.dataStreamNamespace()).isEqualTo("prod_eu");
        assertThat(AuditRouting.none().eventDataset()).isNull();
    }

    @Test
    void refusesValuesOutsideTheElasticRules() {
        String tooLong = "a".repeat(101);
        assertInvalid(new Object[] {"Upper", null, null, null});
        assertInvalid(new Object[] {"has-dash", null, null, null});
        assertInvalid(new Object[] {"", null, null, null});
        assertInvalid(new Object[] {tooLong, null, null, null});
        assertInvalid(new Object[] {null, "metrics", null, null});
        assertInvalid(new Object[] {null, null, "bad dataset", null});
        assertInvalid(new Object[] {null, null, null, "a.b"});
        assertInvalid(new Object[] {null, null, null, "Prod"});
        assertInvalid(new Object[] {null, null, null, tooLong});
        assertThat(new AuditRouting(null, null, "a".repeat(100), "b".repeat(100)).dataStreamDataset()).hasSize(100);
    }

    private static void assertInvalid(Object[] v) {
        assertThatThrownBy(() -> new AuditRouting((String) v[0], (String) v[1], (String) v[2], (String) v[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_AUDIT_ROUTING_VALUE");
    }

    @Test
    void routingPathCollidingWithAMappedPathIsRefused() {
        AuditRouting r = new AuditRouting("x", "logs", "x", "x");
        assertThatThrownBy(() -> r.checkAgainst(
                AuditFieldMapping.canonical().withOverrides(Map.of("tool", "event.dataset"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_FIELD_MAPPING_CONFLICT");
        assertThatThrownBy(() -> r.checkAgainst(
                AuditFieldMapping.canonical().withOverrides(Map.of("tool", "data_stream"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_FIELD_MAPPING_CONFLICT");
        // an unset routing value reserves no path
        AuditRouting.none().checkAgainst(AuditFieldMapping.canonical().withOverrides(Map.of("tool", "event.dataset")));
        r.checkAgainst(AuditFieldMapping.ecs());
    }
}
