package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.github.aindriub.dataprism.audit.AuditJsonTestSupport.at;
import static io.github.aindriub.dataprism.audit.AuditJsonTestSupport.eventLeaves;
import static io.github.aindriub.dataprism.audit.AuditJsonTestSupport.generate;
import static io.github.aindriub.dataprism.audit.AuditJsonTestSupport.invert;
import static io.github.aindriub.dataprism.audit.AuditJsonTestSupport.leaves;
import static io.github.aindriub.dataprism.audit.AuditJsonTestSupport.parseObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditJsonRendererTest {

    private static final AuditRouting ROUTING = new AuditRouting("dataprism.audit", "logs", "dataprism.audit", "prod");

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        out.sort(null);
        return out;
    }

    @Test
    void oneLineWithNoLineBreakAndEveryCanonicalFieldOnce() {
        Random r = new Random(1);
        for (int i = 0; i < 300; i++) {
            AuditEvent e = generate(r, i + 1);
            String line = AuditJsonRenderer.render(e, AuditFieldMapping.canonical(), AuditRouting.none());
            assertThat(line).doesNotContain("\n").doesNotContain("\r").doesNotContain(" ");
            Map<String, Object> tree = parseObject(line);
            assertThat(tree.keySet()).containsExactlyInAnyOrderElementsOf(AuditFieldMapping.CANONICAL_FIELDS);
        }
    }

    @Test
    void leafValuesAreExactlyTheEventsPlusRoutingPlusOutcome() {
        Random r = new Random(2);
        for (int i = 0; i < 300; i++) {
            AuditEvent e = generate(r, i + 1);

            Map<String, Object> canonical = parseObject(
                    AuditJsonRenderer.render(e, AuditFieldMapping.canonical(), AuditRouting.none()));
            assertThat(leaves(canonical)).as("canonical, no routing").isEqualTo(eventLeaves(e));

            List<String> routed = new ArrayList<>(eventLeaves(e));
            routed.addAll(List.of("dataprism.audit", "logs", "dataprism.audit", "prod"));
            assertThat(leaves(parseObject(AuditJsonRenderer.render(e, AuditFieldMapping.canonical(), ROUTING))))
                    .as("canonical, routing constants").isEqualTo(sorted(routed));

            List<String> ecs = new ArrayList<>(routed);
            ecs.add(AuditJsonRenderer.outcome(e.policyDecision()));
            assertThat(leaves(parseObject(AuditJsonRenderer.render(e, AuditFieldMapping.ecs(), ROUTING))))
                    .as("ecs, routing constants and outcome").isEqualTo(sorted(ecs));
        }
    }

    @Test
    void invertingTheMappingGivesBackTheEventAndItsHash() {
        Random r = new Random(3);
        for (AuditFieldMapping mapping : List.of(AuditFieldMapping.canonical(), AuditFieldMapping.ecs(),
                AuditFieldMapping.ecs().withOverrides(Map.of("externalCorrelationId", "transaction_id")))) {
            for (int i = 0; i < 200; i++) {
                AuditEvent e = generate(r, i + 1);
                AuditEvent back = invert(parseObject(AuditJsonRenderer.render(e, mapping, ROUTING)), mapping);
                assertThat(back).isEqualTo(e);
                assertThat(AuditEventHash.compute(back)).isEqualTo(e.eventHash());
            }
        }
    }

    @Test
    void transactionIdOverrideRendersTheExternalIdUnderThatName() {
        AuditEvent e = generate(new Random(4), 1);
        AuditEvent withExt = new AuditEvent(e.eventId(), e.timestamp(), e.principalId(), e.clientId(), e.tool(),
                e.entityType(), e.subjectPseudonym(), e.parameterFingerprint(), e.privacyProfile(), e.scopeId(),
                e.purpose(), e.caseId(), e.policyDecision(), e.sourceSystems(), e.rejectedArguments(),
                e.correlationId(), e.instanceId(), e.sequence(), e.previousHash(), e.eventHash(), 3,
                e.fieldDispositions(), e.approvalId(), e.approverId(), "ORDER-77/a");
        AuditFieldMapping m = AuditFieldMapping.ecs().withOverrides(Map.of("externalCorrelationId", "transaction_id"));

        Map<String, Object> tree = parseObject(AuditJsonRenderer.render(withExt, m, AuditRouting.none()));

        assertThat(tree.get("transaction_id")).isEqualTo("ORDER-77/a");
        assertThat(tree).doesNotContainKey("trace");
        assertThat(at(tree, "event.action")).isEqualTo(e.tool());
    }

    @Test
    void ecsNestsDottedPathsAndKeepsTheRawDecisionBesideTheOutcome() {
        AuditEvent e = generate(new Random(5), 1);
        Map<String, Object> tree = parseObject(AuditJsonRenderer.render(e, AuditFieldMapping.ecs(), ROUTING));
        assertThat(tree).containsKey("@timestamp");
        assertThat(at(tree, "event.id")).isEqualTo(e.eventId());
        assertThat(at(tree, "event.dataset")).isEqualTo("dataprism.audit");
        assertThat(at(tree, "data_stream.type")).isEqualTo("logs");
        assertThat(at(tree, "data_stream.dataset")).isEqualTo("dataprism.audit");
        assertThat(at(tree, "data_stream.namespace")).isEqualTo("prod");
        assertThat(at(tree, "dataprism.policy_decision")).isEqualTo(e.policyDecision());
        assertThat(at(tree, "event.outcome")).isEqualTo(AuditJsonRenderer.outcome(e.policyDecision()));
        assertThat(at(tree, "dataprism.sequence")).isEqualTo(e.sequence());
    }

    @Test
    void outcomeIsDerivedFromThePolicyDecisionOnly() {
        assertThat(AuditJsonRenderer.outcome("ALLOW")).isEqualTo("success");
        assertThat(AuditJsonRenderer.outcome("ALLOW:redacted")).isEqualTo("success");
        assertThat(AuditJsonRenderer.outcome("")).isEqualTo("unknown");
        assertThat(AuditJsonRenderer.outcome("DENY")).isEqualTo("failure");
        assertThat(AuditJsonRenderer.outcome("DENY:POLICY_X")).isEqualTo("failure");
        assertThat(AuditJsonRenderer.outcome("TOOL_NOT_PERMITTED")).isEqualTo("failure");
        assertThat(AuditJsonRenderer.outcome("ALLOWED")).isEqualTo("failure");
        assertThat(AuditJsonRenderer.outcome("allow")).isEqualTo("failure");
    }

    @Test
    void canonicalPresetAddsNoOutcome() {
        AuditEvent e = generate(new Random(6), 1);
        assertThat(parseObject(AuditJsonRenderer.render(e, AuditFieldMapping.canonical(), AuditRouting.none())))
                .doesNotContainKey("event");
    }

    @Test
    void routingCollidingWithAMappedPathIsRefusedAtRender() {
        AuditEvent e = generate(new Random(7), 1);
        AuditFieldMapping m = AuditFieldMapping.canonical().withOverrides(Map.of("tool", "event.dataset"));
        assertThatThrownBy(() -> AuditJsonRenderer.render(e, m, ROUTING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_FIELD_MAPPING_CONFLICT");
    }

    @Test
    void keyValuesCarryTheSameValuesAsTheJsonLeaves() {
        Random r = new Random(8);
        for (int i = 0; i < 100; i++) {
            AuditEvent e = generate(r, i + 1);
            Map<String, Object> kv = AuditJsonRenderer.keyValues(e, AuditFieldMapping.ecs(), ROUTING);
            assertThat(kv.keySet()).contains("event.outcome", "event.dataset", "dataprism.sequence", "trace.id");
            assertThat(leaves(kv)).isEqualTo(
                    leaves(parseObject(AuditJsonRenderer.render(e, AuditFieldMapping.ecs(), ROUTING))));
        }
    }
}
