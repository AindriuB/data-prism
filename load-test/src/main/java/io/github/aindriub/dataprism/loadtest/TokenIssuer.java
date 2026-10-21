package io.github.aindriub.dataprism.loadtest;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The harness's own token issuer: an RSA key generated when the run starts, its public half served as a JWKS
 * over HTTPS (the server refuses a plaintext JWKS location), and RS256 tokens minted for the run's one caller.
 *
 * <p>Nothing here is persisted anywhere but the run's temporary directory, and the private key never leaves
 * this process. The TLS certificate the JWKS endpoint (and the synthetic sources) present is generated with {@code keytool} into the
 * same directory, and the server is started trusting only that certificate.
 */
final class TokenIssuer implements AutoCloseable {

    static final String ISSUER = "https://issuer.loadtest.invalid";
    static final String AUDIENCE = "data-prism-load-test";
    static final String PURPOSE = "investigation";
    /** The case id names the privacy scope; a long run rotates it by numbering it: {@code CASE-LOADTEST-1}, -2... */
    static final String CASE_ID_PREFIX = "CASE-LOADTEST-";
    static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    /** Longest any one {@code keytool} call may take. */
    static final Duration KEYTOOL_BOUND = Duration.ofSeconds(60);

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair signingKey;
    private final String keyId = UUID.randomUUID().toString();
    private final HttpsServer jwks;
    private final SSLContext tls;
    private final Path trustStore;
    private final String storePassword = UUID.randomUUID().toString();

    TokenIssuer(Path workDir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();

        Path keyStore = workDir.resolve("jwks-server.p12");
        Path certificate = workDir.resolve("jwks-server.cer");
        trustStore = workDir.resolve("jwks-trust.p12");
        keytool("-genkeypair", "-alias", "jwks", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12", "-storepass", storePassword,
                "-keypass", storePassword, "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1");
        keytool("-exportcert", "-alias", "jwks", "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", storePassword, "-file", certificate.toString());
        keytool("-importcert", "-alias", "jwks", "-file", certificate.toString(), "-keystore", trustStore.toString(),
                "-storetype", "PKCS12", "-storepass", storePassword, "-noprompt");

        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStore)) {
            serverKeys.load(input, storePassword.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, storePassword.toCharArray());
        tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);

        byte[] body = jwksJson().getBytes(StandardCharsets.UTF_8);
        jwks = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.setHttpsConfigurator(new HttpsConfigurator(tls));
        jwks.createContext("/jwks", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        jwks.start();
    }

    /**
     * The TLS context presenting the run's throwaway certificate. The synthetic sources use it too: the
     * server refuses a plaintext source URL, and its truststore trusts only this certificate.
     */
    SSLContext tls() {
        return tls;
    }

    String jwksUri() {
        return "https://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks";
    }

    Path trustStore() {
        return trustStore;
    }

    String trustStorePassword() {
        return storePassword;
    }

    private String jwksJson() {
        RSAPublicKey publicKey = (RSAPublicKey) signingKey.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + keyId + "\","
                + "\"n\":\"" + B64URL.encodeToString(unsigned(publicKey.getModulus().toByteArray())) + "\","
                + "\"e\":\"" + B64URL.encodeToString(unsigned(publicKey.getPublicExponent().toByteArray())) + "\"}]}";
    }

    /** A token for the run's one caller, valid for {@code lifetime}, in the privacy scope named by {@code caseId}. */
    String mintToken(Duration lifetime, String caseId) throws Exception {
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + keyId + "\"}";
        String claims = "{\"sub\":\"load-test-principal\",\"azp\":\"load-test-client\",\"roles\":[\"investigator\"],"
                + "\"purpose\":\"" + PURPOSE + "\",\"case_id\":\"" + caseId + "\","
                + "\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\","
                + "\"exp\":" + Instant.now().plus(lifetime).getEpochSecond() + "}";
        String signingInput = B64URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + B64URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64URL.encodeToString(signature.sign());
    }

    private static byte[] unsigned(byte[] twosComplement) {
        if (twosComplement.length > 1 && twosComplement[0] == 0) {
            byte[] trimmed = new byte[twosComplement.length - 1];
            System.arraycopy(twosComplement, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return twosComplement;
    }

    private static void keytool(String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        try {
            Bounded.command(java.util.List.of(command), KEYTOOL_BOUND);
        } catch (IOException failed) {
            throw new IOException("keytool " + arguments[0] + " failed: " + failed.getMessage(), failed);
        }
    }

    @Override
    public void close() {
        jwks.stop(0);
    }
}
