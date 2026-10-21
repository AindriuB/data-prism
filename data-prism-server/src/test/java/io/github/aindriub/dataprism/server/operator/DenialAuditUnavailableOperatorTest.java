package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.reidentification.Permission;
import io.github.aindriub.dataprism.reidentification.ReidentificationPolicy;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Every operator denial whose DENY event cannot be written is answered 503 {@code AUDIT_UNAVAILABLE} and counted.
 * The controllers are built directly with in-memory stores; one end-to-end case goes through the harness.
 */
class DenialAuditUnavailableOperatorTest {

    private static final String SENTINEL = "sink-secret-path-/var/audit/unwritable";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller OPERATOR = new AuthenticatedCaller("op-1", "operator-console",
            Set.of("approver"), "operations", "case-1", null);
    private static final AuthenticatedCaller REQUESTER = new AuthenticatedCaller("req-1", "agent-client",
            Set.of("requester"), "fraud-review", "case-1", null);
    private static final String TOOL = "search";

    private final List<AuditEvent> events = new ArrayList<>();
    private final Map<Metric, Integer> counts = new EnumMap<>(Metric.class);
    private boolean denyDown;
    private boolean storeDown;
    private final InMemoryApprovalStore real = new InMemoryApprovalStore();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final AuditRecorder recorder = new AuditRecorder(event -> {
        if (denyDown && event.policyDecision().startsWith("DENY")) {
            throw new IllegalStateException(SENTINEL);
        }
        events.add(event);
    }, CLOCK, "operator-test");
    private final OperatorAudit audit;

    DenialAuditUnavailableOperatorTest() {
        beans.registerSingleton("metrics", new PrivacyMetrics() {
            @Override public void increment(Metric metric) { counts.merge(metric, 1, Integer::sum); }
            @Override public void increment(Metric metric, String sourceName) { increment(metric); }
            @Override public void record(Metric metric, String sourceName, Duration duration) { }
        });
        audit = new OperatorAudit(recorder, beans.getBeanProvider(PrivacyMetrics.class));
    }

