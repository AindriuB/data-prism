package io.github.aindriub.dataprism.example.http;

import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    @Test
    void constructs_a_decoder_directly_from_the_configured_jwks_uri() {
        DataPrismProperties properties = jwtProperties("https://issuer.example", "https://keys.example/jwks", null);

        JwtDecoder decoder = new SecurityConfig().jwtDecoder(properties);

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void refuses_a_discovered_plaintext_jwks_location() throws Exception {
        String configuredIssuer = "trusted-production-issuer";
        AtomicInteger metadataRequests = new AtomicInteger();
        AtomicInteger unexpectedRequests = new AtomicInteger();
        HttpServer discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + discovery.getAddress().getPort();
        String metadataPath = "/tenant/custom-discovery-document.json";
        discovery.createContext(metadataPath, exchange -> {
            metadataRequests.incrementAndGet();
            respond(exchange, 200, "{\"issuer\":\"" + configuredIssuer + "\",\"jwks_uri\":\""
                    + base + "/keys/exact-jwks\"}");
        });
        discovery.createContext("/", exchange -> {
            unexpectedRequests.incrementAndGet();
            respond(exchange, 404, "{}");
        });
        discovery.start();
        try {
            DataPrismProperties properties = jwtProperties(configuredIssuer, null, base + metadataPath);

            assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(properties))
                    .hasMessageContaining("JWT_DISCOVERY_FAILED")
                    .hasMessageContaining("approved HTTPS URI");
            assertThat(metadataRequests).hasValue(1);
            assertThat(unexpectedRequests).hasValue(0);
        } finally {
            discovery.stop(0);
        }
    }

    @Test
    void refuses_discovery_metadata_for_a_different_issuer() throws Exception {
        AtomicInteger exactRequests = new AtomicInteger();
        AtomicInteger unexpectedRequests = new AtomicInteger();
        HttpServer discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + discovery.getAddress().getPort();
        String metadataPath = "/exact/discovery.json";
        discovery.createContext(metadataPath, exchange -> {
            exactRequests.incrementAndGet();
            respond(exchange, 200, "{\"issuer\":\"unexpected-issuer\",\"jwks_uri\":\""
                    + base + "/keys\"}");
        });
        discovery.createContext("/", exchange -> {
            unexpectedRequests.incrementAndGet();
            respond(exchange, 404, "{}");
        });
        discovery.start();
        try {
            DataPrismProperties properties = jwtProperties("configured-issuer", null, base + metadataPath);

            assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(properties))
                    .hasMessageContaining("JWT_DISCOVERY_FAILED")
                    .hasMessageContaining("metadata issuer does not match");
            assertThat(exactRequests).hasValue(1);
            assertThat(unexpectedRequests).hasValue(0);
        } finally {
            discovery.stop(0);
        }
    }

    private static DataPrismProperties jwtProperties(String issuer, String jwkSetUri,
            String issuerDiscoveryUri) {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSecurity().getJwt().setIssuer(issuer);
        properties.getSecurity().getJwt().setAudience("data-prism-mcp");
        properties.getSecurity().getJwt().setJwkSetUri(jwkSetUri);
        properties.getSecurity().getJwt().setIssuerDiscoveryUri(issuerDiscoveryUri);
        return properties;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var response = exchange.getResponseBody()) {
            response.write(body);
        }
    }
}
