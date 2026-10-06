package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.security.AuthenticatedCaller;

import java.util.Optional;

/**
 * The verified operator making the current request. Implemented at the security edge, so nothing
 * in this package depends on Spring Security. Empty means "no usable identity" and every
 * endpoint refuses.
 */
@FunctionalInterface
public interface OperatorCallers {
    Optional<AuthenticatedCaller> current();
}
