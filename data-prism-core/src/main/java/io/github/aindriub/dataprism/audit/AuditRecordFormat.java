package io.github.aindriub.dataprism.audit;

import java.time.Instant;
import java.util.LinkedHashSet;
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
        appendField(line, event.timestamp().toString());
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
        appendRawField(line, encodeField(event.eventHash()), false);
        return line.toString();
    }

    /** Parses a line previously produced by {@link #serialize(AuditEvent)}. */
    public static AuditEvent parse(String line) {
        String[] raw = splitRaw(line, FIELD_SEP, 20);

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

        return new AuditEvent(eventId, timestamp, principalId, clientId, tool, entityType, subjectPseudonym,
                parameterFingerprint, privacyProfile, scopeId, purpose, caseId, policyDecision, sourceSystems,
                rejectedArguments, correlationId, instanceId, sequence, previousHash, eventHash);
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
