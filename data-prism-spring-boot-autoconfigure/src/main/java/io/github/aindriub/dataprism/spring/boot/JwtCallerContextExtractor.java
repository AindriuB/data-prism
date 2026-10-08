package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.InboundCorrelation;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.ClaimNames;
import io.github.aindriub.dataprism.security.SecurityRefusedException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a verified {@code Jwt} into the {@link AuthenticatedCaller} the MCP
 * tool layer reads from {@code exchange.transportContext()}.
 *
 * <p>Signature, issuer and audience verification already happened in the
 * edge deployment's own filter chain by the time this runs — Spring
 * Security's {@code OAuth2ResourceServer} filter authenticates the request and
 * populates {@link SecurityContextHolder} before the servlet is ever reached,
 * and {@code HttpServletStreamableServerTransportProvider} calls this
 * extractor synchronously from that same request thread, before it hands the
 * message off for streaming. So a plain {@link SecurityContextHolder} read
 * here is safe.
 *
 * <p>Nothing here ever reads the request itself: the verified token is
 * available only through {@link SecurityContextHolder}, and only
 * {@link AuthenticatedCaller} — never the {@code Jwt}, its claims map, or the
 * raw token string — goes into the transport context, gets logged, or is put
 * on any request attribute.
 */
public final class JwtCallerContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(JwtCallerContextExtractor.class);
    private final ClaimNames claimNames;
    private final String correlationHeader; // null: the feature is off
    private final CorrelationIdPolicy correlationPolicy;

    public JwtCallerContextExtractor(DataPrismProperties properties) {
        SecurityProperties.CallerClaims configured = properties.getSecurity().getCallerClaims();
        this.claimNames = new ClaimNames(configured.getPrincipal(), List.of("azp", "client_id"),
                configured.getRoles(), "purpose", configured.getInvestigation());
        CorrelationProperties.Inbound inbound = properties.getCorrelation().getInbound();
        boolean enabled = inbound.getHeader() != null && !inbound.getHeader().isBlank();
        this.correlationHeader = enabled ? inbound.getHeader() : null;
        this.correlationPolicy = enabled ? inbound.policy() : null;
    }

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            // Never reachable behind the filter chain for the MCP endpoint,
            // which requires authentication before this extractor runs at
            // all — but an empty context is the correct answer if it ever
            // were, exactly like any other unresolvable request.
            return McpTransportContext.EMPTY;
        }

        try {
            AuthenticatedCaller caller =
                    AuthenticatedCaller.fromClaims(claimsOf(jwtAuthentication.getToken()), claimNames);
            Map<String, Object> context = new HashMap<>();
            context.put(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller);
            if (correlationHeader != null) {
                java.util.Enumeration<String> values = request.getHeaders(correlationHeader);
                InboundCorrelation correlation = InboundCorrelation.resolve(
                        values == null ? List.of() : Collections.list(values), correlationPolicy);
                if (correlation.isRejected()) {
                    // The code only. The rejected text is caller-supplied and may carry personal data.
                    LOG.warn("an inbound correlation id was dropped: {}", "EXTERNAL_CORRELATION_ID_DROPPED");
                }
                context.put(DataPrismMcpServer.TRANSPORT_CONTEXT_CORRELATION_KEY, correlation);
            }
            return McpTransportContext.create(context);
        } catch (SecurityRefusedException refused) {
            // The code is safe to log; the claims that produced it are not, and
            // are never read here again to find out.
            LOG.warn("a verified token could not be turned into a caller: {}", refused.code());
            return McpTransportContext.EMPTY;
        }
    }

    /**
     * {@link Jwt#getClaims()} carries {@code exp} as a parsed {@link java.time.Instant},
     * not the raw epoch-seconds number a JWT payload actually holds — Spring
     * Security's own claim converter does that substitution before this class
     * ever sees the map. {@link AuthenticatedCaller#fromClaims} expects the raw
     * shape, so this method undoes exactly that one substitution from
     * {@link Jwt#getExpiresAt()}, which carries the same value, and leaves
     * every other claim exactly as Spring parsed it.
     */
    private static Map<String, Object> claimsOf(Jwt jwt) {
        Map<String, Object> claims = new HashMap<>(jwt.getClaims());
        if (jwt.getExpiresAt() != null) {
            claims.put("exp", jwt.getExpiresAt().getEpochSecond());
        }
        return claims;
    }
}
