package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import org.springframework.web.client.RestClient;

import java.util.Objects;

/**
 * Fetches one entity from one configuration-driven JSON REST source.
 *
 * <p>Fetching is delegated to {@link RestDataSourceAdapter}, unchanged: the same
 * reviewed URI templating, the same treatment of a 404 as NO_DATA rather than a
 * failure, the same propagation of every other error to the fan-out. This class
 * adds nothing to that transport; it only tags the fetched tree with the source
 * name that {@link ConfiguredJsonScrubbingEngine} needs to pick the right
 * catalogue, and it asks for the response as an {@link ObjectNode} rather than
 * an annotated Java type, since a configured source has no compiled model.
 *
 * <p>A response that is not a JSON object — an array, a bare scalar, {@code
 * null} — is a schema mismatch. Requesting the body as {@code ObjectNode.class}
 * makes Jackson refuse that conversion itself, so the mismatch surfaces as an
 * exception out of {@link #fetch} exactly like any other source failure: the
 * fan-out records it and the request continues without this source, rather than
 * a malformed body of unknown shape ever reaching the scrubbing engine.
 */
public final class ConfiguredJsonDataSourceAdapter implements DataSourceAdapter<ConfiguredJsonPayload> {

    private final String sourceName;
    private final RestDataSourceAdapter<ObjectNode> delegate;

    public ConfiguredJsonDataSourceAdapter(ConfiguredJsonSource source, RestClient client) {
        Objects.requireNonNull(source, "source");
        this.sourceName = source.transport().name();
        this.delegate = new RestDataSourceAdapter<>(source.transport(), client, ObjectNode.class);
    }

    @Override
    public String sourceName() {
        return sourceName;
    }

    @Override
    public Class<ConfiguredJsonPayload> responseType() {
        return ConfiguredJsonPayload.class;
    }

    @Override
    public ConfiguredJsonPayload fetch(DataRequest request) {
        ObjectNode body = delegate.fetch(request);
        return body == null ? null : new ConfiguredJsonPayload(sourceName, body);
    }
}
