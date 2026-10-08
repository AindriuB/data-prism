package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Stands in for a list appender: no SLF4J provider is on this module's test classpath. */
class Slf4jAuditSinkKeyValueTest {

    private static final AuditRouting ROUTING = new AuditRouting("dataprism.audit", "logs", "dataprism.audit", "prod");

    private static final class Captured {
        final List<String> messages = new ArrayList<>();
        final List<List<Object>> args = new ArrayList<>();
        final List<Map<String, Object>> keyValues = new ArrayList<>();
    }

    private static Logger capturingLogger(Captured out) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class},
                (proxy, method, a) -> {
                    if (method.getName().equals("atInfo")) {
                        Map<String, Object> kv = new LinkedHashMap<>();
                        return Proxy.newProxyInstance(Logger.class.getClassLoader(),
                                new Class<?>[] {LoggingEventBuilder.class}, (bp, m, ba) -> {
                                    switch (m.getName()) {
                                        case "addKeyValue" -> {
                                            kv.put((String) ba[0], ba[1]);
                                            return bp;
                                        }
                                        case "log" -> {
                                            out.messages.add((String) ba[0]);
                                            List<Object> flat = new ArrayList<>();
                                            for (int i = 1; i < ba.length; i++) {
                                                if (ba[i] instanceof Object[] arr) {
                                                    flat.addAll(List.of(arr));
                                                } else {
                                                    flat.add(ba[i]);
                                                }
                                            }
                                            out.args.add(flat);
                                            out.keyValues.add(kv);
                                            return null;
                                        }
                                        default -> {
                                            return m.getReturnType().isInstance(bp) ? bp : null;
                                        }
                                    }
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AuditEvent withExternalId(AuditEvent g, String ext) {
        return new AuditEvent(g.eventId(), g.timestamp(), g.principalId(), g.clientId(), g.tool(), g.entityType(),
                g.subjectPseudonym(), g.parameterFingerprint(), g.privacyProfile(), g.scopeId(), g.purpose(),
                g.caseId(), g.policyDecision(), g.sourceSystems(), g.rejectedArguments(), g.correlationId(),
                g.instanceId(), g.sequence(), g.previousHash(), g.eventHash(), 3, g.fieldDispositions(),
                g.approvalId(), g.approverId(), ext);
    }

    private static AuditEvent event() {
        return withExternalId(new AuditEvent("event-1", Instant.parse("2026-03-01T00:00:00Z"), "p", "c",
                "get_entity_context", "CUSTOMER", "s", "f", "DEFAULT", "scope", "purpose", "CASE", "DENY:POLICY",
                Set.of("a:ANSWERED"), Set.of("purpose"), "corr", "inst", 4, "prev", "hash", 3,
                Map.of("src:/x", "REDACT"), "", "", ""), "ORDER-77");
    }

    @Test
    void theMessageTextIsTheExistingOneWithExtCorrelationAppended() {
        Captured out = new Captured();
        new Slf4jAuditSink(AuditFieldMapping.canonical(), AuditRouting.none(), capturingLogger(out)).record(event());

        assertThat(out.messages).containsExactly("event={} seq={}/{} ts={} principal={} client={} tool={} "
                + "entityType={} subject={} params={} profile={} scope={} purpose={} case={} decision={} "
                + "sources={} rejected={} correlation={} hash={} prev={} extCorrelation={}");
        assertThat(out.args.get(0)).hasSize(21).endsWith("ORDER-77");
    }

    @Test
    void attachesEveryMappedFieldRoutingConstantAndTheOutcomeAsKeyValuePairs() {
        Captured out = new Captured();
        AuditFieldMapping ecs = AuditFieldMapping.ecs();
        new Slf4jAuditSink(ecs, ROUTING, capturingLogger(out)).record(event());

        Map<String, Object> kv = out.keyValues.get(0);
        List<String> expectedKeys = new ArrayList<>(ecs.paths().values());
        expectedKeys.addAll(List.of("event.outcome", "event.dataset", "data_stream.type", "data_stream.dataset",
                "data_stream.namespace"));
        assertThat(kv.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys);
        assertThat(kv).containsEntry("trace.id", "ORDER-77")
                .containsEntry("event.outcome", "failure")
                .containsEntry("dataprism.policy_decision", "DENY:POLICY")
                .containsEntry("dataprism.sequence", 4L)
                .containsEntry("dataprism.source_systems", List.of("a:ANSWERED"))
                .containsEntry("dataprism.field_dispositions", Map.of("src:/x", "REDACT"));
    }

    @Test
    void anOverriddenNameIsUsedAsTheKey() {
        Captured out = new Captured();
        AuditFieldMapping m = AuditFieldMapping.ecs().withOverrides(Map.of("externalCorrelationId", "transaction_id"));
        new Slf4jAuditSink(m, AuditRouting.none(), capturingLogger(out)).record(event());

        assertThat(out.keyValues.get(0)).containsEntry("transaction_id", "ORDER-77").doesNotContainKey("trace.id");
    }

    @Test
    void noArgumentSinkAttachesNoKeyValuePairs() {
        Captured out = new Captured();
        new Slf4jAuditSink(null, null, capturingLogger(out)).record(event());
        assertThat(out.keyValues.get(0)).isEmpty();
    }
}
