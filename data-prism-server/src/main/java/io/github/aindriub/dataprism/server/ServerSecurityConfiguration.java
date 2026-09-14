package io.github.aindriub.dataprism.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

/** Stateless bearer-token boundary for the standalone HTTP distribution. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ServerSecurityConfiguration {
    private static final int MAX_DISCOVERY_BYTES = 16 * 1024;
    private static final JsonFactory DISCOVERY_JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_DISCOVERY_BYTES).maxNestingDepth(8)
                    .maxStringLength(2048).maxTokenCount(128).build())
            .build();

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            DataPrismProperties properties) throws Exception {
        String mcpPath = properties.getTransport().getHttp().getPath();
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(exactPath("/health", HttpMethod.GET)).permitAll()
                        .requestMatchers(exactPath(mcpPath, null)).authenticated()
                        .anyRequest().denyAll())
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
        DataPrismProperties.Security.Jwt jwt = properties.getSecurity().getJwt();
        String jwkSetUri = blank(jwt.getJwkSetUri()) ? discoverJwkSetUri(jwt) : jwt.getJwkSetUri();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                new JwtIssuerValidator(jwt.getIssuer()), audienceValidator(jwt.getAudience())));
        return decoder;
    }

    private static String discoverJwkSetUri(DataPrismProperties.Security.Jwt jwt) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(jwt.getIssuerDiscoveryUri()).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) {
                throw discoveryFailure("metadata endpoint returned a non-success status");
            }
            try (var body = connection.getInputStream()) {
                if (connection.getContentLengthLong() > MAX_DISCOVERY_BYTES) {
                    throw discoveryFailure("metadata document exceeds the response limit");
                }
                byte[] bytes = body.readNBytes(MAX_DISCOVERY_BYTES + 1);
                if (bytes.length > MAX_DISCOVERY_BYTES) {
                    throw discoveryFailure("metadata document exceeds the response limit");
                }
                DiscoveryMetadata metadata = parseDiscoveryMetadata(bytes);
                if (!jwt.getIssuer().equals(metadata.issuer())) {
                    throw discoveryFailure("metadata issuer does not match dataprism.security.jwt.issuer");
                }
                validateDiscoveredJwkSetUri(metadata.jwkSetUri());
                return metadata.jwkSetUri();
            }
        } catch (DataPrismConfigurationException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw discoveryFailure("metadata could not be retrieved or parsed");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static DiscoveryMetadata parseDiscoveryMetadata(byte[] document) throws IOException {
        String issuer = null;
        String jwkSetUri = null;
        try (var parser = DISCOVERY_JSON.createParser(document)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw discoveryFailure("metadata must be a JSON object");
            }
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token == null) throw discoveryFailure("metadata JSON object is incomplete");
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("issuer".equals(name)) {
                    if (issuer != null || value != JsonToken.VALUE_STRING) {
                        throw discoveryFailure("metadata issuer must be one string");
                    }
                    issuer = parser.getText();
                } else if ("jwks_uri".equals(name)) {
                    if (jwkSetUri != null || value != JsonToken.VALUE_STRING) {
                        throw discoveryFailure("metadata jwks_uri must be one string");
                    }
                    jwkSetUri = parser.getText();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) throw discoveryFailure("metadata contains trailing content");
        }
        if (blank(issuer) || blank(jwkSetUri)) {
            throw discoveryFailure("metadata requires issuer and jwks_uri");
        }
        return new DiscoveryMetadata(issuer, jwkSetUri);
    }

    private static void validateDiscoveredJwkSetUri(String raw) {
        try {
            URI uri = URI.create(raw);
            boolean trusted = "https".equalsIgnoreCase(uri.getScheme());
            if (!trusted || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw discoveryFailure("metadata jwks_uri must be an approved HTTPS URI");
            }
        } catch (IllegalArgumentException exception) {
            throw discoveryFailure("metadata jwks_uri must be an approved HTTPS URI");
        }
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String requiredAudience) {
        return token -> {
            List<String> audience = token.getAudience();
            if (audience != null && audience.contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "the token's audience does not include the required value", null));
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static RequestMatcher exactPath(String path, HttpMethod method) {
        return request -> (method == null || method.matches(request.getMethod()))
                && path.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    private static DataPrismConfigurationException discoveryFailure(String detail) {
        return new DataPrismConfigurationException("JWT_DISCOVERY_FAILED", detail);
    }

    private record DiscoveryMetadata(String issuer, String jwkSetUri) { }
}
