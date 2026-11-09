package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A server that fails to start must exit, not hang. The MCP SDK's HTTP transport used to start a
 * non-daemon session-sweeper thread when the transport bean was built, and never stopped it; a
 * startup failure after that point (a port already bound, a refusal raised once singletons exist)
 * then logged "APPLICATION FAILED TO START" and left the JVM up with no port and no exit code, so
 * a supervisor that restarts on exit never fired. Data Prism turns the sweeper off.
 */
class FailedStartExitsTest {

    private static final long EXIT_BOUND_SECONDS = 60;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void aPortAlreadyInUseMakesTheServerProcessExitNonZero() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertChildExitsNonZero("port", String.valueOf(taken.getLocalPort()));
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void aRefusalRaisedAfterTheTransportIsBuiltMakesTheServerProcessExitNonZero() throws Exception {
        assertChildExitsNonZero("limits", "0");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void closingTheServerLeavesNoSessionSweeperThreadAlive() throws Exception {
        try (ConfigurableApplicationContext context = start("0", null)) {
            assertThat(context.isRunning()).isTrue();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!sweeperThreads().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(sweeperThreads()).as("session sweeper threads alive after the context closed").isEmpty();
    }

    private static List<String> sweeperThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.contains("session-sweeper"))
                .toList();
    }

    private static void assertChildExitsNonZero(String mode, String port) throws Exception {
        Path log = Files.createTempFile("failed-start-child-", ".log");
        Process child = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Child.class.getName());
            command.add(mode);
            command.add(port);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            child = builder.start();
            boolean exited = child.waitFor(EXIT_BOUND_SECONDS, TimeUnit.SECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertThat(exited).as("child did not exit within %ds after a failed start: %s",
                    EXIT_BOUND_SECONDS, output).isTrue();
            assertThat(child.exitValue()).as(output).isNotZero();
            assertThat(output).contains("APPLICATION FAILED TO START");
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }

    /** The server's own start, with the reviewed beans a real deployment would supply. */
    public static final class Child {
        public static void main(String[] args) {
            start(args[1], "limits".equals(args[0]) ? "hiddenLimits" : null);
        }
    }

    private static ConfigurableApplicationContext start(String port, String hiddenLimitsBeanName) {
        return new SpringApplicationBuilder(DataPrismServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .logStartupInfo(false)
                .initializers(context -> {
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("testKeys", (HmacKeyReferenceResolver) (keyId, reference) ->
                            "task-17-test-only-key-material-longer-than-thirty-two-bytes"
                                    .getBytes(StandardCharsets.UTF_8));
                    beans.registerSingleton("testAudit", (AuditSink) event -> { });
                    beans.registerSingleton("testMetrics", PrivacyMetrics.none());
                    beans.registerSingleton("testIdentityResolver", (IdentityResolver) new PassThroughIdentityResolver());
                    beans.registerSingleton("testCustomerAdapter", adapter());
                    if (hiddenLimitsBeanName != null) {
                        ((BeanDefinitionRegistry) beans).registerBeanDefinition(hiddenLimitsBeanName,
                                new RootBeanDefinition(Object.class, () -> RequestLimits.DEFAULT));
                    }
                })
                .run(configuration(port));
    }

    private static DataSourceAdapter<TestPayload> adapter() {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return "customer"; }
            @Override public Class<TestPayload> responseType() { return TestPayload.class; }
            @Override public TestPayload fetch(DataRequest request) { return null; }
        };
    }

    private static String[] configuration(String port) {
        return new String[] {
                "--server.port=" + port, "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--dataprism.security.jwt.issuer=https://issuer.example",
                "--dataprism.security.jwt.audience=mcp",
                "--dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "--dataprism.security.caller-claims.principal=sub",
                "--dataprism.security.caller-claims.roles=roles",
                "--dataprism.security.caller-claims.investigation=case_id",
                "--dataprism.security-policy.case-authorisation=token-trusted",
                "--dataprism.security-policy.client-allowlist=unrestricted",
                "--dataprism.security-policy.purposes[0]=investigation",
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.privacy.profile=DEFAULT",
                "--dataprism.privacy.scope-lifetime=8h",
                "--dataprism.privacy.hmac-key.key-id=v1",
                "--dataprism.privacy.hmac-key.environment-variable=test-key",
                "--dataprism.audit.sink=approved-sink",
                "--dataprism.audit.writer-id=test-server",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node",
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s"
        };
    }
}
