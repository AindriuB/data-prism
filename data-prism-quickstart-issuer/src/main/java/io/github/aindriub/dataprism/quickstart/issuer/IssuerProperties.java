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
    private String operatorAudience = "data-prism-quickstart-operator";
    private String operatorScope = "operator";
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

    /** The one other audience this issuer will stamp, for the operator surface; must equal the server's. */
    public String getOperatorAudience() {
        return operatorAudience;
    }

    public void setOperatorAudience(String operatorAudience) {
        this.operatorAudience = operatorAudience;
    }

    /** The only scope this issuer will stamp, and only with the operator audience; must equal the server's. */
    public String getOperatorScope() {
        return operatorScope;
    }

    public void setOperatorScope(String operatorScope) {
        this.operatorScope = operatorScope;
    }

    public String getDefaultPurpose() {
        return defaultPurpose;
    }

    public void setDefaultPurpose(String defaultPurpose) {
        this.defaultPurpose = defaultPurpose;
    }
}
