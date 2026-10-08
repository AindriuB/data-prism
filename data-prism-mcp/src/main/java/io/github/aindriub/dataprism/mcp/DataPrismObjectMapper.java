package io.github.aindriub.dataprism.mcp;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one mapper that serialises anything a model will see.
 *
 * <p>There is a single instance on purpose. The scrubbing engine is installed
 * here, so a second mapper anywhere below this module would be a way for source
 * data to reach the transport without passing through it — and it would fail
 * silently, since the output would look perfectly well-formed. Reviewers are
 * told to treat a new mapper (a constructor, a builder, a {@code build()} or a
 * {@code rebuild()}) in or under {@code mcp} as the finding, and an
 * architecture test enforces it.
 *
 * <p>The project is on a single Jackson major (Jackson 3) for the same reason: a
 * second major would bring a second mapper family that this class does not
 * govern. Maven Enforcer bans the Jackson 2 artifacts.
 */
public final class DataPrismObjectMapper {

    private DataPrismObjectMapper() {
    }

    public static JsonMapper create() {
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Jackson 3 sorts properties alphabetically by default; Jackson 2 did not,
                // and the bytes a model sees must not change with the Jackson major.
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                // Anything reaching this mapper has already been through the
                // engine. Failing on an unexpected shape is better than emitting
                // it: an empty bean here means something was not scrubbed.
                .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .build();
    }
}
