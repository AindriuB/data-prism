package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.limits.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.*;
import io.github.aindriub.dataprism.core.spi.*;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ResponseLimitsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final StaticSecretKeyProvider KEYS = StaticSecretKeyProvider.of("development-only-key-not-for-any-real-data");
    private static final PrivacyContext CONTEXT = new PrivacyContext("scope-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    private static final InvestigationContext CALLER = new InvestigationContext("principal-1", "client-1", "case-1", Set.of());
    private record Parcel(String marker, List<Parcel> children) { }

    static ObjectNode tree(int children) {
        ObjectNode root = MAPPER.createObjectNode().put("label", "synthetic-parcel");
        var items = root.putArray("items");
        for (int i = 0; i < children; i++) items.addObject().put("label", "synthetic-depot");
        return root;
    }

    private ContextResponse call(ObjectNode tree, int records, int bytes, boolean refused, List<AuditEvent> events) {
        return call(tree, records, bytes, refused ? List.of("alpha", "withheld") : List.of("alpha"), false, events);
    }

    private ContextResponse call(ObjectNode tree, int records, int bytes, List<String> names,
                                 boolean refuseDuringValidation, List<AuditEvent> events) {
        List<DataSourceAdapter<?>> adapters = new ArrayList<>();
        for (String name : names) {
            adapters.add(new DataSourceAdapter<Parcel>() {
                public String sourceName() { return name; }
                public Class<Parcel> responseType() { return Parcel.class; }
                public Parcel fetch(DataRequest request) { return new Parcel(name, name.equals("withheld")
                        ? java.util.Collections.nCopies(500, new Parcel("withheld-payload-".repeat(100), List.of())) : List.of()); }
            });
        }
        var resolver = new DefaultFieldMetadataResolver();
        ScrubbingEngine scrubber = (record, context) -> {
            if (((Parcel) record).marker().equals("withheld")) {
                if (refuseDuringValidation) return new ScrubResult(tree(500).put("withhold", true), Set.of());
                throw new PrivacyRefusedException("FIXTURE_REFUSED", "$", "withheld");
            }
            return new ScrubResult(tree, Set.of());
        };
        var orchestrator = new DefaultContextOrchestrator(adapters, scrubber, resolver,
                List.of((node, prohibited, emitted, context) -> node.has("withhold")
                        ? ValidationResult.failed(List.of(new io.github.aindriub.dataprism.validation.Violation("$", "FIXTURE", "TEST")))
                        : ValidationResult.ok()),
                (subject, namespace, context) -> "SUBJ-1", new ParameterFingerprinter(KEYS),
                new AuditRecorder(events::add, CLOCK, "limits-test"), new PassThroughIdentityResolver(),
                new SourceFanOut(SourceCircuitBreaker.disabled(), CLOCK, SourceFanOutOptions.defaults()),
                new InMemoryScopeBudget(), new RequestLimits(8, records, bytes, Duration.ofSeconds(3), 4, 100),
                (raw, context) -> List.of(), new SourceAliasing(new HmacValueTokenSource(KEYS)), PrivacyMetrics.none());
        return orchestrator.buildContext(ContextRequest.of("PARCEL", "fixture-1"), CONTEXT, CALLER);
    }

    @Test void recordLimitAllowsEqualityAndRefusesOneMore() {
        var events = new ArrayList<AuditEvent>();
        assertThat(call(tree(2), 3, 10000, false, events).sources()).hasSize(1);
        events.clear();
        assertThatThrownBy(() -> call(tree(2), 2, 10000, false, events))
                .isInstanceOf(AuditedRefusalException.class)
                .satisfies(e -> assertThat(((PrivacyRefusedException)e).code()).isEqualTo("TOO_MANY_RECORDS"));
        assertThat(events).singleElement().satisfies(e -> assertThat(e.policyDecision()).isEqualTo("DENY:TOO_MANY_RECORDS"));
    }

    @Test void byteLimitAllowsEqualityAndRefusesOneLessThanAsciiBody() {
        var events = new ArrayList<AuditEvent>();
        var response = call(tree(0), 1, 10000, false, events);
        int bytes = MAPPER.writeValueAsString(response).getBytes(StandardCharsets.UTF_8).length;
        assertThat(call(tree(0), 1, bytes, false, new ArrayList<>()).sources()).hasSize(1);
        events.clear();
        assertThatThrownBy(() -> call(tree(0), 1, bytes - 1, false, events))
                .isInstanceOf(AuditedRefusalException.class)
                .satisfies(e -> {
                    assertThat(((PrivacyRefusedException)e).code()).isEqualTo("RESPONSE_TOO_LARGE");
                    assertThat(e.getMessage()).doesNotContain("synthetic-parcel");
                });
        assertThat(events).singleElement().satisfies(e -> assertThat(e.policyDecision()).isEqualTo("DENY:RESPONSE_TOO_LARGE"));
    }

    @Test void nestedObjectsOnlyCountWhenTheyAreArrayElements() {
        var root = tree(0);
        root.putObject("address").putObject("location").put("label", "synthetic");
        root.putArray("nested").addArray().addObject().put("label", "synthetic");
        root.putArray("scalars").add(1).add(2);
        assertThat(call(root, 2, 10000, false, new ArrayList<>()).sources()).hasSize(1);
        assertThatThrownBy(() -> call(root, 1, 10000, false, new ArrayList<>()))
                .isInstanceOf(PrivacyRefusedException.class);
    }

    @Test void refusedSourceAddsNoRecordsOrPayloadBytes() {
        var events = new ArrayList<AuditEvent>();
        var response = call(tree(0), 1, 10000, true, events);
        int bytes = MAPPER.writeValueAsString(response).getBytes(StandardCharsets.UTF_8).length;
        var limited = call(tree(0), 1, bytes, true, new ArrayList<>());
        assertThat(limited.sources().values()).extracting(SourceResult::status).containsExactlyInAnyOrder("ANSWERED", "REFUSED");
        assertThat(events).singleElement().satisfies(e -> assertThat(e.policyDecision()).isEqualTo("ALLOW"));
    }
    @Test void recordCapIsCombinedAcrossReturnedSources() {
        var names = List.of("alpha", "beta");
        assertThat(call(tree(1), 4, 10000, names, false, new ArrayList<>()).sources()).hasSize(2);
        var events = new ArrayList<AuditEvent>();
        assertThatThrownBy(() -> call(tree(1), 3, 10000, names, false, events))
                .isInstanceOf(PrivacyRefusedException.class);
        assertThat(events).singleElement().satisfies(e -> assertThat(e.policyDecision()).isEqualTo("DENY:TOO_MANY_RECORDS"));
    }

    @Test void byteCapIsCombinedAcrossReturnedSources() {
        var names = List.of("alpha", "beta");
        var response = call(tree(1), 4, 10000, names, false, new ArrayList<>());
        int bytes = MAPPER.writeValueAsString(response).getBytes(StandardCharsets.UTF_8).length;
        assertThat(call(tree(1), 4, bytes, names, false, new ArrayList<>()).sources()).hasSize(2);
        var events = new ArrayList<AuditEvent>();
        assertThatThrownBy(() -> call(tree(1), 4, bytes - 1, names, false, events))
                .isInstanceOf(PrivacyRefusedException.class);
        assertThat(events).singleElement().satisfies(e -> assertThat(e.policyDecision()).isEqualTo("DENY:RESPONSE_TOO_LARGE"));
    }

    @Test void validationWithheldPayloadDoesNotConsumeEitherLimit() {
        var names = List.of("alpha", "withheld");
        var response = call(tree(0), 1, 10000, names, true, new ArrayList<>());
        int bytes = MAPPER.writeValueAsString(response).getBytes(StandardCharsets.UTF_8).length;
        var events = new ArrayList<AuditEvent>();
        assertThat(call(tree(0), 1, bytes, names, true, events).sources().values())
                .extracting(SourceResult::status).containsExactlyInAnyOrder("ANSWERED", "REFUSED");
        assertThat(events).singleElement().satisfies(e -> assertThat(e.policyDecision()).isEqualTo("ALLOW"));
    }

}
