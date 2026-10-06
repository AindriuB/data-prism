package io.github.aindriub.dataprism.server.operator;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Locale;

/**
 * Keeps the two surfaces apart, ahead of every other filter. The operator paths are served only on
 * the operator port and everything served on the operator port is an operator path; any other
 * pairing is a plain 404, with no body and no authentication challenge, so neither port reveals
 * what the other serves.
 *
 * <p>Both the raw and the decoded request path are checked. A request on the MCP port is refused if
 * either spelling looks like an operator path; a request on the operator port is admitted only if
 * both spellings are exactly an operator path.
 */
final class OperatorPortFilter implements Filter {

    static final String PREFIX = "/operator/";
    /** Operator bodies are small JSON objects. */
    static final long MAX_BODY_BYTES = 16 * 1024;

    private final int operatorPort;

    OperatorPortFilter(int operatorPort) {
        this.operatorPort = operatorPort;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        String decoded = (request.getServletPath() == null ? "" : request.getServletPath())
                + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (request.getLocalPort() == operatorPort) {
            if (!(raw.startsWith(PREFIX) && decoded.startsWith(PREFIX))) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setHeader("Cache-Control", "no-store");
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                return;
            }
        } else if (looksLikeOperator(raw) || looksLikeOperator(decoded)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean looksLikeOperator(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.equals("/operator") || lower.startsWith(PREFIX) || lower.startsWith("/operator;");
    }
}
