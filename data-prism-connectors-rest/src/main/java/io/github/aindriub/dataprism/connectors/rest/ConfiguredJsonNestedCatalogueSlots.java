package io.github.aindriub.dataprism.connectors.rest;

/**
 * The bounded pool of pre-declared marker types {@link
 * ConfiguredJsonNestedCatalogueTokens} assigns, one per named nested catalogue,
 * at parse time.
 *
 * <p>Each of these classes exists for exactly one reason: to be a distinct,
 * compiled, package-private {@code Class} object that a source's nested {@code
 * fields:} entry can carry as its {@link
 * io.github.aindriub.dataprism.core.FieldMetadata#valueType()}/{@code
 * elementType()}, so that core's {@code Class}-keyed {@code
 * FieldMetadataResolver} can tell one nested catalogue's descent target apart
 * from another's -- and from the root catalogue's -- without core growing a
 * second, string-keyed way to resolve metadata. Unlike a class minted at
 * runtime, every one of these has a fixed, stable, greppable {@link
 * Class#getName()} that is identical on every run: {@code
 * io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonNestedCatalogueSlot0},
 * and so on, which is exactly what a startup or runtime refusal message that
 * interpolates {@code type.getName()} needs in order to mean the same thing
 * twice.
 *
 * <p>None of these classes is ever instantiated, and none carries any state or
 * behaviour; only their identity as distinct {@code Class} objects is used.
 * They are deliberately not one generic class reused with different names --
 * a fixed, finite, reviewable list is the entire point: see
 * docs/plan/tasks/60-*.md's rejection of a runtime-generated class.
 */
final class ConfiguredJsonNestedCatalogueSlot0 {
    private ConfiguredJsonNestedCatalogueSlot0() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot1 {
    private ConfiguredJsonNestedCatalogueSlot1() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot2 {
    private ConfiguredJsonNestedCatalogueSlot2() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot3 {
    private ConfiguredJsonNestedCatalogueSlot3() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot4 {
    private ConfiguredJsonNestedCatalogueSlot4() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot5 {
    private ConfiguredJsonNestedCatalogueSlot5() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot6 {
    private ConfiguredJsonNestedCatalogueSlot6() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot7 {
    private ConfiguredJsonNestedCatalogueSlot7() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot8 {
    private ConfiguredJsonNestedCatalogueSlot8() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot9 {
    private ConfiguredJsonNestedCatalogueSlot9() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot10 {
    private ConfiguredJsonNestedCatalogueSlot10() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot11 {
    private ConfiguredJsonNestedCatalogueSlot11() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot12 {
    private ConfiguredJsonNestedCatalogueSlot12() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot13 {
    private ConfiguredJsonNestedCatalogueSlot13() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot14 {
    private ConfiguredJsonNestedCatalogueSlot14() {
    }
}

final class ConfiguredJsonNestedCatalogueSlot15 {
    private ConfiguredJsonNestedCatalogueSlot15() {
    }
}
