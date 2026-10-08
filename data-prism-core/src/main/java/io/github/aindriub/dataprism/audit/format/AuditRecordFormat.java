package io.github.aindriub.dataprism.audit.format;

import io.github.aindriub.dataprism.audit.AuditEvent;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/**
 * The single on-disk record shape: one {@link AuditEvent} per line.
 *
 * <p>Every component of {@link AuditEvent} round-trips through this format,
 * including the chain fields ({@code instanceId}, {@code sequence},
 * {@code previousHash}, {@code eventHash}). Task 66's verifier and task 65's
 * PII scan read only what this class writes, so a field silently dropped here
 * is a field neither of them can ever see.
 *
 * <p>A line has 20 fields (version 1), 24 (version 2) or 25 (version 3). Version 3 appends
 * {@code externalCorrelationId} as the last field, after {@code approverId}; {@code parse}
 * decides the version by field count, and the declared {@code recordVersion} must agree with it exactly
 * (version 1 has no such field and so is 20 fields; 2 is 24; 3 is 25); any mismatch is refused.
 *
 * <p>Fields are separated by an ASCII Unit Separator (0x1F); set members are
 * separated by an ASCII Record Separator (0x1E). Both control characters, and
 * the backslash and newline that could otherwise be mistaken for them, are
 * escaped in every component value, so a component containing any of them
 * still produces exactly one line. A {@code null} component is encoded as a
 * distinct sentinel, never as the empty string, so the two round-trip to
 * different values; a component whose real content happens to equal the
 * sentinel text still round-trips correctly, because {@link #escape(String)}
 * never produces that sentinel for non-null input. The same distinction
 * applies to an empty set versus a set holding a single empty string.
 *
 * <p>A record is complete, and safe to treat as such, only if its line is
 * terminated by a newline. A trailing chunk of file content with no
 * terminating newline — for example a process killed mid-write — is an
 * in-progress write, not a record: it must not be parsed, and must not be
 * compared against an expected {@code eventHash}, because it never finished
 * being written. Recognising "no newline yet" as distinct from "tampered
 * after being written" is exactly as far as this format's tamper-evidence
 * goes; it does not, by itself, detect truncation of records that were
 * previously complete — that needs an external checkpoint outside this
 * class.
 */
public final class AuditRecordFormat {

    private static final char FIELD_SEP = '';
    private static final char SET_SEP = '';
    private static final String NULL_TOKEN = "\\0";
    private static final String EMPTY_SET_TOKEN = "\\e";

    private AuditRecordFormat() {
    }

    /** Serialises {@code event} to a single line, with no trailing newline. */
    public static String serialize(AuditEvent event) {
        StringBuilder line = new StringBuilder();
        appendField(line, event.eventId());
        appendField(line, event.timestamp() == null ? null : event.timestamp().toString());
        appendField(line, event.principalId());
        appendField(line, event.clientId());
        appendField(line, event.tool());
        appendField(line, event.entityType());
        appendField(line, event.subjectPseudonym());
        appendField(line, event.parameterFingerprint());
        appendField(line, event.privacyProfile());
        appendField(line, event.scopeId());
        appendField(line, event.purpose());
        appendField(line, event.caseId());
        appendField(line, event.policyDecision());
        appendRawField(line, encodeSet(event.sourceSystems()), true);
        appendRawField(line, encodeSet(event.rejectedArguments()), true);
        appendField(line, event.correlationId());
        appendField(line, event.instanceId());
        appendField(line, Long.toString(event.sequence()));
        appendField(line, event.previousHash());
        if (event.recordVersion() < 2) {
            appendRawField(line, encodeField(event.eventHash()), false);
            return line.toString();
        }
        appendField(line, event.eventHash());
        appendField(line, Integer.toString(event.recordVersion()));
        appendRawField(line, encodeDispositions(event.fieldDispositions()), true);
        appendField(line, event.approvalId());
        if (event.recordVersion() < 3) {
            appendRawField(line, encodeField(event.approverId()), false);
            return line.toString();
        }
        appendField(line, event.approverId());
        appendRawField(line, encodeField(event.externalCorrelationId()), false);
        return line.toString();
    }

