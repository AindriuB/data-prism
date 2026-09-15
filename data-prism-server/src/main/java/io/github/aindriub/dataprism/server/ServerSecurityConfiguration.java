package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import io.github.aindriub.dataprism.spring.boot.JwtCallerContextExtractor;
import io.github.aindriub.dataprism.spring.boot.JwtDecoderSupport;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Stateless bearer-token boundary for the standalone HTTP distribution. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ServerSecurityConfiguration {

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
        return JwtDecoderSupport.buildJwtDecoder(properties);
    }

    private static RequestMatcher exactPath(String path, HttpMethod method) {
        return request -> (method == null || method.matches(request.getMethod()))
                && path.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }
}
