package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.security.AdmissionDecision;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.ReservedArguments;
import io.github.aindriub.dataprism.security.ToolAdmission;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashSet;
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

    /** The code, plus the approval id for the two codes a caller can act on. */
    static String refusalText(String code, String approvalId) {
        boolean carriesApproval = ("APPROVAL_REQUIRED".equals(code) || "APPROVAL_PENDING".equals(code))
                && approvalId != null && !approvalId.isBlank();
        return carriesApproval ? code + " approvalId=" + approvalId : code;
    }

    static McpSchema.CallToolResult.Builder withCorrelation(McpSchema.CallToolResult.Builder builder,
                                                           String correlationId) {
        return builder.meta(Map.of(CORRELATION_ID_META_KEY, correlationId));
    }
}
