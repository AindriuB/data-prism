package io.github.aindriub.dataprism.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Stands between stdin and the SDK's stdio transport and refuses a malformed line itself, so that its
 * text reaches no log and the session survives it.
 *
 * <p>The SDK's stdio reader logs a parse failure at ERROR with Jackson's message, which quotes the
 * offending token, then closes the session. Here each line is framed as {@code BufferedReader.readLine}
 * frames it (LF, CRLF or a lone CR end a line) and checked in three passes with a content-free mapper:
 * syntax, then shape, then type (the tree must convert to the SDK's request, notification or
 * response). It never calls {@code McpSchema.deserializeJsonRpcMessage}, which logs the
 * raw line at DEBUG before parsing. A line that passes all three goes to the SDK unchanged, re-terminated with
 * LF. A line that fails is not forwarded: the guard answers it with {@code "id":null} and the JSON-RPC
 * code {@code -32700} (not JSON) or {@code -32600} (JSON, but not a request, notification or response),
 * and logs one constant line. Nothing of the line, its length or an exception is logged. A blank line
 * is dropped; a line over the limit is discarded and refused as {@code -32600}, and the guard never holds
 * more than the limit.
 *
 * <p>Answers are written holding the monitor of the {@link OutputStream} given to the constructor. That
 * is the SDK's writer lock when {@link #output()} is passed to it, so a refusal and an SDK reply never
 * interleave within a line.
 */
final class StdioInboundGuard {

    private static final Logger LOG = LoggerFactory.getLogger(StdioInboundGuard.class);

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;

    private static final byte[] EOF = new byte[0];
    private static final int INITIAL_BUFFER = 8192;
    private static final int QUEUE_LINES = 64;

    private final McpJsonMapper mapper;
    private final InputStream in;
    private final OutputStream out;
    private final int limit;
    private final LineInputStream forwarded = new LineInputStream();

    /**
     * Starts reading {@code in} on a daemon thread.
     *
     * @param mapper a content-free mapper; the guard classifies with it alone
     * @param out    the stream the SDK writes to; the same instance must be given to the SDK, through
     *               {@link #output()}
     * @param limit  the longest line, in bytes, that is forwarded; set to the SDK's own limit
     */
    StdioInboundGuard(McpJsonMapper mapper, InputStream in, OutputStream out, int limit) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.in = new BufferedInputStream(Objects.requireNonNull(in, "in"));
        this.out = Objects.requireNonNull(out, "out");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        Thread reader = new Thread(this::run, "data-prism-stdio-inbound-guard");
        reader.setDaemon(true);
        reader.start();
    }

    /** The lines that passed, each ending in LF: what the SDK reads. */
    InputStream input() {
        return forwarded;
    }

    /** The stream the SDK writes to, and whose monitor the guard holds when it answers. */
    OutputStream output() {
        return out;
    }

    private void run() {
        try {
            byte[] line = new byte[Math.min(INITIAL_BUFFER, limit)];
            int length = 0;
            boolean discarding = false;
            boolean skipLf = false;
            int b;
            while ((b = in.read()) != -1) {
                if (skipLf) {
                    skipLf = false;
                    if (b == '\n') {
                        continue;
                    }
                }
                if (b == '\n' || b == '\r') {
                    skipLf = b == '\r';
                    if (discarding) {
                        refuse(INVALID_REQUEST);
                    } else {
                        handle(line, length);
                    }
                    length = 0;
                    discarding = false;
                    continue;
                }
                if (discarding) {
                    continue;
                }
                if (length == limit) {
                    discarding = true;
                    length = 0;
                    continue;
                }
                if (length == line.length) {
                    line = java.util.Arrays.copyOf(line, (int) Math.min((long) line.length * 2, limit));
                }
                line[length++] = (byte) b;
            }
            if (discarding) {
                refuse(INVALID_REQUEST);
            } else {
                handle(line, length);
            }
        } catch (IOException e) {
            // A stdin or stdout fault ends the session as it would at EOF; say so, naming only the class,
            // because the message may quote client bytes.
            LOG.warn("stdio inbound guard stopped on an I/O fault: {}", e.getClass().getName());
        } catch (InterruptedException e) {
            // the SDK went away: the stream ends, as it would without a guard
            Thread.currentThread().interrupt();
        } finally {
            forwarded.finish();
        }
    }

    private void handle(byte[] line, int length) throws IOException, InterruptedException {
        if (length == 0) {
            return;
        }
        int code = classify(new String(line, 0, length, StandardCharsets.UTF_8));
        if (code == 0) {
            byte[] forward = java.util.Arrays.copyOf(line, length + 1);
            forward[length] = '\n';
            forwarded.put(forward);
        } else {
            refuse(code);
        }
    }

    /** {@code 0} when the line may go to the SDK, else the JSON-RPC code to refuse it with. */
    private int classify(String line) {
        Object value;
        try {
            value = mapper.readValue(line, Object.class);
        } catch (IOException | RuntimeException e) {
            return PARSE_ERROR;
        }
        if (!(value instanceof Map<?, ?> map)
                || !(map.containsKey("method") || map.containsKey("result") || map.containsKey("error"))) {
            return INVALID_REQUEST;
        }
        try {
            if (map.containsKey("method")) {
                if (map.containsKey("id")) {
                    mapper.convertValue(value, McpSchema.JSONRPCRequest.class);
                } else {
                    mapper.convertValue(value, McpSchema.JSONRPCNotification.class);
                }
            } else {
                mapper.convertValue(value, McpSchema.JSONRPCResponse.class);
            }
        } catch (RuntimeException e) {
            return INVALID_REQUEST;
        }
        return 0;
    }

    private void refuse(int code) throws IOException {
        String message = code == PARSE_ERROR ? "Parse error" : "Invalid Request";
        byte[] reply = ("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":" + code + ",\"message\":\""
                + message + "\"}}\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(reply);
            out.flush();
        }
        LOG.warn("stdio: refused a malformed inbound message ({})", code);
    }

    /** A bounded hand-off from the guard's reader thread to the SDK's, so a slow SDK holds the guard back. */
    private static final class LineInputStream extends InputStream {

        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_LINES);
        private byte[] current = new byte[0];
        private int position;
        private boolean ended;

        void put(byte[] line) throws InterruptedException {
            queue.put(line);
        }

        void finish() {
            try {
                queue.put(EOF);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Makes {@code current} non-empty, or sets {@code ended}. */
        private void fill() throws IOException {
            while (position >= current.length && !ended) {
                byte[] next;
                try {
                    next = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                if (next == EOF) {
                    ended = true;
                } else {
                    current = next;
                    position = 0;
                }
            }
        }

        @Override
        public int read() throws IOException {
            fill();
            return ended && position >= current.length ? -1 : current[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            fill();
            if (position >= current.length) {
                return -1;
            }
            int n = Math.min(length, current.length - position);
            System.arraycopy(current, position, target, offset, n);
            position += n;
            return n;
        }
    }
}
