package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.URI;
import java.util.Objects;

/**
 * Fetches one entity from one HTTP source.
 *
 * <p>The URI is built from the configured base URL and path template with the
 * subject substituted as a URI variable, so Spring encodes it. A subject of
 * {@code ../../admin} becomes a single encoded path segment rather than a
 * traversal, and one containing {@code ?} or {@code #} cannot start a query or a
 * fragment.
 *
 * <p>A 404 is data, not a failure: it means this source holds nothing for this
 * subject, which is a legitimate and common answer when a person exists in some
 * systems and not others. Returning null lets the orchestrator record NO_DATA,
 * which does not count against the source's circuit breaker. Any other error is
 * allowed to propagate so the fan-out can time it, record it and open the breaker
 * if it keeps happening.
 */
public final class RestDataSourceAdapter<T> implements DataSourceAdapter<T> {

    private static final Logger LOG = LoggerFactory.getLogger(RestDataSourceAdapter.class);

    private final RestSource source;
    private final RestClient client;
    private final Class<T> responseType;
    private final DefaultUriBuilderFactory uris;

    public RestDataSourceAdapter(RestSource source, RestClient client, Class<T> responseType) {
        this.source = Objects.requireNonNull(source, "source");
        this.client = Objects.requireNonNull(client, "client");
        this.responseType = Objects.requireNonNull(responseType, "responseType");
        this.uris = new DefaultUriBuilderFactory(source.baseUrl().toString());
    }

    /**
     * The URI this adapter would call for a subject.
     *
     * <p>Package-private so the encoding can be tested directly. The property
     * worth testing is not that a normal id works but that a hostile one cannot
     * escape: the subject is a single URI variable, so traversal, an injected
     * query string and a swapped host all become one encoded path segment.
     */
    URI uriFor(String subjectId) {
        return uris.uriString(source.pathTemplate()).build(subjectId);
    }

    @Override
    public String sourceName() {
        return source.name();
    }

    @Override
    public Class<T> responseType() {
        return responseType;
    }

    @Override
    public T fetch(DataRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return client.get()
                    .uri(uriFor(request.subjectId()))
                    .retrieve()
                    .body(responseType);
        } catch (HttpClientErrorException.NotFound absent) {
            // Names the source and never the subject: a subject id in a log line
            // is the operational metadata the specification asks us to protect.
            LOG.debug("source {} holds no record for the requested subject", source.name());
            return null;
        }
    }
}
