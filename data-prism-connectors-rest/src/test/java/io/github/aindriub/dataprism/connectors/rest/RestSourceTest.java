package io.github.aindriub.dataprism.connectors.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestSourceTest {

    private static RestDataSourceAdapter<String> adapter(String base, String path) {
        return new RestDataSourceAdapter<>(
                new RestSource("s", URI.create(base), path, Duration.ofSeconds(2)),
                RestClient.create(), String.class);
    }

    @Test
    @DisplayName("a subject id becomes one encoded path segment")
    void subjectIsASinglePathSegment() {
        assertThat(adapter("https://customer.internal", "/customers/{subject}").uriFor("123"))
                .hasToString("https://customer.internal/customers/123");
    }

    @Test
    @DisplayName("a hostile subject id cannot escape the configured path")
    void hostileSubjectCannotEscape() {
        var a = adapter("https://customer.internal", "/customers/{subject}");

        // Traversal: must stay inside /customers, not climb to /admin.
        assertThat(a.uriFor("../../admin").toString())
                .startsWith("https://customer.internal/customers/")
                .doesNotContain("/admin");

        // A query string: must not start one.
        assertThat(a.uriFor("1?role=admin").toString())
                .startsWith("https://customer.internal/customers/")
                .doesNotContain("?role=");

        // A fragment, and an attempt at a second path segment.
        assertThat(a.uriFor("1#x").toString()).doesNotContain("#x");
        assertThat(a.uriFor("a/b").toString()).doesNotContain("/customers/a/b");

        // The host is configuration and stays configuration.
        assertThat(a.uriFor("evil.example.com/x").getHost()).isEqualTo("customer.internal");
    }

    @Test
    @DisplayName("a source with no HTTP scheme or no host is refused at construction")
    void refusesNonHttpSources() {
        // file: and jar: are the shapes that turn an SSRF into a local read.
        assertThatThrownBy(() -> new RestSource("s", URI.create("file:///etc/passwd"),
                "/x/{subject}", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-HTTP");

        assertThatThrownBy(() -> new RestSource("s", URI.create("https:///nohost"),
                "/x/{subject}", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no host");
    }

    @Test
    @DisplayName("a path template must name the subject exactly once")
    void pathTemplateMustNameTheSubjectOnce() {
        assertThatThrownBy(() -> new RestSource("s", URI.create("https://h"),
                "/customers", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{subject}");

        // Two substitution points make it ambiguous which part a caller steers.
        assertThatThrownBy(() -> new RestSource("s", URI.create("https://h"),
                "/{subject}/x/{subject}", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly once");
    }

    @Test
    @DisplayName("sources load from YAML, and a malformed one fails at startup")
    void loadsFromYaml() {
        var sources = RestSources.fromYaml(new ByteArrayInputStream("""
                sources:
                  customer-api:
                    base-url: https://customer.internal
                    path: /customers/{subject}
                    timeout: PT2S
                  account-api:
                    base-url: https://account.internal
                    path: /accounts/by-customer/{subject}
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(sources).containsOnlyKeys("customer-api", "account-api");
        assertThat(sources.get("customer-api").timeout()).isEqualTo(Duration.ofSeconds(2));
        // An unstated timeout gets a default rather than none.
        assertThat(sources.get("account-api").timeout()).isEqualTo(Duration.ofSeconds(3));

        // A source that silently did not exist would look like a subject the
        // system genuinely does not hold, so this has to fail loudly.
        assertThatThrownBy(() -> RestSources.fromYaml(new ByteArrayInputStream("""
                sources:
                  broken: { path: "/x/{subject}" }
                """.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no base-url");

        assertThatThrownBy(() -> RestSources.fromYaml(new ByteArrayInputStream("""
                sources:
                  broken: { base-url: "https://h", path: "/x/{subject}", timeout: "2 seconds" }
                """.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }
}
