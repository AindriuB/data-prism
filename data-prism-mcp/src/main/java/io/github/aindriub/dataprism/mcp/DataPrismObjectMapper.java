package io.github.aindriub.dataprism.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The one mapper that serialises anything a model will see.
 *
 * <p>There is a single instance on purpose. The scrubbing engine is installed
 * here, so a second mapper anywhere below this module would be a way for source
 * data to reach the transport without passing through it — and it would fail
 * silently, since the output would look perfectly well-formed. Reviewers are
 * told to treat a new {@code ObjectMapper} in or under {@code mcp} as the
 * finding, and an architecture test enforces it.
 *
 * <p>The project is on a single Jackson 2 classpath for the same reason: the
 * {@code mcp} aggregate artifact would bring Jackson 3 alongside Spring Boot's
 * Jackson 2, and the module would end up registered on the wrong one. Maven
 * Enforcer bans that dependency.
 */
public final class DataPrismObjectMapper {

    private DataPrismObjectMapper() {
    }

    public static ObjectMapper create() {
        return new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Anything reaching this mapper has already been through the
                // engine. Failing on an unexpected shape is better than emitting
                // it: an empty bean here means something was not scrubbed.
                .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
