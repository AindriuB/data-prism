package io.github.aindriub.dataprism.quickstart.issuer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * A local JWT issuer for the Compose quickstart (task 18).
 *
 * <p>The standalone server has no fixture-development bypass (task 21): every
 * request in the quickstart carries a real, signature-verified JWT. This
 * process is what makes that possible without a real identity provider — it
 * generates its own signing key in memory at startup (never persisted, never
 * committed), serves the public half over HTTPS at {@code /jwks}, and mints
 * tokens against it at {@code /token}.
 */
@SpringBootApplication
@EnableConfigurationProperties(IssuerProperties.class)
public class IssuerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IssuerApplication.class, args);
    }
}
