package io.github.aindriub.dataprism.example.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * The resource server's filter chain: a bearer JWT, verified against a
 * configured JWKS URL, is the only way past the health probe.
 *
 * <p>Configured by {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
 * alone — no IdP-specific client, no discovery hack (an {@code issuer-uri}
 * would trigger an OIDC discovery fetch this deployment does not want), and no
 * issuer-specific claim name in code. The issuer and audience this decoder
 * requires are {@link JwtSecurityProperties}, plain configuration values rather
 * than anything specific to one identity provider's conventions, and a token
 * failing either check is rejected before {@link JwtCallerContextExtractor}
 * ever runs.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String HEALTH_PATH = "/health";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                // A bearer-token API with no cookie or session to protect: CSRF
                // exists to stop a browser silently replaying a session cookie,
                // which is not how any client reaches this endpoint.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, HEALTH_PATH).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            JwtSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.issuer()),
                audienceValidator(properties.audience())));
        return decoder;
    }

    /** Not one of Spring's own validators: those key off {@code issuer-uri}, which this class avoids. */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String requiredAudience) {
        return token -> {
            List<String> audience = token.getAudience();
            if (audience != null && audience.contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "the token's audience does not include the required value",
                            null));
        };
    }
}
