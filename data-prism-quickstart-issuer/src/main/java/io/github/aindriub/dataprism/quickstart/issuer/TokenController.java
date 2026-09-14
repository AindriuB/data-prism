package io.github.aindriub.dataprism.quickstart.issuer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Mints a real, signature-verified JWT against {@link SigningKeyHolder}'s own
 * key. Task 21 gives the standalone server no fixture-development bypass, so
 * this endpoint — not a shortcut on the server — is the quickstart's only
 * path to a usable caller credential.
 */
@RestController
class TokenController {

    private final SigningKeyHolder keys;
    private final IssuerProperties properties;

    TokenController(SigningKeyHolder keys, IssuerProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    @PostMapping("/token")
    ResponseEntity<Map<String, Object>> issueToken(@RequestBody(required = false) TokenRequest request)
            throws JOSEException {
        TokenRequest resolved = (request == null ? new TokenRequest(null, null, null, null, null, null)
                : request).withDefaults();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(resolved.subject())
                .claim("azp", resolved.clientId())
                .claim("roles", List.copyOf(resolved.roles()))
                .claim("purpose", resolved.purpose())
                .claim("case_id", resolved.caseId())
                .issuer(properties.getIssuerId())
                .audience(properties.getAudience())
                .expirationTime(Date.from(Instant.now().plus(resolved.ttlMinutes(), ChronoUnit.MINUTES)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.signingKey().getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(keys.signingKey()));

        return ResponseEntity.ok(Map.of(
                "access_token", jwt.serialize(),
                "token_type", "Bearer",
                "expires_in", resolved.ttlMinutes() * 60));
    }
}
