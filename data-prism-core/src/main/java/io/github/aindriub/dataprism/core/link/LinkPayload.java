package io.github.aindriub.dataprism.core.link;

import java.time.Instant;

/** The sealed payload of a link: what the codec seals and, after authentication, returns. */
public sealed interface LinkPayload permits FollowPayload, CatalogPagePayload {

    byte[] linkId();

    Instant issueTime();

    LinkRelation relation();
}
