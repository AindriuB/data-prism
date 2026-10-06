package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome;
import io.github.aindriub.dataprism.reidentification.ReidentificationRequest;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Re-identification request, approval and collection for the authenticated operator.
 *
 * <p>The service does the authorisation, the four-eyes rule and its own audit of the outcome
 * (tool {@code reidentify}). Each endpoint also writes its {@code operator:<action>} event first,
 * with the decision {@code ALLOW:FORWARDED}: that the operator asked, not what the service decided.
 * If that event cannot be written the service is not called. A refusal is audited exactly once, by
 * the service, so an endpoint never writes a second {@code DENY} for it.
 *
 * <p>The subject id appears only in the response to the requester when the outcome is
 * {@code RESOLVED}: a collect, or a request with four-eyes off. Errors carry the code alone.
 */
@RestController
@ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
final class ReidentificationOperatorController {

    private final OperatorCallers callers;
    private final OperatorAudit audit;
    private final ObjectProvider<ReidentificationService> service;

    ReidentificationOperatorController(OperatorCallers callers, OperatorAudit audit,
                                       ObjectProvider<ReidentificationService> service) {
        this.callers = callers;
        this.audit = audit;
        this.service = service;
    }

    record ReidentificationBody(String scopeId, String namespace, String syntheticValue, String purpose,
                                String caseId) { }

    @PostMapping("/operator/reidentifications")
    ResponseEntity<Map<String, String>> request(@RequestBody ReidentificationBody body) {
        AuthenticatedCaller caller = caller();
        ReidentificationService reidentification = service(caller, "reidentify-request");
        ReidentificationRequest request = parse(caller, body);
        audit.record(caller, "reidentify-request", request.namespace().name(), request.scopeId(),
                "ALLOW:FORWARDED", "", "");
        return respond(reidentification.request(caller, request));
    }

    @PostMapping("/operator/reidentifications/{id}/approve")
    ResponseEntity<Map<String, String>> approve(@PathVariable("id") String id) {
        AuthenticatedCaller caller = caller();
        ReidentificationService reidentification = service(caller, "reidentify-approve");
        audit.record(caller, "reidentify-approve", "", "", "ALLOW:FORWARDED", id, caller.principalId());
        return respond(reidentification.approve(caller, id));
    }

    @GetMapping("/operator/reidentifications/{id}")
    ResponseEntity<Map<String, String>> collect(@PathVariable("id") String id) {
        AuthenticatedCaller caller = caller();
        ReidentificationService reidentification = service(caller, "reidentify-collect");
        audit.record(caller, "reidentify-collect", "", "", "ALLOW:FORWARDED", id, "");
        return respond(reidentification.collect(caller, id));
    }

    private AuthenticatedCaller caller() {
        return callers.current().orElseThrow(
                () -> new OperatorException(HttpStatus.UNAUTHORIZED, "OPERATOR_IDENTITY_UNAVAILABLE"));
    }

    private ReidentificationService service(AuthenticatedCaller caller, String action) {
        ReidentificationService found = service.getIfAvailable();
        if (found == null) {
            audit.recordDenial(caller, action, "", "", "REIDENTIFICATION_DISABLED", "");
            throw new OperatorException(HttpStatus.NOT_FOUND, "REIDENTIFICATION_DISABLED");
        }
        return found;
    }

    private ReidentificationRequest parse(AuthenticatedCaller caller, ReidentificationBody body) {
        try {
            if (body == null || body.scopeId() == null || body.scopeId().isBlank() || body.namespace() == null
                    || body.syntheticValue() == null || body.syntheticValue().isBlank()) {
                throw new IllegalArgumentException();
            }
            return new ReidentificationRequest(body.scopeId(), PrivacyNamespace.valueOf(body.namespace()),
                    body.syntheticValue(), body.purpose(), body.caseId());
        } catch (IllegalArgumentException invalid) {
            // Nothing from the body is echoed or logged: the message could carry the synthetic value.
            audit.recordDenial(caller, "reidentify-request", "", "", "INVALID_REQUEST", "");
            throw new OperatorException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
    }

    private static ResponseEntity<Map<String, String>> respond(ReidentificationOutcome outcome) {
        return switch (outcome) {
            case ReidentificationOutcome.Resolved resolved -> ResponseEntity.ok(
                    Map.of("status", "RESOLVED", "subjectId", resolved.subjectId()));
            case ReidentificationOutcome.PendingApproval pending -> ResponseEntity.accepted().body(
                    Map.of("status", "PENDING_APPROVAL", "approvalId", pending.approvalId()));
            case ReidentificationOutcome.Approved approved -> ResponseEntity.ok(Map.of("status", "APPROVED"));
            case ReidentificationOutcome.Refused refused -> ResponseEntity.status(statusOf(refused.code()))
                    .body(Map.of("code", refused.code()));
        };
    }

    private static HttpStatus statusOf(String code) {
        return switch (code) {
            case "REIDENTIFICATION_NOT_PERMITTED", "NOT_REQUESTER" -> HttpStatus.FORBIDDEN;
            case "PURPOSE_REQUIRED", "PURPOSE_NOT_ALLOWED" -> HttpStatus.BAD_REQUEST;
            case "REIDENTIFICATION_NOT_FOUND", "APPROVAL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SELF_APPROVAL", "APPROVAL_EXPIRED", "APPROVAL_NOT_PENDING", "APPROVAL_NOT_APPROVED" ->
                    HttpStatus.CONFLICT;
            case "TOO_MANY_PENDING" -> HttpStatus.TOO_MANY_REQUESTS;
            case "REIDENTIFICATION_UNAVAILABLE", "AUDIT_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
