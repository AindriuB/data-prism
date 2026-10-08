package io.github.aindriub.dataprism.audit.format;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditEventHash;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Streaming JSON reader and event generator for the projection tests; no ObjectMapper. */
public final class AuditJsonTestSupport {

    private static final JsonFactory JSON = JsonFactory.builder().build();

    private AuditJsonTestSupport() {
    }

    /** Parses one JSON text into Map, List, String, Long, Boolean or null (numbers as Long). */
    public static Object parse(String json) {
        try (JsonParser p = JSON.createParser(json)) {
            p.nextToken();
            Object value = read(p);
            if (p.nextToken() != null) {
                throw new IllegalArgumentException("trailing content");
            }
            return value;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        return (Map<String, Object>) parse(json);
    }

    private static Object read(JsonParser p) throws IOException {
        JsonToken t = p.currentToken();
        switch (t) {
            case START_OBJECT -> {
                Map<String, Object> m = new LinkedHashMap<>();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    String name = p.currentName();
                    p.nextToken();
                    if (m.put(name, read(p)) != null) {
                        throw new IllegalArgumentException("duplicate key " + name);
                    }
                }
                return m;
            }
            case START_ARRAY -> {
                List<Object> l = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    l.add(read(p));
                }
                return l;
            }
            case VALUE_STRING -> {
                return p.getText();
            }
            case VALUE_NUMBER_INT -> {
                return p.getLongValue();
            }
            case VALUE_NULL -> {
                return null;
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            default -> throw new IllegalArgumentException("unexpected token " + t);
        }
    }

    /** The scalar leaves of a parsed tree, as strings, sorted. Object keys are not leaves. */
    public static List<String> leaves(Object tree) {
        List<String> out = new ArrayList<>();
        collect(tree, out);
        out.sort(null);
        return out;
    }

    private static void collect(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) {
            m.values().forEach(v -> collect(v, out));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collect(v, out));
        } else {
            out.add(String.valueOf(node));
        }
    }

    /** Looks up a dotted path in a parsed tree, or fails. */
    public static Object at(Map<String, Object> tree, String path) {
        Object node = tree;
        for (String segment : path.split("\\.")) {
            node = ((Map<?, ?>) node).get(segment);
        }
        return node;
    }

    /** The leaves the event itself contributes, sorted. */
    public static List<String> eventLeaves(AuditEvent e) {
        List<String> out = new ArrayList<>(List.of(e.eventId(), e.timestamp().toString(), e.principalId(),
                e.clientId(), e.tool(), e.entityType(), e.subjectPseudonym(), e.parameterFingerprint(),
                e.privacyProfile(), e.scopeId(), e.purpose(), e.caseId(), e.policyDecision(), e.correlationId(),
                e.instanceId(), Long.toString(e.sequence()), e.previousHash(), e.eventHash(),
                Integer.toString(e.recordVersion()), e.approvalId(), e.approverId(), e.externalCorrelationId()));
        out.addAll(e.sourceSystems());
        out.addAll(e.rejectedArguments());
        out.addAll(e.fieldDispositions().values());
        out.sort(null);
        return out;
    }

    /** Rebuilds an event from the parsed projection by inverting the mapping. */
    @SuppressWarnings("unchecked")
    public static AuditEvent invert(Map<String, Object> tree, AuditFieldMapping m) {
        java.util.function.Function<String, Object> get = f -> at(tree, m.pathOf(f));
        return new AuditEvent((String) get.apply("eventId"), Instant.parse((String) get.apply("timestamp")),
                (String) get.apply("principalId"), (String) get.apply("clientId"), (String) get.apply("tool"),
                (String) get.apply("entityType"), (String) get.apply("subjectPseudonym"),
                (String) get.apply("parameterFingerprint"), (String) get.apply("privacyProfile"),
                (String) get.apply("scopeId"), (String) get.apply("purpose"), (String) get.apply("caseId"),
                (String) get.apply("policyDecision"),
                new TreeSet<>((List<String>) get.apply("sourceSystems")),
                new TreeSet<>((List<String>) get.apply("rejectedArguments")),
                (String) get.apply("correlationId"), (String) get.apply("instanceId"),
                (Long) get.apply("sequence"), (String) get.apply("previousHash"),
                (String) get.apply("eventHash"), ((Long) get.apply("recordVersion")).intValue(),
                new TreeMap<>((Map<String, String>) get.apply("fieldDispositions")),
                (String) get.apply("approvalId"), (String) get.apply("approverId"),
                (String) get.apply("externalCorrelationId"));
    }

    private static final String[] AWKWARD = {
            "plain", "with \"quote\"", "back\\slash", "line\nbreak", "tab\there", "ctrl\u0001char",
            "café 中文", "emoji 😀", "</script>", "a.b", "", "{\"x\":1}", " sep"};

    private static String text(Random r) {
        String base = AWKWARD[r.nextInt(AWKWARD.length)];
        return r.nextBoolean() ? base : base + r.nextInt(1000);
    }

    private static final String[] DECISIONS = {"ALLOW", "ALLOW:redacted", "DENY", "DENY:POLICY", "TOOL_NOT_PERMITTED",
            "", "allow"};

    /** A chained event with generated field values, its hash computed over its content. */
    public static AuditEvent generate(Random r, long sequence) {
        Set<String> sources = new TreeSet<>();
        Set<String> rejected = new TreeSet<>();
        for (int i = r.nextInt(4); i > 0; i--) {
            sources.add(text(r));
        }
        for (int i = r.nextInt(3); i > 0; i--) {
            rejected.add(text(r));
        }
        Map<String, String> dispositions = new TreeMap<>();
        String[] actions = {"REDACT", "HASH", "REFUSED", "PASS_THROUGH"};
        for (int i = r.nextInt(4); i > 0; i--) {
            dispositions.put("src:/" + text(r), actions[r.nextInt(actions.length)]);
        }
        String ext = r.nextBoolean() ? "" : "ext-" + r.nextInt(100000) + ".x/y";
        AuditEvent draft = new AuditEvent("id-" + text(r), Instant.parse("2026-03-01T10:15:30.123Z").plusSeconds(sequence),
                text(r), text(r), text(r), text(r), text(r), text(r), text(r), text(r), text(r), text(r),
                DECISIONS[r.nextInt(DECISIONS.length)], sources, rejected, text(r), "inst/" + r.nextInt(9), sequence,
                "0".repeat(64), "", 3, dispositions, text(r), text(r), ext);
        return withHash(draft, AuditEventHash.compute(draft));
    }

    public static AuditEvent withHash(AuditEvent d, String hash) {
        return new AuditEvent(d.eventId(), d.timestamp(), d.principalId(), d.clientId(), d.tool(), d.entityType(),
                d.subjectPseudonym(), d.parameterFingerprint(), d.privacyProfile(), d.scopeId(), d.purpose(),
                d.caseId(), d.policyDecision(), d.sourceSystems(), d.rejectedArguments(), d.correlationId(),
                d.instanceId(), d.sequence(), d.previousHash(), hash, d.recordVersion(), d.fieldDispositions(),
                d.approvalId(), d.approverId(), d.externalCorrelationId());
    }
}
