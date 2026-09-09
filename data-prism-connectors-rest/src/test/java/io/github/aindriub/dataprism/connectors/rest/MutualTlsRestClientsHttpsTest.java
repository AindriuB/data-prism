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
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
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
    private Path clientTrustStorePath;

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
        this.clientTrustStorePath = clientTrustStore;

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
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("a client built by the factory with the client certificate fetches a record")
    void factoryBuiltClientCompletesTheHandshake() {
        URI base = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
        var adapter = new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(5), true),
                MutualTlsRestClients.build(clientTlsSettings), String.class);

        String body = adapter.fetch(DataRequest.of("CUSTOMER", "123"));

        assertThat(body).contains("\"customerId\":\"123\"");
    }

    @Test
    @DisplayName("a client with no certificate is refused by the server")
    void clientWithoutCertificateIsRefused() throws Exception {
        SSLContext trustOnly = sslContext(null, clientTrustStorePath, false);
        RestClient noCertClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().sslContext(trustOnly).build()))
                .build();

        URI base = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
        var adapter = new RestDataSourceAdapter<>(
                new RestSource("customer-api", base, "/customers/{subject}", Duration.ofSeconds(5), true),
                noCertClient, String.class);

        assertThatThrownBy(() -> adapter.fetch(DataRequest.of("CUSTOMER", "123")))
                .isInstanceOf(RuntimeException.class);
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