    /**
     * A torn write truncates a line; it cannot add separators. A line with more fields than its
     * declared version allows (or more than 25 at all) is therefore tampering, never an interrupted
     * write. Lines of 21 to 23 fields are over-count only when they cannot be a truncated version 2
     * or 3 line: field 20 is neither empty nor a supported version.
     */
    private static boolean isOverCount(String[] raw) {
        if (raw.length <= 20) {
            return false;
        }
        String declared = decode(raw[20]);
        Integer version = null;
        try {
            version = Integer.valueOf(declared);
        } catch (NumberFormatException e) {
            // not a parseable version
        }
        if (raw.length > 25) {
            return version != null;
        }
        if (raw.length < 24) {
            return !declared.isEmpty() && version == null || version != null && version != 2 && version != 3;
        }
        return false;
    }

    /** Parses a line previously produced by {@link #serialize(AuditEvent)}. */
    public static AuditEvent parse(String line) {
        // A line without recordVersion has exactly 20 fields and is version 1.
        String[] raw = splitRaw(line, FIELD_SEP, -1);
        if (isOverCount(raw)) {
            throw new FieldCountMismatchException(
                    "malformed audit record: more fields than any record version allows, found " + raw.length);
        }
        if (raw.length != 20 && raw.length != 24 && raw.length != 25) {
            throw new IllegalArgumentException(
                    "malformed audit record: expected 20, 24 or 25 fields, found " + raw.length);
        }

        String eventId = decode(raw[0]);
        Instant timestamp = Instant.parse(decode(raw[1]));
        String principalId = decode(raw[2]);
        String clientId = decode(raw[3]);
        String tool = decode(raw[4]);
        String entityType = decode(raw[5]);
        String subjectPseudonym = decode(raw[6]);
        String parameterFingerprint = decode(raw[7]);
        String privacyProfile = decode(raw[8]);
        String scopeId = decode(raw[9]);
        String purpose = decode(raw[10]);
        String caseId = decode(raw[11]);
        String policyDecision = decode(raw[12]);
        Set<String> sourceSystems = decodeSet(raw[13]);
        Set<String> rejectedArguments = decodeSet(raw[14]);
        String correlationId = decode(raw[15]);
        String instanceId = decode(raw[16]);
        long sequence = Long.parseLong(decode(raw[17]));
        String previousHash = decode(raw[18]);
        String eventHash = decode(raw[19]);

        if (raw.length == 20) {
            return new AuditEvent(eventId, timestamp, principalId, clientId, tool, entityType, subjectPseudonym,
                    parameterFingerprint, privacyProfile, scopeId, purpose, caseId, policyDecision, sourceSystems,
                    rejectedArguments, correlationId, instanceId, sequence, previousHash, eventHash);
        }
        int recordVersion = Integer.parseInt(decode(raw[20]));
        Map<String, String> dispositions = decodeDispositions(raw[21]);
        String approvalId = decode(raw[22]);
        String approverId = decode(raw[23]);
        // The field count must match the declared version exactly. A field the hash does not cover
        // (anything beyond 24 on a version 2 line, say) must never be accepted, or it could be injected.
        int expectedFields = switch (recordVersion) {
            case 2 -> 24;
            case 3 -> 25;
            default -> -1;
        };
        if (raw.length != expectedFields) {
            throw new FieldCountMismatchException("malformed audit record: recordVersion " + recordVersion
                    + " requires exactly " + (expectedFields < 0 ? "an unsupported number of" : expectedFields)
                    + " fields, found " + raw.length);
        }
        String externalCorrelationId = raw.length == 25 ? decode(raw[24]) : "";

        return new AuditEvent(eventId, timestamp, principalId, clientId, tool, entityType, subjectPseudonym,
                parameterFingerprint, privacyProfile, scopeId, purpose, caseId, policyDecision, sourceSystems,
                rejectedArguments, correlationId, instanceId, sequence, previousHash, eventHash, recordVersion,
                dispositions, approvalId, approverId, externalCorrelationId);
    }

