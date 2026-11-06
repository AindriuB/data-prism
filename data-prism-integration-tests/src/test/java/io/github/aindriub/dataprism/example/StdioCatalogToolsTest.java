package io.github.aindriub.dataprism.example;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The stdio launcher serves {@code list_sources} and {@code describe_source} beside the two entity tools. */
class StdioCatalogToolsTest {

    @Test
    @DisplayName("the stdio server lists the entity tools and both catalog tools")
    void stdioListsTheCatalogTools() {
        McpSyncServer server = ExampleApplication.buildServer(null);
        try {
            assertThat(server.listTools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("get_entity_context", "compare_entity_sources", "list_sources",
                            "describe_source");
        } finally {
            server.closeGracefully();
        }
    }
}
