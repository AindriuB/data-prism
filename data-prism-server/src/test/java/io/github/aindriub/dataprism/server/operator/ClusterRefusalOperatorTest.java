package io.github.aindriub.dataprism.server.operator;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.reidentification.ReidentificationPolicy;
import io.github.aindriub.dataprism.reidentification.Permission;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A cluster that refuses an operation, or times out on a lock, reaches the operator as that code with a 503, like
 * every other path. Any other store failure is still {@code OVERSIGHT_UNAVAILABLE}.
 */
class ClusterRefusalOperatorTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller OPERATOR = new AuthenticatedCaller("op-1", "operator-console",
            Set.of("approver"), "operations", "case-1", null);
    private static final AuthenticatedCaller REQUESTER = new AuthenticatedCaller("req-1", "operator-console",
            Set.of("requester"), "fraud-review", "case-1", null);
    private static final String SENTINEL = "sentinel-key-x-1";

    private final List<AuditEvent> audited = new ArrayList<>();
    private boolean auditDown;
    private final OperatorAudit audit = new OperatorAudit(new AuditRecorder(event -> {
        if (auditDown) throw new IllegalStateException("audit down");
        audited.add(event);
    }, CLOCK, "operator-test"));
    private final InMemoryApprovalStore real = new InMemoryApprovalStore();
    private final OversightState state = new InMemoryOversightState();

    private static List<PrivacyRefusedException> clusterFailures() {
        return List.of(new ClusterOperationRefusedException(new IllegalArgumentException(SENTINEL)),
                new ClusterLockTimeoutException());
    }

    private ApprovalStore storeThrowingOn(String method, RuntimeException failure) {
        return (ApprovalStore) Proxy.newProxyInstance(ApprovalStore.class.getClassLoader(),
                new Class<?>[] {ApprovalStore.class}, (p, m, args) -> {
                    if (m.getName().equals(method)) {
                        throw failure;
                    }
                    try {
                        return m.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private OversightOperatorController oversight(ApprovalStore store) {
        return new OversightOperatorController(() -> Optional.of(OPERATOR), audit, state, store, CLOCK);
    }

    private void pending(String id) {
        real.create(new ApprovalRequest(id, Kind.TOOL_CALL, "requester-1", "client-1", "scope-a", "search",
                "fp-" + id, null, null, "operations", "case-1", CLOCK.instant(),
                CLOCK.instant().plus(Duration.ofHours(1)), Status.PENDING, null, null));
    }

    private static String bodyOf(OperatorException e) throws Exception {
        ResponseEntity<Map<String, String>> response = new OperatorErrorAdvice().refused(e);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        return JSON.writeValueAsString(response.getBody());
    }

    private void assertDenied(String code) {
        assertThat(audited).last().satisfies(event -> {
            assertThat(event.policyDecision()).isEqualTo("DENY:" + code);
            assertThat(event.toString()).doesNotContain(SENTINEL);
        });
    }

    @Test
    void approveAndRejectAnswerTheClusterCodeWith503AndAuditThatDenial() throws Exception {
        for (PrivacyRefusedException failure : clusterFailures()) {
            for (String method : new String[] {"approve", "reject"}) {
                pending("a-" + method + "-" + failure.code());
                String id = "a-" + method + "-" + failure.code();
                var controller = oversight(storeThrowingOn(method, failure));
                OperatorException thrown = catchThrowableOfType(OperatorException.class, () -> {
                    if (method.equals("approve")) controller.approve(id);
                    else controller.reject(id);
                });
                assertThat(thrown).as(method).isNotNull();
                assertThat(bodyOf(thrown)).isEqualTo("{\"code\":\"" + failure.code() + "\"}");
                assertDenied(failure.code());
            }
        }
    }

    @Test
    void aClusterRefusalWhileFindingTheApprovalIsAnswered503WithItsCode() throws Exception {
        for (PrivacyRefusedException failure : clusterFailures()) {
            pending("f-" + failure.code());
            var controller = oversight(storeThrowingOn("find", failure));
            OperatorException thrown = catchThrowableOfType(OperatorException.class,
                    () -> controller.approve("f-" + failure.code()));
            assertThat(bodyOf(thrown)).isEqualTo("{\"code\":\"" + failure.code() + "\"}");
            assertDenied(failure.code());
        }
    }

    @Test
    void aClusterRefusalFromTheStateStoreIsAnswered503WithItsCode() throws Exception {
        for (PrivacyRefusedException failure : clusterFailures()) {
            OversightState refusing = (OversightState) Proxy.newProxyInstance(
                    OversightState.class.getClassLoader(), new Class<?>[] {OversightState.class}, (p, m, a) -> {
                        throw failure;
                    });
            var controller = new OversightOperatorController(() -> Optional.of(OPERATOR), audit, refusing, real, CLOCK);
            OperatorException thrown = catchThrowableOfType(OperatorException.class,
                    () -> controller.pause(new OversightOperatorController.PauseRequest("ALL", "")));
            assertThat(bodyOf(thrown)).isEqualTo("{\"code\":\"" + failure.code() + "\"}");
            assertDenied(failure.code());
        }
    }

    @Test
    void anyOtherStoreFailureIsStillOversightUnavailable() throws Exception {
        pending("o-1");
        var controller = oversight(storeThrowingOn("approve", new IllegalStateException(SENTINEL)));
        OperatorException thrown = catchThrowableOfType(OperatorException.class, () -> controller.approve("o-1"));
        assertThat(bodyOf(thrown)).isEqualTo("{\"code\":\"OVERSIGHT_UNAVAILABLE\"}");
        assertDenied("OVERSIGHT_UNAVAILABLE");
    }

    @Test
    void aClusterRefusalStaysARefusalWhenTheDenialCannotBeAudited() throws Exception {
        pending("d-1");
        auditDown = true;
        var controller = oversight(storeThrowingOn("approve",
                new ClusterOperationRefusedException(new IllegalArgumentException(SENTINEL))));
        OperatorException thrown = catchThrowableOfType(OperatorException.class, () -> controller.approve("d-1"));
        assertThat(bodyOf(thrown)).isEqualTo("{\"code\":\"CLUSTER_OPERATION_REFUSED\"}");
        assertThat(real.find("d-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.PENDING);
    }

    // ----- re-identification endpoints

    private ReidentificationOperatorController reidentification(ApprovalStore store, boolean fourEyes) {
        ReidentificationPolicy policy = new ReidentificationPolicy(Set.of("fraud-review"),
                Map.of("requester", Set.of(Permission.REQUEST), "approver", Set.of(Permission.APPROVE)),
                fourEyes, Duration.ofMinutes(10), 5);
        var service = new ReidentificationService((scope, ns, synth) -> Optional.of("subject-1"), store,
                new AuditRecorder(audited::add, CLOCK, "operator-test"), policy, CLOCK);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("service", service);
        return new ReidentificationOperatorController(() -> Optional.of(REQUESTER), audit,
                beans.getBeanProvider(ReidentificationService.class));
    }

    private static ReidentificationOperatorController.ReidentificationBody body() {
        return new ReidentificationOperatorController.ReidentificationBody("case:case-1",
                PrivacyNamespace.PERSON_NAME.name(), "Synthetic Person 1", "fraud-review", "case-1");
    }

    @Test
    void aReidentificationRequestOverAClusterRefusingStoreIsAnswered503WithTheCode() throws Exception {
        for (PrivacyRefusedException failure : clusterFailures()) {
            ResponseEntity<Map<String, String>> response =
                    reidentification(storeThrowingOn("create", failure), true).request(body());
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(JSON.writeValueAsString(response.getBody())).isEqualTo("{\"code\":\"" + failure.code() + "\"}");
            assertDenied(failure.code());
        }
    }

    @Test
    void aReidentificationOverAnyOtherFailureIsStill503ReidentificationUnavailable() throws Exception {
        ResponseEntity<Map<String, String>> response = reidentification(
                storeThrowingOn("create", new IllegalStateException(SENTINEL)), true).request(body());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(JSON.writeValueAsString(response.getBody())).isEqualTo("{\"code\":\"REIDENTIFICATION_UNAVAILABLE\"}");
    }
}
