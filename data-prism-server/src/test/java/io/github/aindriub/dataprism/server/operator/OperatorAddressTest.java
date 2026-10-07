package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.server.DataPrismServerApplication;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 105: the operator connector's bind address is its own setting; and Boot's Hazelcast is excluded. */
class OperatorAddressTest {

    private static String addressOf(OperatorHarness app, int port) {
        TomcatWebServer tomcat = (TomcatWebServer) ((ServletWebServerApplicationContext) app.context)
                .getWebServer();
        return Arrays.stream(tomcat.getTomcat().getService().findConnectors())
                .filter(c -> c.getLocalPort() == port).map(c -> String.valueOf(c.getProperty("address")).replaceFirst("^/", ""))
                .findFirst().orElseThrow();
    }

    @Test
    void theOperatorConnectorBindsToItsOwnAddressIndependentlyOfServerAddress(@TempDir Path tempDir)
            throws Exception {
        try (OperatorHarness app = OperatorHarness.start(tempDir, false, "--server.address=0.0.0.0",
                "--dataprism.operator.address=127.0.0.1")) {
            assertThat(addressOf(app, app.operatorPort)).isEqualTo("127.0.0.1");
            assertThat(addressOf(app, app.mcpPort)).isNotEqualTo("127.0.0.1");
            assertThat(app.operator("GET", "/operator/state", app.operatorToken("operator-addr"), null)
                    .statusCode()).isEqualTo(200);
        }
    }

    @Test
    void withoutAnOperatorAddressTheConnectorFollowsServerAddress(@TempDir Path tempDir) throws Exception {
        try (OperatorHarness app = OperatorHarness.start(tempDir, false, "--server.address=127.0.0.1")) {
            assertThat(addressOf(app, app.operatorPort)).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void bootsOwnHazelcastAutoConfigurationIsExcluded() {
        assertThat(DataPrismServerApplication.class.getAnnotation(SpringBootApplication.class).excludeName())
                .contains("org.springframework.boot.hazelcast.autoconfigure.HazelcastAutoConfiguration");
    }
}
