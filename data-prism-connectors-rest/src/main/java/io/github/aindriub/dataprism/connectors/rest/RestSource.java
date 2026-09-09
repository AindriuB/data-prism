package io.github.aindriub.dataprism.connectors.rest;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * One HTTP source, configured server-side.
 *
 * <p>The base URL and the path template come from configuration and nothing else.
 * A caller supplies a subject identifier, which is substituted as a single path
 * variable and URL-encoded on the way in; it can never contribute a host, a
 * scheme, a query string or a path segment.
 *
 * <p>That constraint is the point of this type existing rather than the adapter
 * simply taking a {@code URI}. A tool that accepted a URL from its caller would
 * let a model reach any host the platform can reach — the platform's network
 * position is precisely what makes it worth attacking. See docs/pack.md §36.
 *
 * @param pathTemplate a path containing exactly one {@code {subject}} placeholder,
 *                     e.g. {@code /customers/{subject}}
 * @param requireHttps when {@code true}, an {@code http} base URL is refused at
 *                     construction rather than permitted; production sources loaded
 *                     from configuration set this whenever TLS is required, and it
 *                     defaults to {@code false} only through the compatibility
 *                     constructor used by the in-process stub tests below
 */
public record RestSource(String name, URI baseUrl, String pathTemplate, Duration timeout,
                          boolean requireHttps) {

    private static final String PLACEHOLDER = "{subject}";

    public RestSource {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(pathTemplate, "pathTemplate");
        Objects.requireNonNull(timeout, "timeout");

        String scheme = baseUrl.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException(
                    "source " + name + " has a non-HTTP base URL scheme: " + scheme);
        }
        if (requireHttps && scheme.equals("http")) {
            throw new IllegalArgumentException(
                    "source " + name + " requires https but its base URL is " + baseUrl);
        }
        if (baseUrl.getHost() == null) {
            throw new IllegalArgumentException("source " + name + " has no host");
        }
        if (!pathTemplate.contains(PLACEHOLDER)) {
            throw new IllegalArgumentException("source " + name
                    + " path template must contain " + PLACEHOLDER);
        }
        if (pathTemplate.indexOf(PLACEHOLDER) != pathTemplate.lastIndexOf(PLACEHOLDER)) {
            // More than one substitution point makes it ambiguous which part of
            // the path a caller is steering.
            throw new IllegalArgumentException("source " + name
                    + " path template must contain " + PLACEHOLDER + " exactly once");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("source " + name + " timeout must be positive");
        }
    }

    /**
     * Permissive constructor: {@code http} is allowed. Used by the in-process
     * stub tests, whose loopback server has no certificate to present. Sources
     * built from configuration go through {@link RestSources#fromYaml}, which
     * always states the mode explicitly rather than relying on this default.
     */
    public RestSource(String name, URI baseUrl, String pathTemplate, Duration timeout) {
        this(name, baseUrl, pathTemplate, timeout, false);
    }
}
