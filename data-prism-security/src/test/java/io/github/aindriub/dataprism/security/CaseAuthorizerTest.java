package io.github.aindriub.dataprism.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CaseAuthorizerTest {

    @Test
    @DisplayName("tokenTrusted permits every request")
    void tokenTrustedPermitsEverything() {
        CaseAccessRequest request = new CaseAccessRequest("p", "c", "case-1", "purpose", Set.of("r"));

        assertThat(CaseAuthorizer.tokenTrusted().isAuthorised(request)).isTrue();
    }
}
