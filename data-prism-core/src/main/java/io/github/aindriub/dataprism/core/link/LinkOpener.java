package io.github.aindriub.dataprism.core.link;

import java.util.Set;

/**
 * A test seam for {@link LinkAttemptGate}: {@link LinkCodec} is final and this module has no mocking library.
 * Production code never implements it except through {@code codec::open}.
 */
@FunctionalInterface
interface LinkOpener {

    LinkOpenResult open(String token, LinkBinding binding, Set<LinkRelation> accepted);
}