    /** Each entry is {@code path=ACTION}; the action never contains '=', so the last one splits it. */
    private static String encodeDispositions(Map<String, String> dispositions) {
        if (dispositions.isEmpty()) {
            return EMPTY_SET_TOKEN;
        }
        StringBuilder encoded = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : dispositions.entrySet()) {
            if (!first) {
                encoded.append(SET_SEP);
            }
            encoded.append(escape(e.getKey() + "=" + e.getValue()));
            first = false;
        }
        return encoded.toString();
    }

    private static Map<String, String> decodeDispositions(String raw) {
        Map<String, String> values = new TreeMap<>();
        if (raw.equals(EMPTY_SET_TOKEN)) {
            return values;
        }
        for (String item : splitRaw(raw, SET_SEP, -1)) {
            String entry = decode(item);
            int eq = entry.lastIndexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("malformed audit record: disposition without '='");
            }
            values.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return values;
    }

    private static String encodeSet(Set<String> values) {
        if (values.isEmpty()) {
            return EMPTY_SET_TOKEN;
        }
        StringBuilder encoded = new StringBuilder();
        boolean first = true;
        for (String value : values) {
            if (!first) {
                encoded.append(SET_SEP);
            }
            encoded.append(escape(value));
            first = false;
        }
        return encoded.toString();
    }

    private static Set<String> decodeSet(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw.equals(EMPTY_SET_TOKEN)) {
            return values;
        }
        for (String item : splitRaw(raw, SET_SEP, -1)) {
            values.add(decode(item));
        }
        return values;
    }

    private static void appendField(StringBuilder line, String value) {
        appendRawField(line, encodeField(value), true);
    }

    /**
     * Encodes {@code value} for use as a field's already-escaped content: a
     * {@code null} value becomes {@link #NULL_TOKEN}, which {@link
     * #escape(String)} never produces from a non-null value, so the two are
     * always distinguishable on decode.
     */
    private static String encodeField(String value) {
        return value == null ? NULL_TOKEN : escape(value);
    }

    /**
     * Appends {@code alreadyEscaped}, which must already have every backslash,
     * newline, carriage return and separator character escaped by {@link
     * #escape(String)} (a set is escaped item-by-item, so it is passed through
     * here unescaped a second time).
     */
    private static void appendRawField(StringBuilder line, String alreadyEscaped, boolean withSep) {
        line.append(alreadyEscaped);
        if (withSep) {
            line.append(FIELD_SEP);
        }
    }

    /** Escapes backslash, newline, carriage return and both separator characters. */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case FIELD_SEP -> escaped.append("\\f");
                case SET_SEP -> escaped.append("\\s");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String decode(String raw) {
        if (raw.equals(NULL_TOKEN)) {
            return null;
        }
        StringBuilder decoded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case '\\' -> decoded.append('\\');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 'f' -> decoded.append(FIELD_SEP);
                    case 's' -> decoded.append(SET_SEP);
                    default -> {
                        decoded.append('\\');
                        decoded.append(next);
                    }
                }
            } else {
                decoded.append(c);
            }
        }
        return decoded.toString();
    }

    /**
     * Splits {@code raw} on unescaped occurrences of {@code sep}, leaving each
     * returned substring still escaped (call {@link #decode(String)} on it).
     * {@code expectedCount} of {@code -1} means "however many there are".
     */
    private static String[] splitRaw(String raw, char sep, int expectedCount) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                current.append(c).append(raw.charAt(i + 1));
                i++;
            } else if (c == sep) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        if (expectedCount >= 0 && parts.size() != expectedCount) {
            throw new IllegalArgumentException(
                    "malformed audit record: expected " + expectedCount + " fields, found " + parts.size());
        }
        return parts.toArray(new String[0]);
    }
}
