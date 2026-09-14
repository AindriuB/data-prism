package io.github.aindriub.dataprism.quickstart.issuer;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * One RSA keypair, generated in memory the moment this process starts and
 * held only for its lifetime. It is never written to disk and never
 * committed: the only two places its public half is exposed are {@code
 * /jwks} (as a JWK) and every token this process signs (as {@code kid}).
 */
@Component
final class SigningKeyHolder {

    private final RSAKey signingKey;

    SigningKeyHolder() {
        try {
            this.signingKey = new RSAKeyGenerator(2048)
                    .keyID("quickstart-issuer-" + UUID.randomUUID())
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("could not generate the quickstart issuer's signing key", e);
        }
    }

    RSAKey signingKey() {
        return signingKey;
    }
}
