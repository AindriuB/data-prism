package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RestSources#fromYaml} refuses, at load time and with a stable code, a duplicate key, an unknown key,
 * trailing content, and a scalar of the wrong YAML type. A message names the key and its path and never a value.
 */
class RestSourcesStrictKeysTest {

    private static final String SOURCE =
            "sources:\n  s:\n    base-url: https://source.example.invalid\n    path: /things/{subject}\n";
    private static final String TLS = "tls:\n  key-store: ks.p12\n  key-store-password-env: PATH\n"
            + "  trust-store: ts.p12\n  trust-store-password-env: PATH\n  store-type: PKCS12\n";

    private static RestSourcesConfig load(String yaml) {
        return RestSources.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertRefused(String yaml, String prefix) {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(prefix);
    }

    @Test
    @DisplayName("a valid file with tls still loads")
    void validLoads() {
        assertThat(load(SOURCE + TLS).sources()).containsKey("s");
    }

    @Test
    @DisplayName("a duplicate top-level key is refused with DUPLICATE_CONFIG_KEY")
    void duplicateTopLevelKey() {
        assertRefused(SOURCE + SOURCE,
                "DUPLICATE_CONFIG_KEY: source configuration has a duplicate key 'sources'");
    }

    @Test
    @DisplayName("a duplicate key in a source is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicateKeyInSource() {
        assertRefused(SOURCE + "    path: /other\n",
                "DUPLICATE_CONFIG_KEY: source configuration has a duplicate key 'path' in sources.s");
    }

    @Test
    @DisplayName("a duplicate key in tls is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicateKeyInTls() {
        assertRefused(SOURCE + TLS + "  key-store: other.p12\n",
                "DUPLICATE_CONFIG_KEY: source configuration has a duplicate key 'key-store' in tls");
    }

    @Test
    @DisplayName("a duplicate source name is refused with DUPLICATE_CONFIG_KEY")
    void duplicateSourceName() {
        assertRefused(SOURCE + "  s:\n    base-url: https://other.example.invalid\n    path: /x\n",
                "DUPLICATE_CONFIG_KEY: source configuration has a duplicate key 's' in sources");
    }

    @Test
    @DisplayName("an unknown top-level key is refused with UNKNOWN_CONFIG_KEY")
    void unknownTopLevelKey() {
        assertRefused("extra: 1\n" + SOURCE,
                "UNKNOWN_CONFIG_KEY: source configuration has an unknown key 'extra'");
    }

    @Test
    @DisplayName("an unknown key in a source is refused with UNKNOWN_CONFIG_KEY, naming the path")
    void unknownKeyInSource() {
        assertRefused(SOURCE + "    timout: PT2S\n",
                "UNKNOWN_CONFIG_KEY: source configuration sources.s has an unknown key 'timout'");
    }

    @Test
    @DisplayName("an unknown key in tls is refused with UNKNOWN_CONFIG_KEY")
    void unknownKeyInTls() {
        assertRefused(SOURCE + TLS + "  surprise: 2\n",
                "UNKNOWN_CONFIG_KEY: tls configuration has an unknown key 'surprise'");
    }

    @Test
    @DisplayName("an unknown key longer than 64 characters is named cut to 64")
    void longKeyIsTruncated() {
        assertThatThrownBy(() -> load(SOURCE + "    " + "k".repeat(100) + ": 1\n"))
                .hasMessageContaining("'" + "k".repeat(64) + "'")
                .hasMessageNotContaining("k".repeat(65));
    }

    @Test
    @DisplayName("a second YAML document is refused with TRAILING_CONFIG_CONTENT")
    void secondDocument() {
        assertRefused(SOURCE + "---\n" + SOURCE, "TRAILING_CONFIG_CONTENT: source configuration ");
    }

    @Test
    @DisplayName("a tls path written as a number or boolean is refused with NON_STRING_CONFIG_SCALAR, without the value")
    void tlsPathMustBeAString() {
        for (String value : new String[] {"12345", "true", "1.5"}) {
            assertThatThrownBy(() -> load(SOURCE + TLS.replace("ks.p12", value)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("NON_STRING_CONFIG_SCALAR: tls configuration key-store must be a quoted string");
        }
        assertThat(load(SOURCE + TLS.replace("ks.p12", "\"12345\"")).tls()).isNotNull();
        assertThat(load(SOURCE + TLS.replace("ks.p12", "010")).tls().keyStore().toString()).isEqualTo("010");
    }

    @Test
    @DisplayName("a base-url, path or timeout written as a number is refused with NON_STRING_CONFIG_SCALAR")
    void sourceFieldsMustBeStrings() {
        assertRefused(SOURCE.replace("path: /things/{subject}", "path: 5"),
                "NON_STRING_CONFIG_SCALAR: source s path must be a quoted string");
        assertRefused(SOURCE.replace("https://source.example.invalid", "true"),
                "NON_STRING_CONFIG_SCALAR: source s base-url must be a quoted string");
        assertRefused(SOURCE + "    timeout: 3\n",
                "NON_STRING_CONFIG_SCALAR: source configuration sources.s.timeout must be a quoted string");
    }

    @Test
    @DisplayName("tls that is not a mapping, including an empty value, is refused with INVALID_CONFIG_SHAPE")
    void wrongShapedTls() {
        assertRefused(SOURCE + "tls:\n", "INVALID_CONFIG_SHAPE: tls configuration.tls must be a mapping");
        assertRefused(SOURCE + "tls: [a]\n", "INVALID_CONFIG_SHAPE: ");
    }

    @Test
    @DisplayName("a correlation-header written as a number is refused with NON_STRING_CONFIG_SCALAR")
    void correlationHeaderMustBeAString() {
        assertRefused(SOURCE + "    correlation-header: 1\n",
                "NON_STRING_CONFIG_SCALAR: sources.s.correlation-header must be a quoted string");
    }

    @Test
    @DisplayName("an unparseable base-url is refused without repeating it or its user-info")
    void unparseableBaseUrlDoesNotEchoCredentials() {
        assertThatThrownBy(() -> load(SOURCE.replace("https://source.example.invalid",
                "\"https://user:s3cr3t@exa mple\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("source s has an unparseable base-url")
                .hasNoCause();
    }

    @Test
    @DisplayName("an alias is refused with UNSUPPORTED_CONFIG_YAML")
    void aliasRefused() {
        assertRefused("sources: &s\n  s:\n    base-url: https://a.example.invalid\n    path: /x\nother: *s\n",
                "UNSUPPORTED_CONFIG_YAML: ");
    }

    @Test
    @DisplayName("a plaintext http base-url where https is required is refused without repeating it or its user-info")
    void plaintextBaseUrlDoesNotEchoCredentials() {
        assertThatThrownBy(() -> load(SOURCE.replace("https://source.example.invalid",
                "http://user:s3cr3t@source.example.invalid") + TLS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("source s requires https but its base URL is plaintext http")
                .hasNoCause();
    }
}
