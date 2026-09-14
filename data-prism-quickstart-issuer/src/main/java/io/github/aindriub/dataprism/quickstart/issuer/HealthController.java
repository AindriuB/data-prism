package io.github.aindriub.dataprism.quickstart.issuer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness only, so Compose and the Docker-free smoke test can tell this process is up. */
@RestController
final class HealthController {
    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
