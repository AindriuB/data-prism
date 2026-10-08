package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Sets one configured header from the {@link ExternalCorrelationId} the adapter
 * attached to this request.
 *
 * <p>The id arrives as a request attribute, never from ambient per-thread
 * state, because source fan-out runs on parallel virtual threads. With no
 * attribute nothing is sent. In traceparent mode the value is a child
 * traceparent with a fresh parent-id per outbound request; otherwise it is the
 * validated opaque value. Any existing value of the header is removed first.
 */
final class OutboundCorrelationInterceptor implements ClientHttpRequestInterceptor {

    /** The request attribute under which the adapter passes the id. */
    static final String ATTRIBUTE = OutboundCorrelationInterceptor.class.getName() + ".id";

    private final String header;

    OutboundCorrelationInterceptor(String header) {
        this.header = java.util.Objects.requireNonNull(header, "header");
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().remove(header);
        if (request.getAttributes().get(ATTRIBUTE) instanceof ExternalCorrelationId id) {
            String value = id.childTraceparent().orElseGet(id::value);
            request.getHeaders().set(header, value);
        }
        return execution.execute(request, body);
    }
}
