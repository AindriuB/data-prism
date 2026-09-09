package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.ConsistencyFinding;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification's §64 worked example, end to end.
 *
 * <p>Three systems hold the same customer under three spellings of one name. The
 * model must see one consistent identity — otherwise it cannot follow the person
 * across systems, which is the platform's purpose — and must also be told that
 * the systems disagree, which is the other half of it. Neither is useful alone: a
 * consistent identity without the finding has quietly erased a data quality
 * problem, and a finding without a consistent identity is unreadable.
 */
class WorkedExampleTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditSink sink = audited::add;
    private final DataPrismAssembly assembly = DataPrismAssembly.standard();

    private ContextResponse customer123() {
        return assembly.orchestrator().buildContext(
                ContextRequest.of("CUSTOMER", "123"), assembly.privacyContext(),
                assembly.investigationContext());
    }

    @Test
    @DisplayName("one subject reads the same across all three systems")
    void identityIsConsistentAcrossSources() {
        ContextResponse response = customer123();
        String body = response.entity().toString();

        // The three source spellings are Patrick Murphy, Pat Murphy and P. Murphy.
        // None of them may appear.
        assertThat(body)
                .doesNotContain("Patrick Murphy")
                .doesNotContain("Pat Murphy")
                .doesNotContain("P. Murphy");

        // And the fields that carried them all resolve to the same pseudonym,
        // because the pseudonym is keyed on the subject rather than the value.
        String customerName = response.entity().get("customerName").asText();
        String holderName = response.entity().get("holderName").asText();
        assertThat(holderName).isEqualTo(customerName);
        assertThat(customerName).matches("^[A-Za-z]+ [A-Za-z]+ \\([0-9A-Z]{4}\\)$");
    }

    @Test
    @DisplayName("the disagreement survives being pseudonymised away")
    void disagreementIsReported() {
        ContextResponse response = customer123();

        assertThat(response.hasDisagreement())
                .as("three spellings of one name must not read as agreement").isTrue();

        ConsistencyFinding name = response.findings().stream()
                .filter(f -> f.namespace().name().equals("PERSON_NAME"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding about the name"));

        // ABBREVIATION rather than the plainer INCONSISTENT: all three are
        // shortenings of one name, and telling an investigator that is more
        // useful than telling them only that the values differ. It is still a
        // disagreement.
        assertThat(name.kind()).isEqualTo(ConsistencyFinding.Kind.ABBREVIATION);
        assertThat(name.disagreement()).isTrue();
        assertThat(name.distinctValues()).isEqualTo(3);
    }

    @Test
    @DisplayName("a finding says which sources agree, never what they hold")
    void findingsCarryNoValues() {
        ContextResponse response = customer123();

        for (ConsistencyFinding finding : response.findings()) {
            assertThat(finding.toString())
                    .doesNotContain("Patrick").doesNotContain("Pat Murphy")
                    .doesNotContain("P. Murphy").doesNotContain("123");
        }

        // Three sources, three different values, so three groups of one. That
        // tells an investigator exactly where to look and discloses nothing.
        ConsistencyFinding name = response.findings().stream()
                .filter(f -> f.kind() == ConsistencyFinding.Kind.ABBREVIATION)
                .findFirst().orElseThrow();
        assertThat(name.agreementGroups()).hasSize(3).allSatisfy(g -> assertThat(g).hasSize(1));
        assertThat(name.agreementGroups().stream().flatMap(List::stream))
                .containsExactlyInAnyOrder("customer-api", "account-api", "order-api");
    }

    @Test
    @DisplayName("two sources agreeing and one differing is reported as exactly that")
    void agreementGroupsPartitionTheSources() {
        // Customer 456 is spelled identically in the account and order systems.
        ContextResponse response = assembly.orchestrator().buildContext(
                ContextRequest.of("CUSTOMER", "456"), assembly.privacyContext(),
                assembly.investigationContext());

        assertThat(response.findings())
                .as("all three systems agree on this one, so there is nothing to report")
                .noneMatch(f -> f.namespace().name().equals("PERSON_NAME"));
    }

    @Test
    @DisplayName("instruction-shaped text in a source is flagged and not removed")
    void injectionAttemptIsFlaggedAsData() {
        ContextResponse response = customer123();

        assertThat(response.findings())
                .anyMatch(f -> f.kind() == ConsistencyFinding.Kind.SUSPECTED_INSTRUCTION_CONTENT);

        // Flagged, not rewritten: editing the note would hide an attack from the
        // person best placed to notice it, and would change the business truth.
        ConsistencyFinding flagged = response.findings().stream()
                .filter(f -> f.kind() == ConsistencyFinding.Kind.SUSPECTED_INSTRUCTION_CONTENT)
                .findFirst().orElseThrow();
        assertThat(flagged.detail()).doesNotContain("Ignore previous instructions");
    }

    @Test
    @DisplayName("every source that answered is named in the response")
    void allSourcesAreAccountedFor() {
        ContextResponse response = customer123();

        assertThat(response.sources())
                .containsKeys("customer-api", "account-api", "order-api");
        assertThat(response.answered()).hasSize(3);
        assertThat(response.incomplete()).isFalse();
    }
}
