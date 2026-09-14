package io.github.aindriub.dataprism.quickstart.issuer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two facts a token consumer must agree with this issuer on: what
 * {@code iss} it will sign and what {@code aud} it will stamp on a minted
 * token. Both must match the standalone server's own
 * {@code dataprism.security.jwt.issuer} / {@code .audience} exactly, or the
 * server's {@code JwtIssuerValidator} / audience check refuses every token
 * this process mints.
 */
@ConfigurationProperties(prefix = "quickstart.issuer")
public class IssuerProperties {

    private String issuerId = "https://issuer.quickstart.invalid";
    private String audience = "data-prism-quickstart-mcp";
    private String defaultPurpose = "investigation";

    public String getIssuerId() {
        return issuerId;
    }

    public void setIssuerId(String issuerId) {
        this.issuerId = issuerId;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getDefaultPurpose() {
        return defaultPurpose;
    }

    public void setDefaultPurpose(String defaultPurpose) {
        this.defaultPurpose = defaultPurpose;
    }
}
