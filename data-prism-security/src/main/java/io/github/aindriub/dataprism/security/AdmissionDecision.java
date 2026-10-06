package io.github.aindriub.dataprism.security;

/** Whether a call may proceed. {@code code} is {@code null} when admitted. */
public record AdmissionDecision(boolean admitted, String code, String approvalId, String approverPrincipalId) {

    public static AdmissionDecision admit() {
        return new AdmissionDecision(true, null, null, null);
    }

    public static AdmissionDecision refuse(String code) {
        return new AdmissionDecision(false, code, null, null);
    }
}
