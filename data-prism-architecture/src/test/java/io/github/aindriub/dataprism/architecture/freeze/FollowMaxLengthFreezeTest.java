package io.github.aindriub.dataprism.architecture.freeze;

import io.github.aindriub.dataprism.spring.boot.LinksProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code follow} tool's {@code link} {@code maxLength} in the golden {@code tools/list} is the configured
 * default, {@code dataprism.links.max-length}.
 *
 * <p>This lives here, not in {@code ToolsListGoldenTest}, because the default is {@link LinksProperties}' and the
 * mcp module cannot see the autoconfigure module; the mcp test builds its server from an mcp constant, which would
 * pass if the property default drifted.
 */
class FollowMaxLengthFreezeTest {

    private static final String GOLDEN =
            "data-prism-mcp/src/test/resources/io/github/aindriub/dataprism/mcp/golden/tools-list.json";

    @Test
    void theGoldenFollowMaxLengthIsTheLinksPropertiesDefault() throws IOException {
        Path golden = MainSources.repositoryRoot().resolve(GOLDEN);
        JsonNode follow = null;
        for (JsonNode tool : new ObjectMapper().readTree(Files.readString(golden)).get("tools")) {
            if (tool.get("name").asString().equals("follow")) {
                follow = tool;
            }
        }
        assertThat(follow).as("follow is in " + GOLDEN).isNotNull();
        assertThat(follow.at("/inputSchema/properties/link/maxLength").asInt())
                .isEqualTo(new LinksProperties().maxLengthValue());
    }
}
