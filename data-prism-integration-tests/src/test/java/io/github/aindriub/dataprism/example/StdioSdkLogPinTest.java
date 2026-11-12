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
 * The MCP SDK logs raw JSON-RPC messages at DEBUG, client arguments included.
 * The stdio launcher pins {@code io.modelcontextprotocol} to WARN in
 * {@code simplelogger.properties}; this runs it in a child JVM with the default
 * level at DEBUG and scans stderr for a synthetic sentinel.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class StdioSdkLogPinTest {

    private static final String SENTINEL = "SENTINEL-SUBJECT-7f3a91c2";
    private static final long TIMEOUT_SECONDS = 60;

    @Test
    @DisplayName("root DEBUG on stdio does not put a client argument in stderr")
    void pinKeepsClientArgumentsOutOfStderr() throws Exception {
        assertThat(runChild(List.of("-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"), validCalls(), true))
                .doesNotContain(SENTINEL);
    }

    @Test
    @DisplayName("positive control: naming the SDK logger at debug puts the sentinel in stderr")
    void explicitOverrideLeavesTheSentinelSoTheScanIsNotVacuous() throws Exception {
        assertThat(runChild(List.of("-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
                "-Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol=debug"), validCalls(), true)).contains(SENTINEL);
    }

    private static List<String> validCalls() {
        return List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                        + "\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"pin-test\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_entity_context\","
                        + "\"arguments\":{\"subjectId\":\"" + SENTINEL + "\"}}}");
    }

    /**
     * Runs the launcher, sends each line, and waits for the response to a final
     * well-formed {@code ping} (id 99) so every earlier line has been handled.
     * Returns everything the child wrote to stderr.
     */
    private static String runChild(List<String> jvmArgs, List<String> lines, boolean strict)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ExampleApplication.class.getName());
        Process process = new ProcessBuilder(command).start();
        StringBuffer stderr = new StringBuffer();
        StringBuffer stdout = new StringBuffer();
        BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        AtomicInteger endsOfStream = new AtomicInteger();
        Thread errReader = new Thread(() -> drain(process.getErrorStream(), stderr, null, readFailure, endsOfStream));
        Thread outReader = new Thread(() -> drain(process.getInputStream(), stdout, responses, readFailure, endsOfStream));
        errReader.setDaemon(true);
        outReader.setDaemon(true);
        errReader.start();
        outReader.start();
        try {
            Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            for (String line : lines) {
                in.write(line + "\n");
                in.flush();
                if (line.contains("\"id\":1,")) {
                    awaitResponse(responses, "\"id\":1", stdout, stderr);
                }
            }
            in.write("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}\n");
            in.flush();
            if (strict) {
                awaitResponse(responses, "\"id\":99", stdout, stderr);
            } else {
                Thread.sleep(5000); // measurement: the ping may never be answered after a malformed line
            }
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
        return stderr.toString();
    }

    /** Waits at most {@link #TIMEOUT_SECONDS} for a stdout line containing the marker, so a stuck child fails the test. */
    private static void awaitResponse(BlockingQueue<String> responses, String marker, StringBuffer stdout,
            StringBuffer stderr) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (true) {
            long left = deadline - System.nanoTime();
            String line = left <= 0 ? null : responses.poll(left, TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new AssertionError("no response containing " + marker + " within " + TIMEOUT_SECONDS
                        + "s; stdout:\n" + stdout + "\nstderr:\n" + stderr);
            }
            if (line.contains(marker)) {
                return;
            }
        }
    }

    private static void drain(InputStream stream, StringBuffer into, BlockingQueue<String> lines, AtomicReference<Throwable> failure, AtomicInteger endsOfStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                into.append(line).append('\n');
                if (lines != null) {
                    lines.add(line);
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
