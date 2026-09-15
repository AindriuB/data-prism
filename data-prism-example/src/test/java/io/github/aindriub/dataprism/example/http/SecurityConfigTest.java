package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The discovery, SSRF-guard and metadata-parsing coverage for the decoder
 * this bean builds lives once, in {@code JwtDecoderSupportTest} — this class
 * only proves the bean method delegates to it.
 */
class SecurityConfigTest {

    @Test
    void jwtDecoderBeanDelegatesToTheSharedSupportClass() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSecurity().getJwt().setIssuer("https://issuer.example");
        properties.getSecurity().getJwt().setAudience("data-prism-mcp");
        properties.getSecurity().getJwt().setJwkSetUri("https://keys.example/jwks");

        JwtDecoder decoder = new SecurityConfig().jwtDecoder(properties);

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }
}
