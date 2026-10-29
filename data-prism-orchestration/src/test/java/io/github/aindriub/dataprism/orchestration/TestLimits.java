package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.limits.RequestLimits;

import java.time.Duration;

/**
 * Request limits for a test: {@link RequestLimits#DEFAULT} with only what the test is about changed, so a
 * new limit does not touch every test that builds one.
 */
final class TestLimits {

    private TestLimits() {
    }

    static Builder defaults() {
        return new Builder();
    }

    static final class Builder {
        private int maxSources = RequestLimits.DEFAULT.maxSources();
        private int maxRecords = RequestLimits.DEFAULT.maxRecords();
        private int maxResponseBytes = RequestLimits.DEFAULT.maxResponseBytes();
        private Duration perSourceTimeout = RequestLimits.DEFAULT.perSourceTimeout();
        private int maxConcurrency = RequestLimits.DEFAULT.maxConcurrency();
        private int scopeReadBudget = RequestLimits.DEFAULT.scopeReadBudget();
        private Duration requestTimeout = RequestLimits.DEFAULT.requestTimeout();
        private int scopeTotalReadBudget = RequestLimits.DEFAULT.scopeTotalReadBudget();

        Builder maxSources(int v) {
            maxSources = v;
            return this;
        }

        Builder maxRecords(int v) {
            maxRecords = v;
            return this;
        }

        Builder maxResponseBytes(int v) {
            maxResponseBytes = v;
            return this;
        }

        Builder perSourceTimeout(Duration v) {
            perSourceTimeout = v;
            return this;
        }

        Builder maxConcurrency(int v) {
            maxConcurrency = v;
            return this;
        }

        Builder scopeReadBudget(int v) {
            scopeReadBudget = v;
            return this;
        }

        Builder requestTimeout(Duration v) {
            requestTimeout = v;
            return this;
        }

        Builder scopeTotalReadBudget(int v) {
            scopeTotalReadBudget = v;
            return this;
        }

        RequestLimits build() {
            return new RequestLimits(maxSources, maxRecords, maxResponseBytes, perSourceTimeout, maxConcurrency,
                    scopeReadBudget, requestTimeout, scopeTotalReadBudget);
        }
    }
}
