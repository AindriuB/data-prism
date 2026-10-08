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
 * What {@link RestSources#fromYaml} does today (Jackson 2 YAML, SnakeYAML, YAML 1.1 reading
 * rules). The expected text is what the current code printed. The scalar probes use the TLS
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
    @DisplayName("duplicate key: last wins, silently (second base-url is used)")
    void duplicateKey() {
        assertThat(outcome("sources:\n  s:\n    base-url: https://first.example.invalid\n"
                + "    base-url: https://second.example.invalid\n    path: /things/{subject}\n"))
                .isEqualTo("ok s: baseUrl=https://second.example.invalid path=/things/{subject} timeout=PT3S requireHttps=false header=null");
    }

    @Test
    @DisplayName("as the key-store path text, yes, on and True read as true; no, off and FALSE read as false; y and n stay the text y and n (YAML 1.1 booleans, but only the long forms); yes/no/on/off arrive as true/false text")
    void booleanSpellings() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS, s -> SOURCE + tls(s),
                RestSourcesYamlCharacterisationTest::render)).isEqualTo("yes => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=true tls.trustStore=ts.p12\n"
                + "no => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=false tls.trustStore=ts.p12\n"
                + "on => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=true tls.trustStore=ts.p12\n"
                + "off => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=false tls.trustStore=ts.p12\n"
                + "y => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=y tls.trustStore=ts.p12\n"
                + "n => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=n tls.trustStore=ts.p12\n"
                + "True => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=true tls.trustStore=ts.p12\n"
                + "FALSE => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=false tls.trustStore=ts.p12\n");
    }

    @Test
    @DisplayName("as the key-store path text, 010 reads as decimal 8 and 0777 as decimal 511 (YAML 1.1 octal); 0o10 stays the text 0o10")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS, s -> SOURCE + tls(s),
                RestSourcesYamlCharacterisationTest::render)).isEqualTo("010 => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=8 tls.trustStore=ts.p12\n"
                + "0o10 => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=0o10 tls.trustStore=ts.p12\n"
                + "0777 => ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=511 tls.trustStore=ts.p12\n");
    }

    @Test
    @DisplayName("unknown top-level key: ignored silently")
    void unknownTopLevelKey() {
        assertThat(outcome("extra: 1\n" + SOURCE)).isEqualTo("ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=false header=null");
    }

    @Test
    @DisplayName("unknown nested key (in a source and in tls): ignored silently")
    void unknownNestedKey() {
        assertThat(outcome(SOURCE + "    surprise: 1\n" + tls("ks.p12") + "  surprise: 2\n"))
                .isEqualTo("ok s: baseUrl=https://source.example.invalid path=/things/{subject} timeout=PT3S requireHttps=true header=null tls.keyStore=ks.p12 tls.trustStore=ts.p12");
    }
}
