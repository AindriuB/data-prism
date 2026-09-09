package io.github.aindriub.dataprism.example.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The one endpoint {@link SecurityConfig} leaves unauthenticated: a liveness
 * probe carries no privacy-relevant information, so there is nothing here for
 * a bearer token to protect.
 */
@RestController
class HealthController {

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
