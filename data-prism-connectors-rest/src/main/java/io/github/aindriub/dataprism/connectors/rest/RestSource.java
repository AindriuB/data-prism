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
 */
public record RestSource(String name, URI baseUrl, String pathTemplate, Duration timeout) {

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
}
