package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** A soak outlives the 12 h token and 8 h scope: the credentials are replaced before either ends. */
class CredentialRotationTest {

    private static final Duration TOKEN = TokenIssuer.TOKEN_LIFETIME;
    private static final Duration SCOPE = ServerProcess.SCOPE_LIFETIME;

    private final AtomicLong now = new AtomicLong();
    private final List<String> minted = new ArrayList<>();

    private CredentialRotation rotation() {
        return new CredentialRotation((lifetime, caseId) -> {
            minted.add(caseId);
            return "token-" + minted.size() + "-" + caseId;
        }, TOKEN, SCOPE, now::get);
    }

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    void theSameTokenIsUsedWhileBothLifetimesHaveMostOfTheirTimeLeft() {
        CredentialRotation rotation = rotation();
        String first = rotation.token();

        advance(Duration.ofHours(5));

        assertThat(rotation.token()).isEqualTo(first);
        assertThat(rotation.tokenReissues()).isZero();
        assertThat(rotation.scopeRotations()).isZero();
    }

    @Test
    void theScopeRotatesBeforeEightHoursAndTheNewTokenCarriesTheNewScope() {
        CredentialRotation rotation = rotation();
        rotation.token();

        advance(Duration.ofHours(7));
        String second = rotation.token();

        assertThat(rotation.scopeRotations()).isEqualTo(1);
        assertThat(rotation.tokenReissues()).isEqualTo(1);
        assertThat(minted).hasSize(2);
        assertThat(minted.get(1)).isNotEqualTo(minted.get(0));
        assertThat(second).contains(minted.get(1));
    }

    @Test
    void theTokenIsReissuedBeforeItExpiresInTheSameScopeWhenOnlyTheTokenIsDue() {
        CredentialRotation rotation = new CredentialRotation((lifetime, caseId) -> {
            minted.add(caseId);
            return "t" + minted.size();
        }, Duration.ofHours(2), Duration.ofHours(20), now::get);
        rotation.token();

        advance(Duration.ofMinutes(100));
        rotation.token();

        assertThat(rotation.tokenReissues()).isEqualTo(1);
        assertThat(rotation.scopeRotations()).isZero();
        assertThat(minted.get(1)).isEqualTo(minted.get(0));
    }

    @Test
    void aTwentyFourHourRunNeverHoldsACredentialPastItsLifetime() {
        CredentialRotation rotation = rotation();
        long mintedAt = 0;
        long scopeStart = 0;
        int seen = 0;
        for (int minute = 0; minute <= 24 * 60; minute++) {
            rotation.token();
            if (minted.size() != seen) {
                seen = minted.size();
                mintedAt = now.get();
                if (seen < 2 || !minted.get(seen - 1).equals(minted.get(seen - 2))) {
                    scopeStart = now.get();
                }
            }
            assertThat(now.get() - mintedAt).isLessThan(TOKEN.toNanos());
            assertThat(now.get() - scopeStart).isLessThan(SCOPE.toNanos());
            advance(Duration.ofMinutes(1));
        }

        // Mints at 0, 6, 12, 18 and 24 h: every re-mint is a reissue, and each one here also rotates the scope.
        assertThat(minted).hasSize(5);
        assertThat(rotation.tokenReissues()).isEqualTo(4);
        assertThat(rotation.scopeRotations()).isEqualTo(4);
    }
}
