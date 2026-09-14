package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.ClaimNames;
import io.github.aindriub.dataprism.security.SecurityRefusedException;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts only the already-verified JWT security context into an MCP caller. */
final class JwtCallerContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {
    private static final Logger LOG = LoggerFactory.getLogger(JwtCallerContextExtractor.class);
    private final ClaimNames claimNames;

    JwtCallerContextExtractor(DataPrismProperties properties) {
        DataPrismProperties.Security.CallerClaims claims = properties.getSecurity().getCallerClaims();
        claimNames = new ClaimNames(claims.getPrincipal(), List.of("azp", "client_id"),
                claims.getRoles(), "purpose", claims.getInvestigation());
    }

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return McpTransportContext.EMPTY;
        }
        try {
            AuthenticatedCaller caller = AuthenticatedCaller.fromClaims(
                    claimsOf(jwtAuthentication.getToken()), claimNames);
            return McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller));
        } catch (SecurityRefusedException refused) {
            LOG.warn("a verified token could not be turned into a caller: {}", refused.code());
            return McpTransportContext.EMPTY;
        }
    }

    private static Map<String, Object> claimsOf(Jwt jwt) {
        Map<String, Object> claims = new HashMap<>(jwt.getClaims());
        if (jwt.getExpiresAt() != null) {
            claims.put("exp", jwt.getExpiresAt().getEpochSecond());
        }
        return claims;
    }
}
