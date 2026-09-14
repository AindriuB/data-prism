package io.github.aindriub.dataprism.quickstart.issuer;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public half of {@link SigningKeyHolder}'s keypair, as a JWK Set. This is
 * the URI the standalone server's {@code dataprism.security.jwt.jwk-set-uri}
 * points at; task 21 refuses a plaintext {@code http://} location for it, so
 * this endpoint is reachable only over the HTTPS this process serves.
 */
@RestController
class JwksController {

    private final SigningKeyHolder keys;

    JwksController(SigningKeyHolder keys) {
        this.keys = keys;
    }

    @GetMapping("/jwks")
    Map<String, Object> jwks() {
        return new JWKSet(keys.signingKey().toPublicJWK()).toJSONObject();
    }
}
