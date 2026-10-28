package io.github.aindriub.dataprism.quickstart.issuer;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.text.ParseException;
import java.util.List;
import java.util.Map;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
        assertThat(claims.getClaims().keySet())
                .containsExactlyInAnyOrder("sub", "azp", "roles", "purpose", "case_id", "iss", "aud", "exp");
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
    void the_operator_audience_without_the_scope_is_refused() throws Exception {
        assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope(OPERATOR, null)), "zzzz");
    }

    @Test
    void a_scope_on_the_mcp_audience_is_refused_with_or_without_an_explicit_audience() throws Exception {
        assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope(null, "operator")), "zzzz");
        assertRefusedWithoutEcho(
                controller.issueToken(withAudienceAndScope("data-prism-quickstart-mcp", "operator")), "zzzz");
    }

    @Test
    void any_scope_other_than_the_configured_one_is_refused() throws Exception {
        for (String other : List.of("admin", "Operator", "operator extra", "operator ", "", " ")) {
            assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope(OPERATOR, other)), "admin");
        }
    }

    @Test
    void an_unknown_audience_is_refused_without_echoing_it() throws Exception {
        assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope("https://evil.example.invalid", null)),
                "evil");
        assertRefusedWithoutEcho(
                controller.issueToken(withAudienceAndScope("https://evil.example.invalid", "operator")), "evil");
    }

    @Test
    void a_blank_audience_is_refused() throws Exception {
        assertRefusedWithoutEcho(controller.issueToken(withAudienceAndScope(" ", null)), "zzzz");
    }

    @Test
    void json_posted_to_the_endpoint_binds_audience_and_scope() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        MockHttpServletResponse ok = mvc.perform(post("/token").contentType("application/json").content(
                "{\"subject\":\"approver-1\",\"roles\":[\"approver\"],\"audience\":\"" + OPERATOR
                        + "\",\"scope\":\"operator\"}")).andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        String token = ok.getContentAsString().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly(OPERATOR);
        assertThat(claims.getStringClaim("scope")).isEqualTo("operator");

        MockHttpServletResponse refused = mvc.perform(post("/token").contentType("application/json")
                .content("{\"scope\":\"operator\"}")).andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(refused.getContentAsString()).isEqualTo("{\"code\":\"INVALID_TOKEN_REQUEST\"}");

        assertThat(mvc.perform(post("/token")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private static void assertRefusedWithoutEcho(ResponseEntity<Map<String, Object>> response, String input) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "INVALID_TOKEN_REQUEST"));
        assertThat(response.getBody().toString()).doesNotContain(input);
    }
}
