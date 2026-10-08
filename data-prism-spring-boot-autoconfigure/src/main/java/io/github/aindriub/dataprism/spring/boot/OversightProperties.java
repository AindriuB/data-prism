package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** {@code dataprism.oversight.*}: which tools need human approval, and the per-caller request limit. */
public class OversightProperties {
    private List<String> approvalRequiredTools = new java.util.ArrayList<>();
    private Duration approvalTtl = Duration.ofMinutes(15);
    private final CallerRateLimit callerRateLimit = new CallerRateLimit();
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
        private Integer requests;
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
