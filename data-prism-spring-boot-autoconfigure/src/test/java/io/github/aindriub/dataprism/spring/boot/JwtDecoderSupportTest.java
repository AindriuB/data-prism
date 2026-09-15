package io.github.aindriub.dataprism.spring.boot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both edge deployments call {@link JwtDecoderSupport#buildJwtDecoder}
 * directly for their discovery, SSRF-guard and metadata-parsing coverage
 * rather than duplicating it per filter chain — this is the one place that
 * coverage lives.
 */
class JwtDecoderSupportTest {

    private static final String CONFIGURED_ISSUER = "trusted-production-issuer";

    @Test
    void refuses_a_discovered_jwks_uri_over_plain_http() throws Exception {
        assertDiscoveryRefused(
                metadataResponse(CONFIGURED_ISSUER, "http://keys.example/jwks"),
                "approved HTTPS URI");
    }

    @Test
    void refuses_a_discovered_jwks_uri_carrying_user_info() throws Exception {
        assertDiscoveryRefused(
                metadataResponse(CONFIGURED_ISSUER, "https://someone:secret@keys.example/jwks"),
                "approved HTTPS URI");
    }

    @Test
    void refuses_a_discovered_jwks_uri_carrying_a_fragment() throws Exception {
        assertDiscoveryRefused(
                metadataResponse(CONFIGURED_ISSUER, "https://keys.example/jwks#fragment"),
                "approved HTTPS URI");
    }

    @Test
    void refuses_a_discovered_jwks_uri_with_no_host() throws Exception {
        assertDiscoveryRefused(
                metadataResponse(CONFIGURED_ISSUER, "https:///jwks"),
                "approved HTTPS URI");
    }

    @Test
    void refuses_discovery_metadata_for_a_different_issuer() throws Exception {
        assertDiscoveryRefused(
                metadataResponse("unexpected-issuer", "https://keys.example/jwks"),
                "metadata issuer does not match");
    }

    @Test
    void refuses_a_discovery_document_over_the_response_limit() throws Exception {
        // The response-size check reads the declared Content-Length before any
        // parsing happens, so an oversized body — its content is irrelevant —
        // is enough to prove the limit is enforced.
        byte[] oversized = "x".repeat(17 * 1024).getBytes(StandardCharsets.UTF_8);
        assertDiscoveryRefused(oversized, "exceeds the response limit");
    }

    @Test
    void refuses_metadata_with_a_duplicate_key() throws Exception {
        String body = "{\"issuer\":\"" + CONFIGURED_ISSUER + "\",\"issuer\":\"" + CONFIGURED_ISSUER
                + "\",\"jwks_uri\":\"https://keys.example/jwks\"}";
        assertDiscoveryRefused(body.getBytes(StandardCharsets.UTF_8), "must be one string");
    }

    @Test
    void refuses_metadata_with_trailing_content() throws Exception {
        String body = "{\"issuer\":\"" + CONFIGURED_ISSUER + "\",\"jwks_uri\":\"https://keys.example/jwks\"} {}";
        assertDiscoveryRefused(body.getBytes(StandardCharsets.UTF_8), "trailing content");
    }

    private static void assertDiscoveryRefused(byte[] discoveryResponseBody, String expectedMessageFragment)
            throws Exception {
        AtomicInteger metadataRequests = new AtomicInteger();
        AtomicInteger unexpectedRequests = new AtomicInteger();
        HttpServer discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + discovery.getAddress().getPort();
        String metadataPath = "/tenant/discovery-document.json";
        discovery.createContext(metadataPath, exchange -> {
            metadataRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, discoveryResponseBody.length);
            try (var response = exchange.getResponseBody()) {
                response.write(discoveryResponseBody);
            }
        });
        discovery.createContext("/", exchange -> {
            unexpectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
        });
        discovery.start();
        try {
            DataPrismProperties properties = jwtProperties(CONFIGURED_ISSUER, null, base + metadataPath);

            assertThatThrownBy(() -> JwtDecoderSupport.buildJwtDecoder(properties))
                    .isInstanceOf(DataPrismConfigurationException.class)
                    .hasMessageContaining("JWT_DISCOVERY_FAILED")
                    .hasMessageContaining(expectedMessageFragment);
            assertThat(metadataRequests).hasValue(1);
            assertThat(unexpectedRequests).hasValue(0);
        } finally {
            discovery.stop(0);
        }
    }

    private static byte[] metadataResponse(String issuer, String jwkSetUri) {
        return ("{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + jwkSetUri + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static DataPrismProperties jwtProperties(String issuer, String jwkSetUri, String issuerDiscoveryUri) {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSecurity().getJwt().setIssuer(issuer);
        properties.getSecurity().getJwt().setAudience("data-prism-mcp");
        properties.getSecurity().getJwt().setJwkSetUri(jwkSetUri);
        properties.getSecurity().getJwt().setIssuerDiscoveryUri(issuerDiscoveryUri);
        return properties;
    }
}
