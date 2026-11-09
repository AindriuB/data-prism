package io.github.aindriub.dataprism.spring.boot;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Pins the MCP SDK's loggers to WARN. The SDK logs the raw JSON-RPC message at
 * DEBUG, client-supplied arguments included, so root DEBUG would put them in
 * the log. The pin is the lowest-precedence property source: an operator who
 * names {@code logging.level.io.modelcontextprotocol} explicitly still wins,
 * and {@code docs/configuration.md} says that is unsupported in production.
 */
public final class McpSdkLogPin implements EnvironmentPostProcessor {

    static final String PROPERTY = "logging.level.io.modelcontextprotocol";
    static final String SOURCE_NAME = "dataPrismMcpSdkLogPin";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, Map.of(PROPERTY, "WARN")));
    }
}
