package io.github.aindriub.dataprism.core;

import java.util.Set;

/**
 * Names of everything an {@link InvestigationContext} can be granted.
 *
 * <p>One tool maps to one baseline capability, plus {@link #EXPOSE_SOURCE_NAMES}
 * — the elevated grant that lifts the per-scope source-name aliasing docs/pack.md
 * §32 and docs/design-review.md §34 require by default.
 */
public final class Capability {

    public static final String EXPOSE_SOURCE_NAMES = "EXPOSE_SOURCE_NAMES";
    public static final String GET_ENTITY_CONTEXT = "GET_ENTITY_CONTEXT";
    public static final String COMPARE_ENTITY_SOURCES = "COMPARE_ENTITY_SOURCES";
    public static final String DESCRIBE_ENTITY_MODEL = "DESCRIBE_ENTITY_MODEL";

    public static final Set<String> KNOWN =
            Set.of(EXPOSE_SOURCE_NAMES, GET_ENTITY_CONTEXT, COMPARE_ENTITY_SOURCES, DESCRIBE_ENTITY_MODEL);

    private Capability() {}
}
