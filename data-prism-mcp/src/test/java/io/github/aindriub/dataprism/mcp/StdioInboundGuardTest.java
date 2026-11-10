package io.github.aindriub.dataprism.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StdioInboundGuardTest {

    private static final String SENTINEL = "SENTINEL-SUBJECT-7f3a91c2";
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
    private static final String NOTIFICATION = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String PARSE_ERROR =
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}\n";
    private static final String INVALID_REQUEST =
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}\n";
    private static final int LIMIT = 1024;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private static ContentFreeMcpJsonMapper mapper() {
        return new ContentFreeMcpJsonMapper(new JacksonMcpJsonMapper(DataPrismObjectMapper.create()));
    }

    /** Feeds the bytes, reads everything the SDK would be given, and returns it. */
    private String forwarded(String input) throws IOException {
        StdioInboundGuard guard = new StdioInboundGuard(mapper(),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out, LIMIT);
        return new String(guard.input().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String answered() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an I/O fault on stdin is logged once, by exception class only, and the stream then ends")
    void anIoFaultIsLoggedWithoutItsMessage() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(StdioInboundGuard.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            InputStream failing = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("boom " + SENTINEL);
                }
            };
            StdioInboundGuard guard = new StdioInboundGuard(mapper(), failing, out, LIMIT);

            assertThat(guard.input().readAllBytes()).isEmpty();
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("java.io.IOException")
                        .doesNotContain("boom").doesNotContain(SENTINEL);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("valid lines pass byte for byte, in order")
    void validLinesPass() throws Exception {
        assertThat(forwarded(PING + "\n" + NOTIFICATION + "\n")).isEqualTo(PING + "\n" + NOTIFICATION + "\n");
        assertThat(answered()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            A unterminated object|{"jsonrpc":"2.0","method":"x","params":{"s":"SENTINEL-SUBJECT-7f3a91c2|-32700
            B bare alphanumeric token|SENTINELSUBJECT7f3a91c2|-32700
            C bare token with punctuation|SENTINEL-SUBJECT-7f3a91c2|-32700
            D valid JSON, not JSON-RPC|{"jsonrpc":"2.0","subjectId":"SENTINEL-SUBJECT-7f3a91c2"}|-32600
            a JSON string|"x"|-32600
            a JSON array|[1]|-32600
            JSON null|null|-32600
            """)
    @DisplayName("a malformed line is not forwarded and gets the stated error with a null id")
    void malformedLineIsRefused(String name, String line, String code) throws Exception {
        String forwarded = forwarded(line + "\n" + PING + "\n");

        assertThat(forwarded).isEqualTo(PING + "\n");
        assertThat(answered()).isEqualTo(code.equals("-32700") ? PARSE_ERROR : INVALID_REQUEST);
        assertThat(answered()).doesNotContain("SENTINEL");
    }

    @Test
    @DisplayName("a blank line is dropped, with no answer")
    void blankLineIsDropped() throws Exception {
        assertThat(forwarded("\n" + PING + "\n\n")).isEqualTo(PING + "\n");
        assertThat(answered()).isEmpty();
    }

    @Test
    @DisplayName("a line over the limit is refused as -32600 and the next valid line still passes")
    void overLimitLineIsRefused() throws Exception {
        String tooLong = "{\"method\":\"x\",\"pad\":\"" + "a".repeat(LIMIT) + "\"}";

        assertThat(forwarded(tooLong + "\n" + PING + "\n")).isEqualTo(PING + "\n");
        assertThat(answered()).isEqualTo(INVALID_REQUEST);
    }

    @Test
    @DisplayName("a wrongly typed method, result or error is refused as -32600 and the next line passes")
    void wronglyTypedMembersAreRefused() throws Exception {
        for (String bad : new String[] {"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":{\"a\":1}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":\"x\",\"message\":\"m\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":\"x\"}"}) {
            assertThat(forwarded(bad + "\n" + PING + "\n")).isEqualTo(PING + "\n");
            assertThat(answered()).isEqualTo(INVALID_REQUEST);
            out.reset();
        }
    }

    @Test
    @DisplayName("a line of exactly the limit passes")
    void lineOfExactlyTheLimitPasses() throws Exception {
        String prefix = "{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":{\"pad\":\"";
        String line = prefix + "a".repeat(LIMIT - prefix.length() - 3) + "\"}}";
        assertThat(line.length()).isEqualTo(LIMIT);

        assertThat(forwarded(line + "\n")).isEqualTo(line + "\n");
        assertThat(answered()).isEmpty();
    }

    @Test
    @DisplayName("a CR-terminated line, a CRLF-terminated line and a final unterminated line each end as readLine would")
    void lineFraming() throws Exception {
        assertThat(forwarded(PING + "\r" + NOTIFICATION + "\r\n" + PING)).isEqualTo(PING + "\n" + NOTIFICATION + "\n" + PING + "\n");
        assertThat(answered()).isEmpty();
    }

    @Test
    @DisplayName("a valid line then a lone CR then a malformed line: the malformed text is never forwarded")
    void loneCrSeparatesLines() throws Exception {
        String forwarded = forwarded(PING + "\r" + SENTINEL + "\n" + NOTIFICATION + "\n");

        assertThat(forwarded).isEqualTo(PING + "\n" + NOTIFICATION + "\n");
        assertThat(answered()).isEqualTo(PARSE_ERROR);
    }

    @Test
    @DisplayName("an error line written while another thread writes under the output's monitor is never split")
    void answerIsNotInterleavedWithAnotherWriter() throws Exception {
        PipedOutputStream feed = new PipedOutputStream();
        StdioInboundGuard guard = new StdioInboundGuard(mapper(), new PipedInputStream(feed), out, LIMIT);
        CountDownLatch holding = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            synchronized (guard.output()) {
                try {
                    guard.output().write("AAAA".getBytes(StandardCharsets.UTF_8));
                    holding.countDown();
                    Thread.sleep(500);
                    guard.output().write("BBBB\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        other.start();
        holding.await();

        feed.write((SENTINEL + "\n").getBytes(StandardCharsets.UTF_8));
        feed.flush();
        other.join();
        feed.close();
        guard.input().readAllBytes();

        assertThat(answered()).isEqualTo("AAAABBBB\n" + PARSE_ERROR);
    }

    @Test
    @DisplayName("the guard's output is the very stream it was given, so the SDK's writer lock is the guard's")
    void outputIsTheGivenStream() {
        InputStream in = new ByteArrayInputStream(new byte[0]);

        assertThat(new StdioInboundGuard(mapper(), in, out, LIMIT).output()).isSameAs(out);
    }
}
