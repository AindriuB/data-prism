package io.github.aindriub.dataprism.connectors.rest;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.github.aindriub.dataprism.core.DataRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The factory against a real TLS handshake, in both directions.
 *
 * <p>An {@code HttpsServer} demands a client certificate, exactly like an
 * enterprise API behind mTLS would. A client built by {@link
 * MutualTlsRestClients} from settings naming the right stores completes the
 * handshake and fetches a record; a client with no certificate at all is
 * refused by the server before any response body exists.
 *
 * <p>The key pairs and certificates are generated per test into a JUnit
 * {@code @TempDir} using the JDK's own {@code keytool}, so nothing is added to
 * the repository. The store password below, {@link #STORE_PASSWORD}, is a
 * literal chosen for this test run only: it protects a throwaway key pair that
 * exists solely inside this JVM process and is discarded with the temp
 * directory, so its value carries no risk if it ever appeared in a log. The
 * environment variables the settings name are populated with the same literal
 * by this module's POM (see the surefire configuration there); they must be
 * kept in sync if this literal ever changes.
 */
class MutualTlsRestClientsHttpsTest {

    private static final String STORE_PASSWORD = "dp-test-only-not-a-real-secret";
    private static final String KEY_STORE_PASSWORD_ENV = "DATA_PRISM_TEST_TLS_PASSWORD";
    private static final String TRUST_STORE_PASSWORD_ENV = "DATA_PRISM_TEST_TLS_PASSWORD";

    @TempDir
    Path tempDir;

    private HttpsServer server;
    private TlsSettings clientTlsSettings;
    private URI base;

    @BeforeEach
    void startServer() throws Exception {
        assertThat(System.getenv(KEY_STORE_PASSWORD_ENV))
                .as("test relies on the module POM's surefire env config matching STORE_PASSWORD")
                .isEqualTo(STORE_PASSWORD);

        Path serverKeyStore = tempDir.resolve("server-keystore.p12");
        Path serverTrustStore = tempDir.resolve("server-truststore.p12");
        Path clientKeyStore = tempDir.resolve("client-keystore.p12");
        Path clientTrustStore = tempDir.resolve("client-truststore.p12");
        Path serverCert = tempDir.resolve("server.cer");
        Path clientCert = tempDir.resolve("client.cer");

        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", serverKeyStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1");
        keytool("-exportcert", "-alias", "server", "-keystore", serverKeyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-file", serverCert.toString());

        keytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", clientKeyStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", "CN=mutual-tls-rest-clients-https-test-client");
        keytool("-exportcert", "-alias", "client", "-keystore", clientKeyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-file", clientCert.toString());

        keytool("-importcert", "-alias", "server", "-file", serverCert.toString(),
                "-keystore", clientTrustStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-noprompt");
        keytool("-importcert", "-alias", "client", "-file", clientCert.toString(),
                "-keystore", serverTrustStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-noprompt");

        this.clientTlsSettings = new TlsSettings(clientKeyStore, clientTrustStore, "PKCS12",
                KEY_STORE_PASSWORD_ENV, TRUST_STORE_PASSWORD_ENV);

        SSLContext serverContext = sslContext(serverKeyStore, serverTrustStore, true);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters sslParameters = serverContext.getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(true);
                params.setSSLParameters(sslParameters);
            }
        });
        server.createContext("/customers", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/customers/".length());
            byte[] body = ("{\"customerId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        this.base = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("a client built by the factory with the client certificate fetches a record")
    void factoryBuiltClientCompletesTheHandshake() {
        var adapter = new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(5), true),
                MutualTlsRestClients.build(clientTlsSettings), String.class);

        String body = adapter.fetch(DataRequest.of("CUSTOMER", "123"));

        assertThat(body).contains("\"customerId\":\"123\"");
    }

    /**
     * Built from exactly the same server, the same trust store — {@link
     * TlsSettings#trustStore()} on the very {@link #clientTlsSettings} the
     * passing test's factory call also reads — and the same {@link #base} URI
     * as {@link #factoryBuiltClientCompletesTheHandshake}. The only argument
     * that differs from that client's construction is {@code withKeyManager},
     * passed {@code false} below where the factory would install a real key
     * manager. So neither a wrong URL, a missing file nor a store password can
     * be the reason the fetch below fails: only the absent certificate can.
     *
     * <p>What the JDK's {@code HttpClient} surfaces for that refusal differs
     * by platform, because the two ends of the TCP connection race each
     * other after the server sends its fatal TLS alert. On Windows, the
     * client reliably reads the alert before the socket closes, so the cause
     * chain contains an {@link SSLException} (typically
     * {@code SSLHandshakeException}) directly. On Linux, the server tears
     * down the TCP connection before the client gets to read the alert, so
     * the client instead observes a plain end of stream, with no bytes ever
     * read: the JDK's own HTTP/1.1 header parser reports exactly that — see
     * {@code jdk.internal.net.http.Http1HeaderParser#currentStateMessage()},
     * which returns the literal {@link #LINUX_HANDSHAKE_REFUSAL_MESSAGE}
     * whenever the parser is still in its initial state when the connection
     * ends. That literal, wrapping whatever transport-level exception (an
     * {@code EOFException} or a {@code SocketException}, depending on
     * exactly how the kernel tore the connection down) triggered it, is what
     * appeared verbatim in the CI run this test now guards against. Both
     * observables share the same cross-platform property this test asserts:
     * the connection produced no HTTP response at all, only a TLS failure or
     * an immediate, contentless end of stream — which is why {@link
     * #indicatesTlsHandshakeRefusal} checks for either rather than one
     * specific exception type. A closed port instead throws a {@code
     * ConnectException} ("Connection refused"), which matches neither
     * branch, and a request that completes the handshake and reaches a 404
     * never throws a {@link ResourceAccessException} in the first place —
     * both keep failing this test, which is what distinguishes a genuine
     * handshake refusal from every other way this test could go green for
     * the wrong reason.
     */
    @Test
    @DisplayName("a client with no certificate is refused by the server with an SSL handshake failure")
    void clientWithoutCertificateIsRefused() throws Exception {
        SSLContext noKeyManager = sslContext(null, clientTlsSettings.trustStore(), false);
        RestClient noCertClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().sslContext(noKeyManager).build()))
                .build();

        var adapter = new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(5), true),
                noCertClient, String.class);

        assertThatThrownBy(() -> adapter.fetch(DataRequest.of("CUSTOMER", "123")))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(e -> assertThat(indicatesTlsHandshakeRefusal(e))
                        .as("cause chain of %s should show a TLS handshake refusal: either an "
                                + "SSLException (the Windows observable, where the client reads the "
                                + "server's fatal alert) or a cause whose message is \"%s\" (the Linux "
                                + "observable, where the server tears the connection down before the "
                                + "client can read the alert, so the JDK's HTTP/1.1 header parser sees "
                                + "the connection end having received no bytes at all)",
                                e, LINUX_HANDSHAKE_REFUSAL_MESSAGE)
                        .isTrue());
    }

    /**
     * The exact string {@code jdk.internal.net.http.Http1HeaderParser
     * #currentStateMessage()} returns when the header parser's state is
     * still {@code INITIAL} at the point the connection ends — i.e. no
     * bytes at all were read from the socket. This is the Linux spelling of
     * a refused TLS handshake; see the javadoc on {@link
     * #clientWithoutCertificateIsRefused}.
     */
    private static final String LINUX_HANDSHAKE_REFUSAL_MESSAGE = "HTTP/1.1 header parser received no bytes";

    private static boolean indicatesTlsHandshakeRefusal(Throwable throwable) {
        return causeChainContains(throwable, SSLException.class)
                || causeChainContainsMessage(throwable, LINUX_HANDSHAKE_REFUSAL_MESSAGE);
    }

    private static boolean causeChainContainsMessage(Throwable throwable, String message) {
        for (Throwable current = throwable.getCause(); current != null; current = current.getCause()) {
            if (message.equals(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean causeChainContains(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable.getCause(); current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static SSLContext sslContext(Path keyStorePath, Path trustStorePath, boolean withKeyManager)
            throws GeneralSecurityException, IOException {
        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(loadStore(trustStorePath));

        var keyManagers = withKeyManager
                ? keyManagerFactory(keyStorePath).getKeyManagers()
                : null;

        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keyManagers, trustManagerFactory.getTrustManagers(), null);
        return context;
    }

    private static KeyManagerFactory keyManagerFactory(Path keyStorePath)
            throws GeneralSecurityException, IOException {
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(loadStore(keyStorePath), STORE_PASSWORD.toCharArray());
        return keyManagerFactory;
    }

    private static KeyStore loadStore(Path path) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, STORE_PASSWORD.toCharArray());
        }
        return store;
    }

    /**
     * Shells out to the JDK's own {@code keytool} rather than pulling in a
     * certificate-building library: it is always present alongside the JVM
     * running the test, and generating an X.509 certificate by hand needs
     * exactly the kind of dependency this task's scope excludes.
     */
    private static void keytool(String... args) throws IOException, InterruptedException {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);

        List<String> command = new java.util.ArrayList<>();
        command.add(keytool.toString());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException("keytool " + args[0] + " failed: " + output);
        }
    }
}
