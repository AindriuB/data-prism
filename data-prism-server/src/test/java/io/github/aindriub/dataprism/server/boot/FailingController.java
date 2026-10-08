package io.github.aindriub.dataprism.server.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 142: a handler that throws, so a real MVC error reaches the error path. Component-scanned
 * from the test classpath, and inert unless {@code boot4.guard.failing-controller=true}. The MCP
 * port permits only {@code GET /health}, so the MCP-port route is the more specific
 * {@code /health?fail} mapping; the operator port serves {@code /operator/**}.
 */
@RestController
@ConditionalOnProperty(name = "boot4.guard.failing-controller", havingValue = "true")
final class FailingController {

    @GetMapping(path = "/health", params = "fail")
    String failOnTheMcpPort() {
        throw new IllegalStateException("guard failure");
    }

    @GetMapping("/operator/fail")
    String failOnTheOperatorPort() {
        throw new IllegalStateException("guard failure");
    }
}
