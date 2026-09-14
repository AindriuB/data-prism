package io.github.aindriub.dataprism.example.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
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
    void discovers_jwks_when_the_issuer_discovery_location_is_configured() throws Exception {
        String configuredIssuer = "trusted-production-issuer";
        AtomicInteger metadataRequests = new AtomicInteger();
        AtomicInteger jwksRequests = new AtomicInteger();
        AtomicInteger unexpectedRequests = new AtomicInteger();
        RSAKey key = new RSAKeyGenerator(2048).keyID("discovered-key").algorithm(JWSAlgorithm.RS256).generate();
        HttpServer discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + discovery.getAddress().getPort();
        String metadataPath = "/tenant/custom-discovery-document.json";
        String jwksPath = "/keys/exact-jwks";
        discovery.createContext(metadataPath, exchange -> {
            metadataRequests.incrementAndGet();
            respond(exchange, 200, "{\"issuer\":\"" + configuredIssuer + "\",\"jwks_uri\":\""
                    + base + jwksPath + "\"}");
        });
        discovery.createContext(jwksPath, exchange -> {
            jwksRequests.incrementAndGet();
            respond(exchange, 200, new JWKSet(key.toPublicJWK()).toString());
        });
        discovery.createContext("/", exchange -> {
            unexpectedRequests.incrementAndGet();
            respond(exchange, 404, "{}");
        });
        discovery.start();
        try {
            DataPrismProperties properties = jwtProperties(configuredIssuer, null, base + metadataPath);
            properties.getTransport().setFixtureDevelopment(true);

            JwtDecoder decoder = new SecurityConfig().jwtDecoder(properties);
            SignedJWT token = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    new JWTClaimsSet.Builder()
                            .subject("protected-caller")
                            .issuer(configuredIssuer)
                            .audience(List.of("data-prism-mcp"))
                            .issueTime(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                            .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                            .build());
            token.sign(new RSASSASigner(key));

            assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
            assertThat(decoder.decode(token.serialize()).getSubject()).isEqualTo("protected-caller");
            assertThat(metadataRequests).hasValue(1);
            assertThat(jwksRequests).hasValue(1);
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
            properties.getTransport().setFixtureDevelopment(true);

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
