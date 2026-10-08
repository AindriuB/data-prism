package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.spring.boot.OperatorProperties;
import io.github.aindriub.dataprism.spring.boot.SecurityProperties;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.server.operator.OperatorCallers;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import io.github.aindriub.dataprism.spring.boot.jwt.JwtCallerContextExtractor;
import io.github.aindriub.dataprism.spring.boot.jwt.JwtDecoderSupport;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Optional;

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

    /**
     * The operator surface's own chain, ahead of the MCP chain and matching every request that
     * arrives on {@code dataprism.operator.port} and nothing else. It decodes tokens with the
     * operator audience instead of the MCP one, and demands the operator scope. A token minted
     * for the MCP endpoint carries the wrong audience and is refused 401; a token with the right
     * audience and without the scope is refused 403. Anything not matched here falls to the MCP
     * chain, which no operator-port request can reach.
     */
    @Bean
    @Order(1)
    @DependsOn("dataPrismPropertiesValidated")
    @ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
    SecurityFilterChain operatorFilterChain(HttpSecurity http, DataPrismProperties properties) throws Exception {
        OperatorProperties operator = properties.getOperator();
        int port = operator.getPort();
        JwtDecoder decoder = JwtDecoderSupport.buildJwtDecoder(operatorTokenProperties(properties));
        http.securityMatcher(request -> request.getLocalPort() == port)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().hasAuthority("SCOPE_" + operator.getRequiredScope()))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(OPERATOR_UNAUTHENTICATED)
                        .accessDeniedHandler(OPERATOR_FORBIDDEN));
        return http.build();
    }

    /** 401 on the operator port: the bearer challenge header, then a code and nothing else. */
    private static final org.springframework.security.web.AuthenticationEntryPoint OPERATOR_UNAUTHENTICATED =
            (request, response, failure) -> {
                new org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint()
                        .commence(request, response, failure);
                codeOnly(response, 401, "UNAUTHENTICATED");
            };

    /** 403 on the operator port: a code and nothing else. */
    private static final org.springframework.security.web.access.AccessDeniedHandler OPERATOR_FORBIDDEN =
            (request, response, denied) -> codeOnly(response, 403, "FORBIDDEN");

    private static void codeOnly(jakarta.servlet.http.HttpServletResponse response, int status, String code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
        response.getWriter().flush();
    }

    /**
     * A request Spring Security's firewall refuses (a double slash, a path parameter, an encoded
     * traversal) never reaches a controller. On the operator port it is answered with
     * {@code {"code":"INVALID_REQUEST"}} and nothing derived from the request; on the MCP port the
     * default behaviour is kept. (Lives here because Spring Security stays at this one edge class.)
     */
    @Bean
    @ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
    RequestRejectedHandler operatorRequestRejectedHandler(DataPrismProperties properties) {
        Integer configured = properties.getOperator().getPort();
        int port = configured == null ? -1 : configured;
        RequestRejectedHandler fallback = new HttpStatusRequestRejectedHandler();
        return (request, response, rejected) -> {
            if (request.getLocalPort() != port) {
                fallback.handle(request, response, rejected);
                return;
            }
            response.setStatus(400);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"INVALID_REQUEST\"}");
            response.getWriter().flush();
        };
    }

    /**
     * The verified operator, read from the security context the operator chain populated. Built
     * with its own extractor so a replaced MCP extractor can never change who an operator is.
     */
    @Bean
    @ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
    OperatorCallers operatorCallers(DataPrismProperties properties) {
        JwtCallerContextExtractor extractor = new JwtCallerContextExtractor(properties);
        return () -> {
            if (!(SecurityContextHolder.getContext().getAuthentication()
                    instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken)) {
                return Optional.empty();
            }
            return extractor.extract(null).get(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY)
                    instanceof AuthenticatedCaller caller ? Optional.of(caller) : Optional.empty();
        };
    }

    /** The MCP token settings with the operator audience: same issuer and keys, different audience. */
    private static DataPrismProperties operatorTokenProperties(DataPrismProperties properties) {
        SecurityProperties.Jwt from = properties.getSecurity().getJwt();
        DataPrismProperties view = new DataPrismProperties();
        SecurityProperties.Jwt to = view.getSecurity().getJwt();
        to.setIssuer(from.getIssuer());
        to.setJwkSetUri(from.getJwkSetUri());
        to.setIssuerDiscoveryUri(from.getIssuerDiscoveryUri());
        to.setAudience(properties.getOperator().getRequiredAudience());
        return view;
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
