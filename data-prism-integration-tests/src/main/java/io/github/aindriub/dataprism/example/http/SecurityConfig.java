package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import io.github.aindriub.dataprism.spring.boot.JwtCallerContextExtractor;
import io.github.aindriub.dataprism.spring.boot.JwtDecoderSupport;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The resource server's filter chain: a bearer JWT, verified against a
 * configured JWKS URL, is the only way past the health probe.
 *
 * <p>The JWKS location, issuer and audience come from the starter's validated
 * {@code dataprism.security.jwt} properties. A token failing any check is
 * rejected before {@link JwtCallerContextExtractor} ever runs.
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
    McpTransportContextExtractor<HttpServletRequest> jwtCallerContextExtractor(DataPrismProperties properties) {
        return new JwtCallerContextExtractor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(DataPrismProperties properties) {
        return JwtDecoderSupport.buildJwtDecoder(properties);
    }
}
