package io.github.aindriub.dataprism.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps the SDK's streamable HTTP servlet and refuses, itself, a {@code tools/call} or {@code initialize}
 * whose params do not convert to the SDK's request type.
 *
 * <p>Under SDK 2.1.0 such a message makes the SDK's handler throw before its error handling is attached;
 * the transport then answers 500 with a serialised exception and closes the connection. Here the body of a
 * POST is buffered, capped at the SDK's own request limit, and the params are converted with the same
 * content-free mapper the SDK is given. A failed conversion is answered 400 with JSON-RPC code
 * {@code -32602} and the request id; a body over the cap, or any fault inside the guard, is answered 400 with
 * {@code -32600}. Nothing refused is forwarded. Everything else, including a body that is not parseable
 * JSON (the SDK refuses those), is replayed to the SDK servlet unchanged. The guard never builds a tool
 * result, and logs one constant line per refusal: no client content, no exception.
 */
final class HttpInboundGuard extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(HttpInboundGuard.class);

    /** The SDK's {@code DEFAULT_REQUEST_MAX_SIZE} in {@code HttpServletStreamableServerTransportProvider}. */
    static final int SDK_REQUEST_MAX_SIZE = 16 * 1024 * 1024;

    static final int INVALID_REQUEST = -32600;
    static final int INVALID_PARAMS = -32602;

    private static final long serialVersionUID = 1L;

    private final transient HttpServlet delegate;
    private final transient McpJsonMapper mapper;
    private final int limit;

    HttpInboundGuard(HttpServlet delegate, McpJsonMapper mapper, int limit) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        delegate.init(config);
    }

    @Override
    public ServletConfig getServletConfig() {
        return delegate.getServletConfig();
    }

    @Override
    public void destroy() {
        delegate.destroy();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            delegate.service((ServletRequest) request, (ServletResponse) response);
            return;
        }
        byte[] body;
        int code;
        Object id;
        try {
            body = readBounded(request);
            if (body == null) {
                code = INVALID_REQUEST;
                id = null;
            } else {
                Object[] verdict = classify(body, request.getCharacterEncoding());
                code = (int) verdict[0];
                id = verdict[1];
            }
        } catch (Exception e) {
            body = null;
            code = INVALID_REQUEST;
            id = null;
        }
        if (code != 0) {
            refuse(response, code, id);
            return;
        }
        delegate.service((ServletRequest) new ReplayedRequest(request, body), (ServletResponse) response);
    }

    /** The body, or {@code null} when it is longer than the limit. Never reads more than the limit plus one. */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        InputStream in = request.getInputStream();
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > limit) {
                return null;
            }
            bytes.write(chunk, 0, n);
        }
        return bytes.toByteArray();
    }

    /** {@code {0, null}} when the body may go to the SDK, else {@code {code, id}} to refuse it with. */
    private Object[] classify(byte[] body, String encoding) {
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        Object value;
        try {
            value = mapper.readValue(new String(body, charset), Object.class);
        } catch (IOException e) {
            // the content-free mapper's answer to a body that is not JSON: the SDK refuses it
            return new Object[] {0, null};
        }
        if (!(value instanceof Map<?, ?> message) || !(message.get("method") instanceof String method)) {
            return new Object[] {0, null};
        }
        try {
            if ("tools/call".equals(method)) {
                mapper.convertValue(message.get("params"), McpSchema.CallToolRequest.class);
            } else if ("initialize".equals(method)) {
                mapper.convertValue(message.get("params"), McpSchema.InitializeRequest.class);
            }
        } catch (RuntimeException e) {
            return new Object[] {INVALID_PARAMS, message.get("id")};
        }
        return new Object[] {0, null};
    }

    private void refuse(HttpServletResponse response, int code, Object id) throws IOException {
        String message = code == INVALID_PARAMS ? "Invalid params" : "Invalid Request";
        String echoed = "null";
        if (id instanceof Number || id instanceof String) {
            try {
                echoed = mapper.writeValueAsString(id);
            } catch (IOException | RuntimeException e) {
                echoed = "null";
            }
        }
        byte[] reply = ("{\"jsonrpc\":\"2.0\",\"id\":" + echoed + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}").getBytes(StandardCharsets.UTF_8);
        LOG.warn("http: refused a malformed inbound message ({})", code);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(reply.length);
        response.getOutputStream().write(reply);
        response.getOutputStream().flush();
    }

    /** The buffered body, served again to the SDK servlet. Everything else is the original request's. */
    private static final class ReplayedRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return source.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("the replayed body is read synchronously");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
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
}
