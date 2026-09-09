package io.github.aindriub.dataprism.connectors.rest;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * Builds a {@link RestClient} whose outbound connections present a client
 * certificate and trust only the anchors named in {@link TlsSettings}.
 *
 * <p>There is deliberately no fallback to the platform's default trust store or
 * an unauthenticated client: a store that cannot be opened, or a password that
 * does not match it, is a startup failure. Nothing is returned in that case, so
 * a source can never be reached over a connection this factory could not fully
 * verify.
 */
public final class MutualTlsRestClients {

    private MutualTlsRestClients() {
    }

    public static RestClient build(TlsSettings settings) {
        SSLContext sslContext = sslContext(settings);
        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    private static SSLContext sslContext(TlsSettings settings) {
        char[] keyStorePassword = settings.keyStorePassword();
        char[] trustStorePassword = settings.trustStorePassword();
        try {
            KeyStore keyStore = loadStore(settings.keyStore(), settings.storeType(), keyStorePassword);
            KeyStore trustStore = loadStore(settings.trustStore(), settings.storeType(), trustStorePassword);

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, keyStorePassword);

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("mTLS material for key store " + settings.keyStore()
                    + " or trust store " + settings.trustStore() + " could not be loaded", e);
        } finally {
            Arrays.fill(keyStorePassword, '\0');
            Arrays.fill(trustStorePassword, '\0');
        }
    }

    private static KeyStore loadStore(Path path, String type, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }
}
