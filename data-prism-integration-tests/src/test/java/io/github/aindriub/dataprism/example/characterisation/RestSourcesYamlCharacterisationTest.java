package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.connectors.rest.RestSource;
import io.github.aindriub.dataprism.connectors.rest.RestSources;
import io.github.aindriub.dataprism.connectors.rest.RestSourcesConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link RestSources#fromYaml} does today (Jackson 3 YAML, YAML 1.2 reading rules; decision D-167-1 accepted
 * the change from Jackson 2 and YAML 1.1). The expected text is what the current code printed. The scalar probes use the TLS
 * {@code key-store} path, the one place a plain scalar's text is observable in the result.
 */
class RestSourcesYamlCharacterisationTest {

    private static final String SOURCE =
            "sources:\n  s:\n    base-url: https://source.example.invalid\n    path: /things/{subject}\n";

    private static String tls(String keyStore) {
        return "tls:\n  key-store: " + keyStore + "\n  key-store-password-env: PATH\n  trust-store: ts.p12\n"
                + "  trust-store-password-env: PATH\n  store-type: PKCS12\n";
    }

    private static String render(InputStream in) {
        RestSourcesConfig config = RestSources.fromYaml(in);
        StringBuilder out = new StringBuilder();
        new TreeMap<>(config.sources()).forEach((name, s) -> out.append(name).append(": baseUrl=").append(s.baseUrl())
                .append(" path=").append(s.pathTemplate()).append(" timeout=").append(s.timeout())
                .append(" requireHttps=").append(s.requireHttps()).append(" header=").append(s.correlationHeader()));
        if (config.tls() != null) {
            out.append(" tls.keyStore=").append(config.tls().keyStore())
                    .append(" tls.trustStore=").append(config.tls().trustStore());
        }
        return out.toString();
    }

    private static String outcome(String yaml) {
        return Observe.outcome(() -> render(Observe.yaml(yaml)));
    }

    @Test
    @DisplayName("duplicate key: refused, IllegalArgumentException \"DUPLICATE_CONFIG_KEY: ...\" (base-url given twice)")
    void duplicateKey() {
        assertThat(outcome("sources:\n  s:\n    base-url: https://first.example.invalid\n"
                + "    base-url: https://second.example.invalid\n    path: /things/{subject}\n"))
                .isEqualTo("refused IllegalArgumentException \"DUPLICATE_CONFIG_KEY: source configuration has a duplicate key 'base-u\"");
    }

    @Test
    @DisplayName("yes, no, on and off are read as text, not booleans (YAML 1.2); only true and false, in any case, are booleans")
    void booleanSpellings() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS, s -> SOURCE + tls(s),
                RestSourcesYamlCharacterisationTest::render)).isEqualTo("yes => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=yes tls.trustStore=ts.p12\n"
                + "no => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=no tls.trustStore=ts.p12\n"
                + "on => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=on tls.trustStore=ts.p12\n"
                + "off => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=off tls.trustStore=ts.p12\n"
                + "y => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=y tls.trustStore=ts.p12\n"
                + "n => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=n tls.trustStore=ts.p12\n"
                + "True => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=True tls.trustStore=ts.p12\n"
                + "FALSE => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=FALSE tls.trustStore=ts.p12\n");
    }

    @Test
    @DisplayName("a leading-zero number such as 010 or 0777 is read as written, not as octal (YAML 1.2)")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS, s -> SOURCE + tls(s),
                RestSourcesYamlCharacterisationTest::render)).isEqualTo("010 => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=010 tls.trustStore=ts.p12\n"
                + "0o10 => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=0o10 tls.trustStore=ts.p12\n"
                + "0777 => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=0777 tls.trustStore=ts.p12\n");
    }

    @Test
    @DisplayName("unknown top-level key: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownTopLevelKey() {
        assertThat(outcome("extra: 1\n" + SOURCE)).isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: source configuration has an unknown key 'extra'\"");
    }

    @Test
    @DisplayName("unknown nested key in a source: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownNestedKey() {
        assertThat(outcome(SOURCE + "    surprise: 1\n"))
                .isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: source configuration sources.s has an unknown key \"");
    }

    @Test
    @DisplayName("unknown nested key in tls: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownNestedKeyInTls() {
        assertThat(outcome(SOURCE + tls("ks.p12") + "  surprise: 2\n"))
                .isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: tls configuration has an unknown key 'surprise'\"");
    }

    @Test
    @DisplayName("a number or boolean where a string is expected (the tls key-store) is refused, \"NON_STRING_CONFIG_SCALAR: ...\"; the quoted form is accepted")
    void nonStringScalarsInAStringField() {
        assertThat(Observe.table(java.util.List.of("1", "true", "1.5", "\"1\""), s -> SOURCE + tls(s),
                RestSourcesYamlCharacterisationTest::render)).isEqualTo("1 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: tls configuration key-store must be a quoted\"\n"
                + "true => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: tls configuration key-store must be a quoted\"\n"
                + "1.5 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: tls configuration key-store must be a quoted\"\n"
                + "\"1\" => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=1 tls.trustStore=ts.p12\n");
    }
}
