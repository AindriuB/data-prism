package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.security.SecurityRefusedException;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wiring at {@code ExampleApplication}'s only stdio call site: the
 * {@code productionDeployment} argument now comes from the active Spring
 * profile rather than a hardcoded {@code false}.
 *
 * <p>{@code DataPrismMcpServer.stdio}'s own refusal is already covered by
 * {@code DataPrismMcpServerTest}; this class exists to prove the call site
 * actually passes it a value that changes with the profile, in both
 * directions.
 */
class StdioProductionProfileTest {

    @Test
    @DisplayName("stdio refuses to start when the production profile is active")
    void refusesUnderProductionProfile() {
        assertThatThrownBy(() -> ExampleApplication.buildServer("production"))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo("STDIO_DEVELOPMENT_ONLY"));
    }

    @Test
    @DisplayName("stdio refuses when production is one of several active profiles")
    void refusesWhenProductionIsAmongSeveralProfiles() {
        assertThatThrownBy(() -> ExampleApplication.buildServer("local,production"))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo("STDIO_DEVELOPMENT_ONLY"));
    }

    @Test
    @DisplayName("stdio starts when a development profile is active")
    void startsUnderDevelopmentProfile() {
        McpSyncServer server = ExampleApplication.buildServer("development");
        try {
            assertThat(server.listTools()).isNotEmpty();
        } finally {
            server.closeGracefully();
        }
    }

    @Test
    @DisplayName("stdio starts when no profile is active at all")
    void startsWithNoActiveProfile() {
        McpSyncServer server = ExampleApplication.buildServer(null);
        try {
            assertThat(server.listTools()).isNotEmpty();
        } finally {
            server.closeGracefully();
        }
    }
}
