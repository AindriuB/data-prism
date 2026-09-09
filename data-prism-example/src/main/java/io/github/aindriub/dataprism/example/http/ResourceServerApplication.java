package io.github.aindriub.dataprism.example.http;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The deployable shape: an HTTP resource server that validates a bearer JWT
 * against a configured JWKS URL and serves the MCP streamable HTTP transport.
 *
 * <p>Deliberately a separate class from
 * {@link io.github.aindriub.dataprism.example.ExampleApplication}, stdio's
 * only call site, rather than a second launcher folded into it. Starting a
 * Spring context around the stdio path would print a banner to the stream
 * stdio's own protocol occupies — see that class's own comment — so this
 * application exists beside it instead of inside it.
 */
@SpringBootApplication
public class ResourceServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResourceServerApplication.class, args);
    }
}
