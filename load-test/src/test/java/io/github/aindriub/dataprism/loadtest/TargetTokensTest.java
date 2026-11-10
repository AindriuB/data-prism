package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetTokensTest {

    @Test
    void theRequestNamesTheCaseAndTheLifetime() {
        assertThat(TargetTokens.requestBody("case-2", 45)).contains("\"caseId\":\"case-2\"")
                .contains("\"ttlMinutes\":45").contains("investigator").contains("investigation");
    }

    @Test
    void theTokenIsReadFromTheAnswer() {
        assertThat(TargetTokens.accessToken("{\"access_token\":\"abc\",\"expires_in\":60}")).isEqualTo("abc");
    }

    @Test
    void anAnswerWithNoTokenFailsWithoutEchoingIt() {
        assertThatThrownBy(() -> TargetTokens.accessToken("{\"secret\":\"do-not-print\"}"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("do-not-print");
    }
}
