package io.github.aindriub.dataprism.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness only: no configuration, dependency, identity, or readiness details. */
@RestController
final class HealthController {
    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