    private ApprovalStore flaky() {
        return (ApprovalStore) Proxy.newProxyInstance(ApprovalStore.class.getClassLoader(),
                new Class<?>[] {ApprovalStore.class}, (p, m, args) -> {
                    if (storeDown && (m.getName().equals("find") || m.getName().equals("reject"))) {
                        throw new IllegalStateException(SENTINEL);
                    }
                    try {
                        return m.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private OversightOperatorController oversight(OversightState state) {
        return new OversightOperatorController(() -> Optional.of(OPERATOR), audit, state, flaky(), CLOCK,
                beans.getBeanProvider(PrivacyMetrics.class));
    }

    private OversightOperatorController oversight() {
        return oversight(new InMemoryOversightState());
    }

    private String pendingToolApproval() {
        var admission = new ToolAdmission(new InMemoryOversightState(), real, new InMemoryCallerRateLimiter(),
                new OversightPolicy(Set.of(TOOL), OptionalInt.empty(), Duration.ofMinutes(1), Duration.ofHours(1)),
                CLOCK);
        return admission.admit(REQUESTER, TOOL, "scope-a", "fp-1").approvalId();
    }

    private void assertUnavailable(Supplier<?> call, String endpoint) {
        counts.clear();
        OperatorException thrown = catchThrowableOfType(OperatorException.class, call::get);
        assertThat(thrown).as(endpoint).isNotNull();
        ResponseEntity<Map<String, String>> response = new OperatorErrorAdvice().refused(thrown);
        assertThat(response.getStatusCode().value()).as(endpoint).isEqualTo(503);
        assertThat(response.getBody()).as(endpoint).isEqualTo(Map.of("code", "AUDIT_UNAVAILABLE"));
        assertThat(thrown.toString()).as(endpoint).doesNotContain(SENTINEL);
        assertThat(counts).as(endpoint).containsEntry(Metric.DENIAL_AUDIT_UNAVAILABLE, 1);
    }

    private void assertOwn(Supplier<?> call, int status, String code, String endpoint) {
        counts.clear();
        OperatorException thrown = catchThrowableOfType(OperatorException.class, call::get);
        assertThat(thrown).as(endpoint).isNotNull();
        assertThat(thrown.status().value()).as(endpoint).isEqualTo(status);
        assertThat(thrown.code()).as(endpoint).isEqualTo(code);
        assertThat(events).as(endpoint).last().extracting(AuditEvent::policyDecision).isEqualTo("DENY:" + code);
        assertThat(counts).as(endpoint).doesNotContainKey(Metric.DENIAL_AUDIT_UNAVAILABLE);
    }

    // ---- oversight endpoints ---------------------------------------------------------------------

    @Test
    void invalidPauseAndResumeBodies() {
        denyDown = true;
        var invalid = new OversightOperatorController.PauseRequest("NOPE", "x");
        assertUnavailable(() -> oversight().pause(invalid), "pause");
        assertUnavailable(() -> oversight().resume(invalid), "resume");
    }

    @Test
    void pauseWhoseStateWriteFails() {
        denyDown = true;
        OversightState broken = (OversightState) Proxy.newProxyInstance(OversightState.class.getClassLoader(),
                new Class<?>[] {OversightState.class}, (p, m, args) -> {
                    throw new IllegalStateException(SENTINEL);
                });
        assertUnavailable(() -> oversight(broken).pause(new OversightOperatorController.PauseRequest("ALL", null)),
                "pause unavailable");
    }

    @Test
    void approvalViewUnknownAndUnreadable() {
        denyDown = true;
        assertUnavailable(() -> oversight().approval("no-such"), "approval not found");
        storeDown = true;
        assertUnavailable(() -> oversight().approval("no-such"), "approval unreadable");
    }

    @Test
    void approveUnknownUnreadableAndPrechecked() {
        String id = pendingToolApproval();
        denyDown = true;
        assertUnavailable(() -> oversight().approve("no-such"), "approve not found");
        storeDown = true;
        assertUnavailable(() -> oversight().approve(id), "approve unreadable");
        storeDown = false;
        real.approve(id, "someone-else", CLOCK.instant());
        assertUnavailable(() -> oversight().approve(id), "approve not pending");
    }

    @Test
    void rejectUnknownUnreadableAndRefused() {
        String id = pendingToolApproval();
        denyDown = true;
        assertUnavailable(() -> oversight().reject("no-such"), "reject not found");
        storeDown = true;
        assertUnavailable(() -> oversight().reject(id), "reject unreadable");
        storeDown = false;
        real.approve(id, "someone-else", CLOCK.instant());
        assertUnavailable(() -> oversight().reject(id), "reject not pending");
    }

    @Test
    void aDenialWhoseEventIsWrittenKeepsItsOwnCode() {
        assertOwn(() -> oversight().approve("no-such"), 404, "APPROVAL_NOT_FOUND", "approve");
        assertOwn(() -> oversight().reject("no-such"), 404, "APPROVAL_NOT_FOUND", "reject");
        assertOwn(() -> oversight().approval("no-such"), 404, "APPROVAL_NOT_FOUND", "approval");
        assertOwn(() -> oversight().pause(new OversightOperatorController.PauseRequest("NOPE", null)), 400,
                "INVALID_REQUEST", "pause");
    }

    // ---- re-identification endpoints -------------------------------------------------------------

    private ReidentificationOperatorController reidentification(ReidentificationService service) {
        var provider = new DefaultListableBeanFactory();
        if (service != null) {
            provider.registerSingleton("service", service);
        }
        return new ReidentificationOperatorController(() -> Optional.of(OPERATOR), audit,
                provider.getBeanProvider(ReidentificationService.class));
    }

    @Test
    void disabledAndInvalidReidentification() {
        denyDown = true;
        var body = new ReidentificationOperatorController.ReidentificationBody("scope-1", "PERSON_NAME",
                "Alex Murphy 7QF2", "fraud-investigation", "CASE-1");
        assertUnavailable(() -> reidentification(null).request(body), "reidentify-request disabled");
        assertUnavailable(() -> reidentification(null).approve("id"), "reidentify-approve disabled");
        assertUnavailable(() -> reidentification(null).collect("id"), "reidentify-collect disabled");
        var invalid = new ReidentificationOperatorController.ReidentificationBody("scope-1", "NOT_A_NAMESPACE",
                "x", "p", "c");
        assertUnavailable(() -> reidentification(service()).request(invalid), "reidentify-request invalid");
    }

    private ReidentificationService service() {
        Map<String, Set<Permission>> roles = Map.of("approver", Set.of(Permission.REQUEST, Permission.APPROVE));
        var policy = new ReidentificationPolicy(Set.of("fraud-investigation"), roles, true, Duration.ofMinutes(10), 5);
        return new ReidentificationService((scope, ns, synth) -> Optional.of("SUBJ-1"), flaky(), recorder, policy,
                CLOCK, beans.getBeanProvider(PrivacyMetrics.class).getObject());
    }

    @Test
    void aServiceRefusalWhoseDenyCannotBeWrittenReachesTheCallerAs503() {
        denyDown = true;
        // The forwarding ALLOW is written; the service's own refusal of an unknown approval is not.
        var controller = reidentification(service());
        counts.clear();
        var response = controller.approve("no-such-approval");
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "AUDIT_UNAVAILABLE"));
        assertThat(counts).containsEntry(Metric.DENIAL_AUDIT_UNAVAILABLE, 1);
    }

    // ---- end to end through the harness ----------------------------------------------------------

    @TempDir
    static Path tempDir;
    static OperatorHarness app;

    @BeforeAll
    static void start() throws Exception {
        app = OperatorHarness.start(tempDir, false);
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void overHttpADenialWhoseAuditFailsAnswers503AuditUnavailable() throws Exception {
        String token = app.operatorToken("operator-denial");
        HttpResponse<String> own = app.operator("POST", "/operator/pause", token, "{\"target\":\"NOPE\"}");
        assertThat(own.statusCode()).isEqualTo(400);
        app.failAudit = true;
        try {
            HttpResponse<String> pause = app.operator("POST", "/operator/pause", token, "{\"target\":\"NOPE\"}");
            HttpResponse<String> approve = app.operator("POST", "/operator/approvals/no-such/approve", token, null);
            for (var response : List.of(pause, approve)) {
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.body()).contains("AUDIT_UNAVAILABLE").doesNotContain("audit down");
            }
        } finally {
            app.failAudit = false;
        }
    }
}
