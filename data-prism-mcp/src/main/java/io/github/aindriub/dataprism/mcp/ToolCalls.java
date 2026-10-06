package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.RefusalCodes;
import io.github.aindriub.dataprism.orchestration.AuditedRefusalException;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.security.AdmissionDecision;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.ReservedArguments;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What both tools share: the admission step, the rejected-argument names and the result {@code _meta}. */
final class ToolCalls {

    /** The {@code _meta} key every tool result carries its call's correlation id under. */
    static final String CORRELATION_ID_META_KEY = "io.github.aindriub.dataprism/correlationId";

    /** Refusal code when admission itself could not be evaluated. */
    static final String OVERSIGHT_UNAVAILABLE = "OVERSIGHT_UNAVAILABLE";

    /**
     * Approval fields a caller must never be able to set. They are not part of
     * either tool's input schema and are never read from the arguments; their
     * names are only noted so the attempt is audited.
     */
    private static final Set<String> APPROVAL_ARGUMENTS = Set.of("approvalId", "approverId");

    private ToolCalls() {
    }

    static Set<String> rejectedArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>(ReservedArguments.rejected(arguments.keySet()));
        for (String name : arguments.keySet()) {
            if (APPROVAL_ARGUMENTS.contains(name)) {
                out.add(name);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Admission before the orchestrator. Any failure to evaluate it, including
     * one while fingerprinting the binding, refuses the call.
     */
    static AdmissionDecision admit(ToolAdmission admission, ParameterFingerprinter fingerprinter,
                                   AuthenticatedCaller caller, String tool, PrivacyContext context,
                                   String bindingValue) {
        try {
            String binding = fingerprinter == null ? "" : fingerprinter.fingerprint(bindingValue, context);
            AdmissionDecision decision = admission.admit(caller, tool, context.scopeId(), binding);
            if (decision == null || (!decision.admitted() && (decision.code() == null || decision.code().isBlank()))) {
                return AdmissionDecision.refuse(OVERSIGHT_UNAVAILABLE);
            }
            return decision;
        } catch (RuntimeException e) {
            return AdmissionDecision.refuse(OVERSIGHT_UNAVAILABLE);
        }
    }

    /**
     * The audited decision for a refusal: {@code DENY:<code>}, the form the
     * orchestrator and the re-identification service also write. A code that
     * is not a plain upper-case token is never copied into the audit file.
     */
    static String denyDecision(String code) {
        return "DENY:" + RefusalCodes.sanitise(code);
    }

    /**
     * The code, plus the approval id for the two codes a caller can act on. A code
     * that is not an upper-case token is replaced, as in {@link #denyDecision}.
     */
    static String refusalText(String code, String approvalId) {
        boolean carriesApproval = ("APPROVAL_REQUIRED".equals(code) || "APPROVAL_PENDING".equals(code))
                && approvalId != null && !approvalId.isBlank();
        return carriesApproval ? code + " approvalId=" + approvalId : RefusalCodes.sanitise(code);
    }

    /**
     * What an approval is bound to: the entity and subject, and everything in
     * the call that decides what the orchestrator will do with them. Each
     * {@link InvestigationContext} field is accounted for:
     * <ul>
     *   <li>{@code principalId}: bound by {@code ToolAdmission}, which keys
     *       every approval on the caller, scope and tool.</li>
     *   <li>{@code clientId}: bound here.</li>
     *   <li>{@code capabilities}: bound here, sorted. They change the output
     *       (for example real source names under {@code EXPOSE_SOURCE_NAMES}),
     *       so a changed set is a different call.</li>
     *   <li>{@code caseId}: bound by {@code ToolAdmission} through scopeId
     *       {@code case:<caseId>}; it also keys the binding HMAC and
     *       pseudonymisation.</li>
     * </ul>
     * From the privacy context: purpose and redaction profile are bound; the
     * scope is bound by {@code ToolAdmission}. Length-prefixed, so no
     * caller-chosen value can shift a boundary into another field.
     */
    static String binding(String entityType, String subjectId, PrivacyContext context,
                          InvestigationContext investigation) {
        StringBuilder out = new StringBuilder();
        for (String part : new String[] {entityType, subjectId, context.purpose(),
                context.redactionProfile(), investigation.clientId()}) {
            append(out, part);
        }
        List<String> capabilities = new ArrayList<>(investigation.capabilities());
        Collections.sort(capabilities);
        out.append(capabilities.size()).append('#');
        for (String capability : capabilities) {
            append(out, capability);
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String part) {
        String value = part == null ? "" : part;
        out.append(value.length()).append(':').append(value).append('\u0000');
    }

    /** An orchestrator refusal that was already audited: its text, with that audit event's id. */
    static McpSchema.CallToolResult refused(AuditedRefusalException refused) {
        String text = AuditedRefusalException.REQUEST_FAILED.equals(refused.code())
                ? "the request could not be completed"
                : "refused: " + RefusalCodes.sanitise(refused.code()) + " at " + refused.path();
        return withCorrelation(McpSchema.CallToolResult.builder().isError(true).addTextContent(text),
                refused.correlationId()).build();
    }

    /** {@code result} with {@code response}'s correlation id, when the orchestrator had already audited the call. */
    static McpSchema.CallToolResult audited(McpSchema.CallToolResult result, ContextResponse response) {
        if (response == null || response.correlationId() == null) {
            return result;
        }
        return withCorrelation(McpSchema.CallToolResult.builder().isError(result.isError())
                .content(result.content()), response.correlationId()).build();
    }

    static McpSchema.CallToolResult.Builder withCorrelation(McpSchema.CallToolResult.Builder builder,
                                                           String correlationId) {
        return builder.meta(Map.of(CORRELATION_ID_META_KEY, correlationId));
    }
}
