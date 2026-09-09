package io.github.aindriub.dataprism.security;

/**
 * One MCP tool call, reduced to the one fact authorisation needs: which
 * capability it requires. See {@code io.github.aindriub.dataprism.core.Capability}.
 */
public record ToolInvocation(String toolName, String requiredCapability) {

    public ToolInvocation {
        requireNonBlank(toolName, "toolName");
        requireNonBlank(requiredCapability, "requiredCapability");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
