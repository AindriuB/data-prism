package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditFieldMappingTest {

    private static List<String> componentNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent c : AuditEvent.class.getRecordComponents()) {
            names.add(c.getName());
        }
        return names;
    }

    @Test
    void canonicalFieldsAreTheAuditEventComponentNames() {
        assertThat(AuditFieldMapping.CANONICAL_FIELDS).containsExactlyElementsOf(componentNames());
    }

    @Test
    void canonicalPresetIsTheIdentityAndTotal() {
        AuditFieldMapping m = AuditFieldMapping.canonical();
        assertThat(m.paths().keySet()).containsExactlyElementsOf(componentNames());
        m.paths().forEach((field, path) -> assertThat(path).isEqualTo(field));
        assertThat(m.outcomePath()).isEmpty();
    }

    @Test
    void ecsPresetIsPinnedInFull() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("eventId", "event.id");
        expected.put("timestamp", "@timestamp");
        expected.put("principalId", "user.id");
        expected.put("clientId", "dataprism.client_id");
        expected.put("tool", "event.action");
        expected.put("entityType", "dataprism.entity_type");
        expected.put("subjectPseudonym", "dataprism.subject_pseudonym");
        expected.put("parameterFingerprint", "dataprism.parameter_fingerprint");
        expected.put("privacyProfile", "dataprism.privacy_profile");
        expected.put("scopeId", "dataprism.scope_id");
        expected.put("purpose", "dataprism.purpose");
        expected.put("caseId", "dataprism.case_id");
        expected.put("policyDecision", "dataprism.policy_decision");
        expected.put("sourceSystems", "dataprism.source_systems");
        expected.put("rejectedArguments", "dataprism.rejected_arguments");
        expected.put("correlationId", "dataprism.correlation_id");
        expected.put("instanceId", "dataprism.instance_id");
        expected.put("sequence", "dataprism.sequence");
        expected.put("previousHash", "dataprism.previous_hash");
        expected.put("eventHash", "dataprism.event_hash");
        expected.put("recordVersion", "dataprism.record_version");
        expected.put("fieldDispositions", "dataprism.field_dispositions");
        expected.put("approvalId", "dataprism.approval_id");
        expected.put("approverId", "dataprism.approver_id");
        expected.put("externalCorrelationId", "trace.id");

        AuditFieldMapping m = AuditFieldMapping.ecs();
        assertThat(m.paths()).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(m.paths().keySet()).containsExactlyElementsOf(componentNames());
        assertThat(m.outcomePath()).contains("event.outcome");
    }

    @Test
    void overrideRenamesExternalCorrelationIdToTransactionId() {
        AuditFieldMapping m = AuditFieldMapping.ecs().withOverrides(Map.of("externalCorrelationId", "transaction_id"));
        assertThat(m.pathOf("externalCorrelationId")).isEqualTo("transaction_id");
        assertThat(m.pathOf("tool")).isEqualTo("event.action");
        assertThat(m.paths()).hasSize(AuditFieldMapping.CANONICAL_FIELDS.size());
        // the preset itself is not mutated
        assertThat(AuditFieldMapping.ecs().pathOf("externalCorrelationId")).isEqualTo("trace.id");
    }

    @Test
    void unknownFieldIsRefusedWithItsCode() {
        assertThatThrownBy(() -> AuditFieldMapping.canonical().withOverrides(Map.of("nope", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_AUDIT_FIELD");
    }

    @Test
    void invalidPathIsRefusedWithItsCode() {
        for (String bad : List.of("", "1abc", "a..b", "a.", ".a", "a b", "a-b", "a.b-c", "a\nb")) {
            assertThatThrownBy(() -> AuditFieldMapping.canonical().withOverrides(Map.of("tool", bad)))
                    .as("path '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("INVALID_AUDIT_FIELD_PATH");
        }
        for (String good : List.of("transaction_id", "@timestamp", "a.b.c", "_x.1", "a@b.c@")) {
            assertThat(AuditFieldMapping.canonical().withOverrides(Map.of("tool", good)).pathOf("tool"))
                    .isEqualTo(good);
        }
    }

    @Test
    void twoFieldsOnOnePathConflict() {
        assertThatThrownBy(() -> AuditFieldMapping.canonical().withOverrides(Map.of("tool", "purpose")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_FIELD_MAPPING_CONFLICT");
    }

    @Test
    void aPathThatIsAPrefixSegmentOfAnotherConflicts() {
        assertThatThrownBy(() -> AuditFieldMapping.ecs().withOverrides(Map.of("tool", "event")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_FIELD_MAPPING_CONFLICT");
        // a string prefix that is not a segment prefix is fine
        assertThat(AuditFieldMapping.ecs().withOverrides(Map.of("tool", "even")).pathOf("tool")).isEqualTo("even");
    }

    @Test
    void aFieldCannotTakeTheDerivedOutcomePath() {
        assertThatThrownBy(() -> AuditFieldMapping.ecs().withOverrides(Map.of("tool", "event.outcome")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_FIELD_MAPPING_CONFLICT");
    }
}
