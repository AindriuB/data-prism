package io.github.aindriub.dataprism.server.boot;

import io.github.aindriub.dataprism.server.operator.OperatorHarness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Starts the real server through {@link OperatorHarness} (task 142), with a single embedded member. */
final class Boot4Servers {

    private Boot4Servers() {
    }

    static OperatorHarness start(Path tempDir, String... extraArguments) throws Exception {
        List<String> cluster = List.of(
                "--dataprism.hazelcast.cluster-name=boot4-guards-" + System.nanoTime(),
                "--dataprism.hazelcast.join.mode=none",
                "--dataprism.hazelcast.member.port=" + freePort());
        return OperatorHarness.startMember(tempDir, new ArrayList<>(cluster), extraArguments);
    }

    /** A GET on the MCP port; {@code token} may be null. */
    static HttpResponse<String> mcpGet(OperatorHarness server, String path, String token) throws Exception {
        return get(server.mcpPort, path, token);
    }

    static HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static int freePort() throws Exception {
        return OperatorHarness.freePort();
    }
}
