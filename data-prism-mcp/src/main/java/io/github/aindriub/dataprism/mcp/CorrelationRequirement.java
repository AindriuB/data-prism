package io.github.aindriub.dataprism.mcp;

/**
 * Whether a tool call must carry a valid external correlation id in its transport context.
 *
 * <p>Under {@link #REQUIRED} a call whose id is absent or rejected is refused and audited before
 * any source is called. Under {@link #OPTIONAL} such a call proceeds as if no id had been sent.
 */
public enum CorrelationRequirement {
    OPTIONAL,
    REQUIRED
}
