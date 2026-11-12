package io.github.aindriub.dataprism.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpInboundGuardTest {

    private static final String SENTINEL = "SENTINEL-SUBJECT-7f3a91c2";
    private static final McpJsonMapper JSON = new ContentFreeMcpJsonMapper(
            new JacksonMcpJsonMapper(DataPrismObjectMapper.create()));
    private static final String VALID_CALL = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"get_entity_context\",\"arguments\":{\"a\":\"b\"}}}";

    /** The SDK servlet stand-in: records the calls and the body it is replayed. */
    private static final class Recording extends HttpServlet {
        int calls;
        byte[] body;
        ServletConfig config;
        boolean destroyed;

        @Override
        public void init(ServletConfig config) {
            this.config = config;
        }

        @Override
        public ServletConfig getServletConfig() {
            return config;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public void service(ServletRequest request, ServletResponse response) throws IOException {
            calls++;
            body = request.getInputStream().readAllBytes();
        }
    }

    private static final class Exchange {
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        Exchange() throws IOException {
            when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public void write(int b) {
                    written.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            });
        }

        String body() {
            return written.toString(StandardCharsets.UTF_8);
        }
    }

    private static ServletInputStream stream(byte[] bytes) {
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
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
            }
        };
    }

    private static HttpServletRequest post(byte[] body) throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getInputStream()).thenReturn(stream(body));
        return request;
    }

    private static HttpServletRequest post(String body) throws IOException {
        return post(body.getBytes(StandardCharsets.UTF_8));
    }

    private static void run(HttpServlet guard, HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        guard.service((ServletRequest) request, (ServletResponse) response);
    }

    private static void assertRefusal(Exchange exchange, int code, String id) {
        String body = exchange.body();
        assertThat(body).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + (code == -32602 ? "Invalid params" : "Invalid Request") + "\"}}");
        assertThat(body).doesNotContain(SENTINEL).doesNotContain("stackTrace");
        verify(exchange.response).setStatus(400);
        verify(exchange.response).setContentType("application/json");
        verify(exchange.response, never()).setHeader(anyString(), anyString());
        verify(exchange.response, never()).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("tools/call params of the wrong type: 400 / -32602, the id echoed, the SDK never invoked")
    void toolCallTypeError() throws Exception {
        Recording sdk = new Recording();
        Exchange exchange = new Exchange();
        run(new HttpInboundGuard(sdk, JSON, 4096), post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_entity_context\",\"arguments\":\"" + SENTINEL + "\"}}"),
                exchange.response);
        assertThat(sdk.calls).isZero();
        assertRefusal(exchange, -32602, "2");
    }

    @Test
    @DisplayName("initialize whose capabilities is a string: 400 / -32602, the id echoed, the SDK never invoked")
    void initializeTypeError() throws Exception {
        Recording sdk = new Recording();
        Exchange exchange = new Exchange();
        run(new HttpInboundGuard(sdk, JSON, 4096), post("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":\"" + SENTINEL
                + "\",\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"}}}"), exchange.response);
        assertThat(sdk.calls).isZero();
        assertRefusal(exchange, -32602, "\"abc\"");
    }

    @Test
    @DisplayName("tools/call and initialize with params absent or null: 400 / -32602, the SDK never invoked")
    void absentOrNullParamsRefused() throws Exception {
        String[] bodies = {
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":null}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"initialize\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"initialize\",\"params\":null}"};
        for (String body : bodies) {
            Recording sdk = new Recording();
            Exchange exchange = new Exchange();
            run(new HttpInboundGuard(sdk, JSON, 4096), post(body), exchange.response);
            assertThat(sdk.calls).as(body).isZero();
            assertRefusal(exchange, -32602, "4");
        }
    }

    @Test
    @DisplayName("an id that is neither a number nor a string is answered as null")
    void structuredIdIsNull() throws Exception {
        Recording sdk = new Recording();
        Exchange exchange = new Exchange();
        run(new HttpInboundGuard(sdk, JSON, 4096), post("{\"jsonrpc\":\"2.0\",\"id\":{\"a\":\"" + SENTINEL
                + "\"},\"method\":\"tools/call\",\"params\":{\"name\":\"n\",\"arguments\":\"s\"}}"),
                exchange.response);
        assertThat(sdk.calls).isZero();
        assertRefusal(exchange, -32602, "null");
    }

    @Test
    @DisplayName("a valid tools/call is replayed to the SDK byte for byte")
    void validCallForwarded() throws Exception {
        Recording sdk = new Recording();
        Exchange exchange = new Exchange();
        run(new HttpInboundGuard(sdk, JSON, 4096), post(VALID_CALL), exchange.response);
        assertThat(sdk.calls).isEqualTo(1);
        assertThat(sdk.body).isEqualTo(VALID_CALL.getBytes(StandardCharsets.UTF_8));
        assertThat(exchange.written.size()).isZero();
    }

    @Test
    @DisplayName("a body exactly at the limit is forwarded unchanged; one byte over is refused")
    void limit() throws Exception {
        byte[] atLimit = VALID_CALL.getBytes(StandardCharsets.UTF_8);
        Recording sdk = new Recording();
        run(new HttpInboundGuard(sdk, JSON, atLimit.length), post(atLimit), new Exchange().response);
        assertThat(sdk.calls).isEqualTo(1);
        assertThat(sdk.body).isEqualTo(atLimit);

        Recording over = new Recording();
        Exchange exchange = new Exchange();
        run(new HttpInboundGuard(over, JSON, atLimit.length - 1), post(atLimit), exchange.response);
        assertThat(over.calls).isZero();
        assertRefusal(exchange, -32600, "null");
    }

    @Test
    @DisplayName("a body that is not JSON, or not a method call, is left to the SDK")
    void undecidableForwarded() throws Exception {
        for (String body : new String[] {SENTINEL, "{\"jsonrpc\":\"2.0\",\"subjectId\":\"x\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "[1,2]", ""}) {
            Recording sdk = new Recording();
            run(new HttpInboundGuard(sdk, JSON, 4096), post(body), new Exchange().response);
            assertThat(sdk.calls).as(body).isEqualTo(1);
            assertThat(sdk.body).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("a fault inside the guard is 400 / -32600 and the SDK is never invoked")
    void faultRefuses() throws Exception {
        McpJsonMapper throwing = new ThrowingMapper();
        Recording sdk = new Recording();
        Exchange exchange = new Exchange();
        run(new HttpInboundGuard(sdk, throwing, 4096), post(VALID_CALL), exchange.response);
        assertThat(sdk.calls).isZero();
        assertRefusal(exchange, -32600, "null");

        HttpServletRequest failing = mock(HttpServletRequest.class);
        when(failing.getMethod()).thenReturn("POST");
        when(failing.getInputStream()).thenThrow(new IOException(SENTINEL));
        Exchange second = new Exchange();
        run(new HttpInboundGuard(sdk, JSON, 4096), failing, second.response);
        assertThat(sdk.calls).isZero();
        assertRefusal(second, -32600, "null");
    }

    @Test
    @DisplayName("a request that is not a POST, and init and destroy, go straight to the SDK servlet")
    void delegates() throws Exception {
        Recording sdk = new Recording();
        HttpInboundGuard guard = new HttpInboundGuard(sdk, JSON, 4096);
        ServletConfig config = mock(ServletConfig.class);
        guard.init(config);
        assertThat(guard.getServletConfig()).isSameAs(config);
        HttpServletRequest get = mock(HttpServletRequest.class);
        when(get.getMethod()).thenReturn("GET");
        when(get.getInputStream()).thenReturn(stream(new byte[0]));
        run(guard, get, new Exchange().response);
        assertThat(sdk.calls).isEqualTo(1);
        guard.destroy();
        assertThat(sdk.destroyed).isTrue();
    }

    /** A mapper whose every read fails with a non-content exception. */
    private static final class ThrowingMapper implements McpJsonMapper {
        @Override
        public <T> T readValue(String content, Class<T> type) {
            throw new IllegalStateException(SENTINEL);
        }

        @Override
        public <T> T readValue(byte[] content, Class<T> type) {
            throw new IllegalStateException(SENTINEL);
        }

        @Override
        public <T> T readValue(String content, io.modelcontextprotocol.json.TypeRef<T> type) {
            throw new IllegalStateException(SENTINEL);
        }

        @Override
        public <T> T readValue(byte[] content, io.modelcontextprotocol.json.TypeRef<T> type) {
            throw new IllegalStateException(SENTINEL);
        }

        @Override
        public <T> T convertValue(Object value, Class<T> type) {
            throw new IllegalStateException(SENTINEL);
        }

        @Override
        public <T> T convertValue(Object value, io.modelcontextprotocol.json.TypeRef<T> type) {
            throw new IllegalStateException(SENTINEL);
        }

        @Override
        public String writeValueAsString(Object value) {
            throw new IllegalStateException(SENTINEL);
        }

        @Override
        public byte[] writeValueAsBytes(Object value) {
            throw new IllegalStateException(SENTINEL);
        }
    }
}
