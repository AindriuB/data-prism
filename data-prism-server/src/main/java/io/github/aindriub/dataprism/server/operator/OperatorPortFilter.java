package io.github.aindriub.dataprism.server.operator;

import jakarta.servlet.Filter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.util.Locale;

/**
 * Keeps the two surfaces apart, ahead of every other filter. The operator paths are served only on
 * the operator port and everything served on the operator port is an operator path; any other
 * pairing is a 404 with no authentication challenge, so neither port reveals
 * what the other serves.
 *
 * <p>Both the raw and the decoded request path are checked. A request on the MCP port is refused if
 * either spelling looks like an operator path; a request on the operator port is admitted only if
 * both spellings are exactly an operator path. A refusal on the operator port carries a
 * {@code {"code":...}} body and nothing else; on the MCP port it has no body.
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
            response.setHeader("Cache-Control", "no-store");
            if (!(raw.startsWith(PREFIX) && decoded.startsWith(PREFIX))) {
                respond(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND");
                return;
            }
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                tooLarge(response);
                return;
            }
            if (request.getContentLengthLong() < 0 && request.getHeader("Transfer-Encoding") != null) {
                // Chunked: there is no declared length to check, so read at most the limit plus one byte.
                byte[] body = request.getInputStream().readNBytes((int) MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) {
                    tooLarge(response);
                    return;
                }
                chain.doFilter(new BufferedBodyRequest(request, body), response);
                return;
            }
        } else if (looksLikeOperator(raw) || looksLikeOperator(decoded)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void respond(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
        response.getWriter().flush();
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        respond(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE");
        // The unread remainder of the body is abandoned with the connection.
        response.setHeader("Connection", "close");
    }

    /** The already-bounded body, replayed to the rest of the chain. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return source.read(); }
                @Override public int read(byte[] b, int off, int len) { return source.read(b, off, len); }
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("asynchronous reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static boolean looksLikeOperator(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.equals("/operator") || lower.startsWith(PREFIX) || lower.startsWith("/operator;");
    }
}
