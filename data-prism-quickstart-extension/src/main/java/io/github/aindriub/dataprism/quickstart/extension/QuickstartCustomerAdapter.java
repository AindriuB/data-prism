package io.github.aindriub.dataprism.quickstart.extension;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Objects;

/**
 * Fetches one customer record from the quickstart's synthetic fixture API.
 *
 * <p>The subject id is substituted as a single URI variable, so {@link
 * RestClient} encodes it rather than letting it steer the path, the same
 * defence {@code RestDataSourceAdapter} in {@code data-prism-connectors-rest}
 * documents. A 404 means the fixture holds nothing for this subject — data,
 * not a failure — and returning {@code null} lets the orchestrator record
 * {@code NO_DATA} rather than opening this source's circuit breaker.
 */
// --8<-- [start:adapter]
final class QuickstartCustomerAdapter implements DataSourceAdapter<CustomerModel> {

    private static final Logger LOG = LoggerFactory.getLogger(QuickstartCustomerAdapter.class);

    /** Must equal the {@code dataprism.sources.*} key this adapter is configured under. */
    static final String SOURCE_NAME = "customer";

    private final RestClient client;

    QuickstartCustomerAdapter(RestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public Class<CustomerModel> responseType() {
        return CustomerModel.class;
    }

    @Override
    public CustomerModel fetch(DataRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return client.get()
                    .uri("/customers/{id}", request.subjectId())
                    .retrieve()
                    .body(CustomerModel.class);
        } catch (HttpClientErrorException.NotFound absent) {
            LOG.debug("source {} holds no record for the requested subject", SOURCE_NAME);
            return null;
        }
    }
}
// --8<-- [end:adapter]
