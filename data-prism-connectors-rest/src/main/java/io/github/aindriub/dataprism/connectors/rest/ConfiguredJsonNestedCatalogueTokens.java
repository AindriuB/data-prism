package io.github.aindriub.dataprism.connectors.rest;

import java.util.List;

/**
 * Assigns one distinct {@code Class} token per named nested JSON catalogue,
 * from a bounded, fixed pool of pre-declared marker types ({@link
 * ConfiguredJsonNestedCatalogueSlot0} and its siblings).
 *
 * <p>Core's {@link io.github.aindriub.dataprism.core.FieldMetadata} and {@link
 * io.github.aindriub.dataprism.core.FieldMetadataResolver} key everything on
 * {@code Class<?>}, never on a name, and that is deliberately not something
 * this feature is allowed to change (see docs/plan/tasks/60-*.md). A
 * configuration-driven JSON source has no compiled Java type to hand core for
 * a nested catalogue the way an annotated model has one for a nested field --
 * so this connector needs its own tokens. An earlier attempt minted a fresh
 * {@code Class} at parse time via {@code
 * MethodHandles.Lookup#defineHiddenClass}. That was rejected: a privacy
 * decision (descend or refuse) must not be keyed on an object with no stable
 * name, no deterministic identity across runs, and nothing an auditor can
 * point at -- and it leaked, since core's {@code UNKNOWN_FIELD} message
 * interpolates {@code type.getName()}, which for a hidden class renders as a
 * different string every run.
 *
 * <p>A bounded pool of compiled, named marker types fixes all three: every
 * assignment is deterministic and reviewable, {@code getName()} is identical
 * across runs, and there is a hard, fail-closed limit on how many nested
 * catalogues a single source may declare, named in the exception a source
 * that exceeds it gets at startup.
 */
final class ConfiguredJsonNestedCatalogueTokens {

    /**
     * Sixteen slots -- the same bound core's own {@code
     * JsonTreeScrubbingEngine}/{@code SourceValues} place on tree depth
     * ({@code MAX_DEPTH}). A single reviewed REST response with more than
     * sixteen independently named nested sub-catalogues is not a shape this
     * connector's no-code path is meant to cover; a source that needs more
     * than that is better served by a Java-first model.
     */
    static final List<Class<?>> POOL = List.of(
            ConfiguredJsonNestedCatalogueSlot0.class,
            ConfiguredJsonNestedCatalogueSlot1.class,
            ConfiguredJsonNestedCatalogueSlot2.class,
            ConfiguredJsonNestedCatalogueSlot3.class,
            ConfiguredJsonNestedCatalogueSlot4.class,
            ConfiguredJsonNestedCatalogueSlot5.class,
            ConfiguredJsonNestedCatalogueSlot6.class,
            ConfiguredJsonNestedCatalogueSlot7.class,
            ConfiguredJsonNestedCatalogueSlot8.class,
            ConfiguredJsonNestedCatalogueSlot9.class,
            ConfiguredJsonNestedCatalogueSlot10.class,
            ConfiguredJsonNestedCatalogueSlot11.class,
            ConfiguredJsonNestedCatalogueSlot12.class,
            ConfiguredJsonNestedCatalogueSlot13.class,
            ConfiguredJsonNestedCatalogueSlot14.class,
            ConfiguredJsonNestedCatalogueSlot15.class);

    private ConfiguredJsonNestedCatalogueTokens() {
    }

    /**
     * @param sourceName the source declaring the catalogue, named in the
     *                   exception message if the pool is exhausted
     * @param ordinal    this catalogue's position among the source's own
     *                   nested catalogues (0-based); each source starts
     *                   counting from zero, so the pool bounds how many
     *                   catalogues one source may declare, not how many exist
     *                   across every configured source
     */
    static Class<?> mint(String sourceName, int ordinal) {
        if (ordinal < 0 || ordinal >= POOL.size()) {
            throw new IllegalArgumentException("json source " + sourceName + " declares more than "
                    + POOL.size() + " nested catalogues, which is more than this connector supports;"
                    + " reduce the number of named nested-catalogues entries or model this source"
                    + " Java-first instead");
        }
        return POOL.get(ordinal);
    }
}
