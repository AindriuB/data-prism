package io.github.aindriub.dataprism.spring.boot;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** A Spring application must not log the SDK's raw JSON-RPC messages, even under root DEBUG. */
@ExtendWith(OutputCaptureExtension.class)
class McpSdkLogPinTest {

    private static final String SENTINEL = "SENTINEL-SUBJECT-7f3a91c2";
    private static final String MESSAGE = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"x\",\"arguments\":{\"subjectId\":\"" + SENTINEL + "\"}}}";

    @Test
    @DisplayName("root DEBUG leaves the SDK loggers above DEBUG and the sentinel out of the output")
    void pinHoldsUnderRootDebug(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext ignored = boot("--logging.level.root=DEBUG")) {
            for (Class<?> type : new Class<?>[] {McpSchema.class, McpServerSession.class,
                    McpStreamableServerSession.class}) {
                Logger logger = LoggerFactory.getLogger(type);
                assertThat(logger.isDebugEnabled()).as(type.getSimpleName() + " debug").isFalse();
                assertThat(logger.isInfoEnabled()).as(type.getSimpleName() + " info").isFalse();
            }
            McpSchema.deserializeJsonRpcMessage(McpJsonDefaults.getMapper(), MESSAGE);
            assertThat(output.getAll()).doesNotContain(SENTINEL);
        }
    }

    @Test
    @DisplayName("positive control: an explicit DEBUG on the SDK prefix wins and the sentinel is captured")
    void explicitOverrideWins(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext ignored = boot("--logging.level.root=DEBUG",
                "--logging.level.io.modelcontextprotocol=DEBUG")) {
            McpSchema.deserializeJsonRpcMessage(McpJsonDefaults.getMapper(), MESSAGE);
            assertThat(output.getAll()).contains(SENTINEL);
        }
    }

    private static ConfigurableApplicationContext boot(String... args) {
        SpringApplication application = new SpringApplication(Config.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(args);
    }

    @org.springframework.context.annotation.Configuration
    static class Config {
    }
}
