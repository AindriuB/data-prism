package io.github.aindriub.dataprism.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PseudonymisationVersionTest {

    @Test
    void hmacSha256v1StillConstructs() {
        assertThat(PseudonymisationVersion.HMAC_SHA256_V1.algorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void withKeyAndWithVocabularyStillWorkOnTheDefault() {
        PseudonymisationVersion pinned = PseudonymisationVersion.HMAC_SHA256_V1
                .withKey("case-key")
                .withVocabulary("en");

        assertThat(pinned.keyId()).isEqualTo("case-key");
        assertThat(pinned.vocabularyId()).isEqualTo("en");
        assertThat(pinned.algorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void anAlgorithmWithA32ByteDigestConstructs() {
        var version = new PseudonymisationVersion("HmacSHA256", "v1", "dev", "");

        assertThat(version.algorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void hmacSha1IsRejectedForATooShortDigest() {
        assertThatThrownBy(() -> new PseudonymisationVersion("HmacSHA1", "v1", "dev", ""))
                .isInstanceOf(PseudonymisationVersion.InvalidAlgorithmException.class)
                .extracting(e -> ((PseudonymisationVersion.InvalidAlgorithmException) e).code())
                .isEqualTo("pseudonymisation.algorithm-digest-too-short");
        assertThatThrownBy(() -> new PseudonymisationVersion("HmacSHA1", "v1", "dev", ""))
                .isInstanceOf(PseudonymisationVersion.InvalidAlgorithmException.class)
                .extracting(e -> ((PseudonymisationVersion.InvalidAlgorithmException) e).algorithm())
                .isEqualTo("HmacSHA1");
    }

    @Test
    void hmacMd5IsRejectedForATooShortDigest() {
        assertThatThrownBy(() -> new PseudonymisationVersion("HmacMD5", "v1", "dev", ""))
                .isInstanceOf(PseudonymisationVersion.InvalidAlgorithmException.class)
                .extracting(e -> ((PseudonymisationVersion.InvalidAlgorithmException) e).code())
                .isEqualTo("pseudonymisation.algorithm-digest-too-short");
    }

    @Test
    void anAlgorithmNoProviderOffersIsRejected() {
        assertThatThrownBy(() -> new PseudonymisationVersion("HmacNoSuchThing", "v1", "dev", ""))
                .isInstanceOf(PseudonymisationVersion.InvalidAlgorithmException.class)
                .extracting(e -> ((PseudonymisationVersion.InvalidAlgorithmException) e).code())
                .isEqualTo("pseudonymisation.algorithm-unavailable");
    }

    @Test
    void noRejectionMessageContainsAKeyOrKeyId() {
        assertThatThrownBy(() -> new PseudonymisationVersion("HmacSHA1", "v1", "a-secret-key-id-42", ""))
                .hasMessageNotContaining("a-secret-key-id-42");
    }
}
