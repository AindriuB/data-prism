package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuditRecordFormat} is the only thing task 66's verifier and task
 * 65's scan read, so every {@link AuditEvent} component — including the
 * chain fields — must survive a round trip unchanged.
 */
class AuditRecordFormatTest {

    private static AuditEvent event(String subjectPseudonym) {
        return new AuditEvent(
                "event-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                "CUSTOMER",
                subjectPseudonym,
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of("customer-api:ANSWERED", "billing-api:ANSWERED"),
                Set.of("scopeId"),
                "corr-1",
                "instance-1",
                42L,
                "prev-hash",
                "event-hash");
    }

    @Test
    @DisplayName("every AuditEvent component round-trips through serialize and parse")
    void roundTripsEveryComponent() {
        AuditEvent original = event("pseudo-1");

        AuditEvent parsed = AuditRecordFormat.parse(AuditRecordFormat.serialize(original));

        assertThat(parsed.eventId()).isEqualTo(original.eventId());
        assertThat(parsed.timestamp()).isEqualTo(original.timestamp());
        assertThat(parsed.principalId()).isEqualTo(original.principalId());
        assertThat(parsed.clientId()).isEqualTo(original.clientId());
        assertThat(parsed.tool()).isEqualTo(original.tool());
        assertThat(parsed.entityType()).isEqualTo(original.entityType());
        assertThat(parsed.subjectPseudonym()).isEqualTo(original.subjectPseudonym());
        assertThat(parsed.parameterFingerprint()).isEqualTo(original.parameterFingerprint());
        assertThat(parsed.privacyProfile()).isEqualTo(original.privacyProfile());
        assertThat(parsed.scopeId()).isEqualTo(original.scopeId());
        assertThat(parsed.purpose()).isEqualTo(original.purpose());
        assertThat(parsed.caseId()).isEqualTo(original.caseId());
        assertThat(parsed.policyDecision()).isEqualTo(original.policyDecision());
        assertThat(parsed.sourceSystems()).isEqualTo(original.sourceSystems());
        assertThat(parsed.rejectedArguments()).isEqualTo(original.rejectedArguments());
        assertThat(parsed.correlationId()).isEqualTo(original.correlationId());
        assertThat(parsed.instanceId()).isEqualTo(original.instanceId());
        assertThat(parsed.sequence()).isEqualTo(original.sequence());
        assertThat(parsed.previousHash()).isEqualTo(original.previousHash());
        assertThat(parsed.eventHash()).isEqualTo(original.eventHash());
    }

    @Test
    @DisplayName("serialize produces exactly one UTF-8 line terminated by a single newline when written")
    void serializesToOneLine() {
        String line = AuditRecordFormat.serialize(event("pseudo-1"));

        assertThat(line).doesNotContain("\n");
    }

    @Test
    @DisplayName("a newline embedded in a component still produces exactly one line and parses back identically")
    void escapesEmbeddedNewline() {
        AuditEvent original = event("pseudo\n1-with-a\nnewline");

        String line = AuditRecordFormat.serialize(original);
        String fileContent = line + "\n";

        assertThat(fileContent.split("\n", -1)).hasSize(2);
        assertThat(fileContent.split("\n", -1)[1]).isEmpty();

        AuditEvent parsed = AuditRecordFormat.parse(line);
        assertThat(parsed.subjectPseudonym()).isEqualTo(original.subjectPseudonym());
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    @DisplayName("a null component round-trips to null, distinct from the empty string, and does not throw")
    void nullComponentRoundTripsToNullNotEmptyString() {
        AuditEvent withNull = new AuditEvent(
                "event-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                null,
                "pseudo-1",
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of(),
                Set.of(),
                "corr-1",
                "instance-1",
                42L,
                "prev-hash",
                "event-hash");
        AuditEvent withEmptyString = new AuditEvent(
                "event-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                "",
                "pseudo-1",
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of(),
                Set.of(),
                "corr-1",
                "instance-1",
                42L,
                "prev-hash",
                "event-hash");

        AuditEvent parsedNull = AuditRecordFormat.parse(AuditRecordFormat.serialize(withNull));
        AuditEvent parsedEmpty = AuditRecordFormat.parse(AuditRecordFormat.serialize(withEmptyString));

        assertThat(parsedNull.entityType()).isNull();
        assertThat(parsedEmpty.entityType()).isEqualTo("");
        assertThat(parsedNull.entityType()).isNotEqualTo(parsedEmpty.entityType());
    }

    @Test
    @DisplayName("a component whose literal content equals the null sentinel still round-trips as that content, not as null")
    void componentEqualToNullSentinelTextStillRoundTrips() {
        AuditEvent original = event("\\0");

        AuditEvent parsed = AuditRecordFormat.parse(AuditRecordFormat.serialize(original));

        assertThat(parsed.subjectPseudonym()).isEqualTo("\\0");
        assertThat(parsed.subjectPseudonym()).isNotNull();
    }

    @Test
    @DisplayName("a set holding a single empty string round-trips distinctly from an empty set")
    void setWithEmptyStringRoundTripsDistinctlyFromEmptySet() {
        AuditEvent withEmptyStringElement = new AuditEvent(
                "event-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                "CUSTOMER",
                "pseudo-1",
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of(""),
                Set.of(),
                "corr-1",
                "instance-1",
                42L,
                "prev-hash",
                "event-hash");
        AuditEvent withEmptySet = new AuditEvent(
                "event-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                "CUSTOMER",
                "pseudo-1",
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of(),
                Set.of(),
                "corr-1",
                "instance-1",
                42L,
                "prev-hash",
                "event-hash");

        AuditEvent parsedWithElement = AuditRecordFormat.parse(AuditRecordFormat.serialize(withEmptyStringElement));
        AuditEvent parsedEmptySet = AuditRecordFormat.parse(AuditRecordFormat.serialize(withEmptySet));

        assertThat(parsedWithElement.sourceSystems()).isEqualTo(Set.of(""));
        assertThat(parsedEmptySet.sourceSystems()).isEqualTo(Set.of());
        assertThat(parsedWithElement.sourceSystems()).isNotEqualTo(parsedEmptySet.sourceSystems());
    }

    @Test
    @DisplayName("a set element equal to the empty-set sentinel text still round-trips as that element")
    void setElementEqualToEmptySetSentinelTextStillRoundTrips() {
        AuditEvent original = new AuditEvent(
                "event-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                "CUSTOMER",
                "pseudo-1",
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of("\\e"),
                Set.of(),
                "corr-1",
                "instance-1",
                42L,
                "prev-hash",
                "event-hash");

        AuditEvent parsed = AuditRecordFormat.parse(AuditRecordFormat.serialize(original));

        assertThat(parsed.sourceSystems()).isEqualTo(Set.of("\\e"));
    }

    @Test
    @DisplayName("a torn trailing chunk with no terminating newline is distinguishable from a complete record")
    void tornTrailingChunkIsDistinguishableFromACompleteRecord() {
        AuditEvent original = event("pseudo-1");
        String completeLine = AuditRecordFormat.serialize(original);
        String fileContent = completeLine + "\n" + completeLine.substring(0, completeLine.length() / 2);

        assertThat(fileContent).doesNotEndWith("\n");

        int lastNewline = fileContent.lastIndexOf('\n');
        String completePart = fileContent.substring(0, lastNewline);
        String tornTrailingChunk = fileContent.substring(lastNewline + 1);

        AuditEvent parsedComplete = AuditRecordFormat.parse(completePart);
        assertThat(parsedComplete).isEqualTo(original);

        assertThatThrownBy(() -> AuditRecordFormat.parse(tornTrailingChunk))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
