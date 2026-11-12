package io.github.aindriub.dataprism.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A malformed inbound line on stdio must put no client content in stdout or stderr, even with every
 * logger at TRACE, and must be answered without ending the session: a {@code ping} sent after it is
 * still answered. The child is the real launcher, so the factory production uses is what is measured.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class StdioParseErrorLeakTest {

    private static final String SENTINEL = "SENTINEL-SUBJECT-7f3a91c2";
    private static final long TIMEOUT_SECONDS = 60;
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"leak\","
            + "\"version\":\"1\"}}}";

    @Test
    @DisplayName("A: an unterminated object holding the sentinel is refused with -32700")
    void unterminatedObject() throws Exception {
        Result result = run("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{\"x\":\"" + SENTINEL);
        assertGuardRefused(result, "-32700");
    }

    @Test
    @DisplayName("B: a bare alphanumeric token is refused with -32700")
    void bareToken() throws Exception {
        assertGuardRefused(run("SENTINELSUBJECT7f3a91c2"), "-32700");
    }

    @Test
    @DisplayName("C: a bare token with punctuation is refused with -32700")
    void bareTokenWithPunctuation() throws Exception {
        assertGuardRefused(run(SENTINEL), "-32700");
    }

    @Test
    @DisplayName("D: valid JSON that is not a JSON-RPC message is refused with -32600")
    void validJsonNotJsonRpc() throws Exception {
        assertGuardRefused(run("{\"jsonrpc\":\"2.0\",\"subjectId\":\"" + SENTINEL + "\"}"), "-32600");
    }

    @Test
    @DisplayName("F: a method that is an object is refused with -32600")
    void methodWrongType() throws Exception {
        assertGuardRefused(run("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":{\"a\":\"" + SENTINEL + "\"}}"), "-32600");
    }

    @Test
    @DisplayName("F: an error whose code is a string is refused with -32600")
    void errorCodeWrongType() throws Exception {
        assertGuardRefused(run("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":\"" + SENTINEL + "\",\"message\":\"m\"}}"), "-32600");
    }

    @Test
    @DisplayName("F: an error that is a string is refused with -32600")
    void errorWrongType() throws Exception {
        assertGuardRefused(run("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":\"" + SENTINEL + "\"}"), "-32600");
    }

    @Test
    @DisplayName("E: tools/call with string arguments gets the SDK's error reply, with no sentinel")
    void toolCallArgumentsString() throws Exception {
        Result result = runPinned("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_entity_context\",\"arguments\":\"" + SENTINEL + "\"}}");
        assertSdkErrorReply(result);
    }

    @Test
    @DisplayName("E: initialize with string capabilities gets the SDK's error reply, with no sentinel")
    void initializeCapabilitiesString() throws Exception {
        Result result = runPinned("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":\"" + SENTINEL
                + "\",\"clientInfo\":{\"name\":\"leak\",\"version\":\"1\"}}}");
        assertSdkErrorReply(result);
    }

    private static void assertGuardRefused(Result result, String code) {
        assertThat(result.stdout).anyMatch(line -> line.contains("\"id\":null") && line.contains("\"code\":" + code));
        assertThat(result.stdout).anyMatch(line -> line.contains("\"id\":99"));
        assertThat(result.stderr).contains("stdio: refused a malformed inbound message (" + code + ")");
        assertNoLeak(result);
    }

    private static void assertSdkErrorReply(Result result) {
        assertThat(result.stdout).anyMatch(line -> line.contains("\"id\":2") && line.contains("\"error\""));
        assertThat(result.stdout).anyMatch(line -> line.contains("\"id\":99"));
        assertNoLeak(result);
    }

    private static void assertNoLeak(Result result) {
        for (String text : List.of(String.join("\n", result.stdout), result.stderr)) {
            assertThat(text).doesNotContain(SENTINEL).doesNotContain("SENTINEL")
                    .doesNotContain("Error processing inbound message");
        }
    }

    private record Result(List<String> stdout, String stderr) {
    }

    /**
     * Runs the launcher at TRACE, sends initialize, the case line and a {@code ping} with id 99, and
     * waits for the ping's reply so every earlier line has been handled.
     */
    private static Result run(String caseLine) throws IOException, InterruptedException {
        return run(caseLine, true);
    }

    /** As {@link #run}, keeping the WARN pin on the SDK's loggers: E lines are forwarded, so its DEBUG is a named residual. */
    private static Result runPinned(String caseLine) throws IOException, InterruptedException {
        return run(caseLine, false);
    }

    private static Result run(String caseLine, boolean sdkDebug) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dorg.slf4j.simpleLogger.defaultLogLevel=trace");
        if (sdkDebug) {
            command.add("-Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol=debug");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ExampleApplication.class.getName());
        Process process = new ProcessBuilder(command).start();
        StringBuffer stderr = new StringBuffer();
        List<String> stdout = new java.util.concurrent.CopyOnWriteArrayList<>();
        BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        AtomicInteger endsOfStream = new AtomicInteger();
        Thread errReader = new Thread(() -> drain(process.getErrorStream(), stderr, null, null, readFailure, endsOfStream));
        Thread outReader = new Thread(() -> drain(process.getInputStream(), new StringBuffer(), responses, stdout, readFailure, endsOfStream));
        errReader.setDaemon(true);
        outReader.setDaemon(true);
        errReader.start();
        outReader.start();
        try {
            Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            in.write(INITIALIZE + "\n");
            in.flush();
            awaitResponse(responses, "\"id\":1", stdout, stderr);
            in.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            in.write(caseLine + "\n");
            in.write("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}\n");
            in.flush();
            awaitResponse(responses, "\"id\":99", stdout, stderr);
            in.close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the child did not exit by itself within 10s of stdin closing; stderr:\n" + stderr);
            }
            assertThat(process.exitValue()).as("the child's exit status after stdin closed").isZero();
        } finally {
            // cleanup only: on the normal path the child has already exited
            process.toHandle().destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
        joinToEndOfStream(errReader, outReader, readFailure, endsOfStream);
        return new Result(List.copyOf(stdout), stderr.toString());
    }

    private static void awaitResponse(BlockingQueue<String> responses, String marker, List<String> stdout,
            StringBuffer stderr) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (true) {
            long left = deadline - System.nanoTime();
            String line = left <= 0 ? null : responses.poll(left, TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new AssertionError("no response containing " + marker + " within " + TIMEOUT_SECONDS
                        + "s; stdout:\n" + String.join("\n", stdout) + "\nstderr:\n" + stderr);
            }
            if (line.contains(marker)) {
                return;
            }
        }
    }

    private static void drain(InputStream stream, StringBuffer into, BlockingQueue<String> queue,
            List<String> lines, AtomicReference<Throwable> failure, AtomicInteger endsOfStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                into.append(line).append('\n');
                if (lines != null) {
                    lines.add(line);
                }
                if (queue != null) {
                    queue.add(line);
                }
            }
            endsOfStream.incrementAndGet();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    /**
     * Waits for both readers to reach end of stream and fails unless both returned null from readLine with no Throwable: a
     * leak check must never pass on output that was not fully read.
     */
    private static void joinToEndOfStream(Thread errReader, Thread outReader,
            AtomicReference<Throwable> readFailure, AtomicInteger endsOfStream) throws InterruptedException {
        errReader.join(5000);
        outReader.join(5000);
        if (errReader.isAlive() || outReader.isAlive()) {
            throw new AssertionError("child output readers did not reach end of stream");
        }
        if (readFailure.get() != null) {
            throw new AssertionError("a child output reader ended on " + readFailure.get().getClass().getName()
                    + ", so the output was not fully read", readFailure.get());
        }
        if (endsOfStream.get() != 2) {
            throw new AssertionError("child output readers did not both reach end of stream (readLine returned null)");
        }
    }
}
