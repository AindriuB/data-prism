package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** {@code dataprism.oversight.*}: which tools need human approval, and the per-caller request limit. */
public class OversightProperties {
    /** Tools whose calls need a human approval first: get_entity_context and compare_entity_sources only. Empty by default. */
    private List<String> approvalRequiredTools = new java.util.ArrayList<>();
    /** How long an approval request stays usable. Defaults to 15 minutes. */
    private Duration approvalTtl = Duration.ofMinutes(15);
    private final CallerRateLimit callerRateLimit = new CallerRateLimit();
    /** Live pending approvals one requester may hold. Defaults to 5. */
    private int maxPendingPerRequester = 5;

    public List<String> getApprovalRequiredTools() {
        return approvalRequiredTools;
    }

    public void setApprovalRequiredTools(List<String> v) {
        approvalRequiredTools = v == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
    }

    public Duration getApprovalTtl() {
        return approvalTtl;
    }

    public void setApprovalTtl(Duration v) {
        approvalTtl = v;
    }

    public CallerRateLimit getCallerRateLimit() {
        return callerRateLimit;
    }

    public int getMaxPendingPerRequester() {
        return maxPendingPerRequester;
    }

    public void setMaxPendingPerRequester(int v) {
        maxPendingPerRequester = v;
    }

    public static class CallerRateLimit {
        /** Requests one caller may make per window. Unset means no limit. */
        private Integer requests;
        /** The rate-limit window. Defaults to one minute. */
        private Duration window = Duration.ofMinutes(1);

        public Integer getRequests() {
            return requests;
        }

        public void setRequests(Integer v) {
            requests = v;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration v) {
            window = v;
        }
    }
}
