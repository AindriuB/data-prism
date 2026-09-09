package io.github.aindriub.dataprism.connectors.rest;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where the outbound mTLS material lives, and which environment variable holds
 * each store's password.
 *
 * <p>A password is never a configuration value: this record carries the name of
 * the environment variable to read, not the secret itself, and validates at
 * construction that the named variable is actually set — so a missing secret is
 * a startup failure naming the variable, never surfaced later as an opaque TLS
 * handshake failure, and never a value written to a log or exception message.
 *
 * @param keyStore              path to the store presenting this client's certificate and key
 * @param trustStore            path to the store of anchors trusted for the source's certificate
 * @param storeType             the store format shared by both stores, e.g. {@code PKCS12}
 * @param keyStorePasswordEnv   name of the environment variable holding the key store password
 * @param trustStorePasswordEnv name of the environment variable holding the trust store password
 */
public record TlsSettings(Path keyStore, Path trustStore, String storeType,
                           String keyStorePasswordEnv, String trustStorePasswordEnv) {

    public TlsSettings {
        Objects.requireNonNull(keyStore, "keyStore");
        Objects.requireNonNull(trustStore, "trustStore");
        if (storeType == null || storeType.isBlank()) {
            throw new IllegalArgumentException("tls configuration has no store-type");
        }
        requireVariableSet(keyStorePasswordEnv, "key-store-password-env");
        requireVariableSet(trustStorePasswordEnv, "trust-store-password-env");
    }

    private static void requireVariableSet(String variable, String key) {
        if (variable == null || variable.isBlank()) {
            throw new IllegalArgumentException("tls configuration has no " + key);
        }
        if (System.getenv(variable) == null) {
            throw new IllegalArgumentException(
                    "environment variable " + variable + ", named by " + key
                            + ", is not set");
        }
    }

    char[] keyStorePassword() {
        return passwordOf(keyStorePasswordEnv);
    }

    char[] trustStorePassword() {
        return passwordOf(trustStorePasswordEnv);
    }

    private static char[] passwordOf(String variable) {
        String value = System.getenv(variable);
        if (value == null) {
            // Checked at construction; only reachable if the environment changed
            // underneath a long-lived process between then and now.
            throw new IllegalStateException(
                    "environment variable " + variable + " is no longer set");
        }
        return value.toCharArray();
    }
}
