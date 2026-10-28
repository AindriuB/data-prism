package io.github.aindriub.dataprism.quickstart.issuer;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.text.ParseException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenControllerTest {

    private static final String OPERATOR = "data-prism-quickstart-operator";

    private final TokenController controller = new TokenController(new SigningKeyHolder(), new IssuerProperties());

    private static TokenRequest withAudienceAndScope(String audience, String scope) {
        return new TokenRequest(null, null, null, null, null, null, audience, scope);
    }

    private static JWTClaimsSet claimsOf(ResponseEntity<Map<String, Object>> response) throws ParseException {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return SignedJWT.parse((String) response.getBody().get("access_token")).getJWTClaimsSet();
    }

    @Test
    void a_default_request_keeps_todays_token_shape_with_no_scope_claim() throws Exception {
        JWTClaimsSet claims = claimsOf(controller.issueToken(null));

        assertThat(claims.getClaims().keySet())
                .containsExactlyInAnyOrder("sub", "azp", "roles", "purpose", "case_id", "iss", "aud", "exp");
        assertThat(claims.getAudience()).containsExactly("data-prism-quickstart-mcp");
        assertThat(claims.getClaim("aud")).isEqualTo(List.of("data-prism-quickstart-mcp"));
    }

    @Test
    void an_explicit_mcp_audience_is_the_same_as_the_default() throws Exception {
        JWTClaimsSet claims = claimsOf(controller.issueToken(
                withAudienceAndScope("data-prism-quickstart-mcp", null)));

        assertThat(claims.getAudience()).containsExactly("data-prism-quickstart-mcp");
        assertThat(claims.getClaim("scope")).isNull();
    }

    @Test
    void an_operator_request_carries_the_operator_audience_and_the_scope() throws Exception {
        JWTClaimsSet claims = claimsOf(controller.issueToken(new TokenRequest("approver-1", null,
                List.of("approver"), "investigation", "CASE-X", null, OPERATOR, "operator")));

        assertThat(claims.getAudience()).containsExactly(OPERATOR);
        assertThat(claims.getStringClaim("scope")).isEqualTo("operator");
        assertThat(claims.getSubject()).isEqualTo("approver-1");
    }

    @Test
    void an_operator_audience_without_a_scope_is_allowed_so_the_403_can_be_shown() throws Exception {
        JWTClaimsSet claims = claimsOf(controller.issueToken(withAudienceAndScope(OPERATOR, null)));

        assertThat(claims.getClaim("scope")).isNull();
    }

    @Test
    void an_unknown_audience_is_refused_without_echoing_it() throws Exception {
        assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope("https://evil.example.invalid", null)),
                "evil");
    }

    @Test
    void a_malformed_scope_is_refused_without_echoing_it() throws Exception {
        for (String bad : List.of("op erator\n", "operator;admin", "a".repeat(200), "x\"y", "two  spaces")) {
            assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope(OPERATOR, bad)), bad.strip());
        }
    }

    @Test
    void a_blank_scope_or_audience_is_refused() throws Exception {
        assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope(OPERATOR, " ")), "zzzz");
        assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope(" ", null)), "zzzz");
    }

    private static void assertRefusedWithoutEcho(ResponseEntity<Map<String, Object>> response, String input) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "INVALID_TOKEN_REQUEST"));
        assertThat(response.getBody().toString()).doesNotContain(input);
    }
}
