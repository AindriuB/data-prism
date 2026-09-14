package io.github.aindriub.dataprism.quickstart.fixtures;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stands in for a customer API behind the standalone server, for the local
 * Compose quickstart (task 18) only.
 *
 * <p>The server refuses a plaintext {@code http://} source base URL (task 21),
 * so this process always serves HTTPS. It never generates or bundles its own
 * certificate: {@code server.ssl.*} must be supplied externally, pointing at
 * material a caller generated — see {@code docker/certs-init/} for the
 * Compose path and {@code QuickstartSmokeIT} in
 * {@code data-prism-quickstart-extension} for the Docker-free one. Nothing
 * generated is ever committed to this repository.
 */
@SpringBootApplication
public class FixturesApplication {
    public static void main(String[] args) {
        SpringApplication.run(FixturesApplication.class, args);
    }
}
