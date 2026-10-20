package io.github.aindriub.dataprism.loadtest;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The credentials a long run presents, replaced before either lifetime ends. A token lives 12 h and a privacy
 * scope 8 h, so a run of up to 24 h would otherwise start failing mid-way. The scope is named by the token's
 * case id, so rotating the scope means minting a token with a new case id.
 *
 * <p>Both are replaced when three quarters of their lifetime has passed. The reissues and rotations are
 * counted, and the result file reports them: a soak that rotated nothing did not exercise this.
 */
final class CredentialRotation {

    /** Mints a token valid for {@code lifetime} for the scope named by {@code caseId}. */
    @FunctionalInterface
    interface Minter {
        String mint(Duration lifetime, String caseId) throws Exception;
    }

    private final Minter minter;
    private final Duration tokenLifetime;
    private final long tokenRefreshAfterNanos;
    private final long scopeRefreshAfterNanos;
    private final LongSupplier clock;

    private String token;
    private long tokenMintedAt;
    private long scopeStartedAt;
    private int scopeNumber = 1;
    private long tokenReissues;
    private long scopeRotations;

    CredentialRotation(Minter minter, Duration tokenLifetime, Duration scopeLifetime, LongSupplier clock) {
        this.minter = minter;
        this.tokenLifetime = tokenLifetime;
        this.tokenRefreshAfterNanos = tokenLifetime.toNanos() / 4 * 3;
        this.scopeRefreshAfterNanos = scopeLifetime.toNanos() / 4 * 3;
        this.clock = clock;
    }

    /** The token to present now, replaced first if it or its scope has used up three quarters of its life. */
    synchronized String token() {
        long now = clock.getAsLong();
        if (token == null) {
            scopeStartedAt = now;
            mint(now);
            return token;
        }
        boolean scopeDue = now - scopeStartedAt >= scopeRefreshAfterNanos;
        boolean tokenDue = now - tokenMintedAt >= tokenRefreshAfterNanos;
        if (scopeDue) {
            scopeNumber++;
            scopeStartedAt = now;
            scopeRotations++;
        }
        if (tokenDue) {
            tokenReissues++;
        }
        if (scopeDue || tokenDue) {
            mint(now);
        }
        return token;
    }

    private void mint(long now) {
        try {
            token = minter.mint(tokenLifetime, TokenIssuer.CASE_ID_PREFIX + scopeNumber);
        } catch (Exception failure) {
            throw new IllegalStateException("could not mint a token", failure);
        }
        tokenMintedAt = now;
    }

    synchronized long tokenReissues() {
        return tokenReissues;
    }

    synchronized long scopeRotations() {
        return scopeRotations;
    }
}
