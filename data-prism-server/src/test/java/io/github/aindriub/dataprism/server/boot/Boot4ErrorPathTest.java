package io.github.aindriub.dataprism.server.boot;

import io.github.aindriub.dataprism.server.operator.OperatorHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Task 142: {@code spring.web.error.path} (renamed from {@code server.error.path} in Boot 4) is honoured. */
class Boot4ErrorPathTest {

    @TempDir
    Path tempDir;

    @Test
    void aCustomErrorPathMovesBothPortsErrorHandling() throws Exception {
        try (OperatorHarness server = Boot4Servers.start(tempDir, "--spring.web.error.path=/custom-error",
                "--boot4.guard.failing-controller=true")) {
            // The same bodies as on the default path can only arrive if the container dispatches the
            // error to /custom-error and the controllers answer there.
            Boot4RegressionGuardsTest.assertMcpPortBootBodyAndOperatorCodeBody(server);
        }
    }
}
