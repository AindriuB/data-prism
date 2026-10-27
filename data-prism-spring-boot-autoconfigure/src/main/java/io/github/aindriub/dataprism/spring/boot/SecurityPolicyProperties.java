package io.github.aindriub.dataprism.spring.boot;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SecurityPolicyProperties {
    /** The only accepted value of {@code case-authorisation}. */
    static final String TOKEN_TRUSTED = "token-trusted";
    /** The only accepted value of {@code client-allowlist}. */
    static final String UNRESTRICTED = "unrestricted";
    private static final String PREFIX = "dataprism.security-policy.";

    /** Permitted purposes. At least one is required. */
    private List<String> purposes = List.of();
    /** Map of role name to the capabilities it holds. At least one role is required. */
    private Map<String, List<String>> roles = new LinkedHashMap<>();
    /**
     * Set to {@code token-trusted} to trust the case claim in the token without assignment checks (development
     * only; startup logs a warning). Unset, the application must supply a CaseAuthorizer bean or startup is refused.
     * Setting it together with a CaseAuthorizer bean is refused.
     */
    private String caseAuthorisation;
    /**
     * The clients allowed to call, each matched exactly. Unset, the application must supply a ClientAllowlist
     * bean, or set {@code client-allowlist=unrestricted}, or startup is refused. Cannot be combined with
     * {@code client-allowlist} or a ClientAllowlist bean.
     */
    private List<String> allowedClients;
    /**
     * Set to {@code unrestricted} to allow every client (startup logs a warning). Cannot be combined with
     * {@code allowed-clients} or a ClientAllowlist bean.
     */
    private String clientAllowlist;

    public List<String> getPurposes() {
        return purposes;
    }

    public void setPurposes(List<String> v) {
        purposes = v;
    }

    public Map<String, List<String>> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, List<String>> v) {
        roles = v;
    }

    public String getCaseAuthorisation() {
        return caseAuthorisation;
    }

    public void setCaseAuthorisation(String v) {
        caseAuthorisation = v;
    }

    public List<String> getAllowedClients() {
        return allowedClients;
    }

    public void setAllowedClients(List<String> v) {
        allowedClients = v;
    }

    public String getClientAllowlist() {
        return clientAllowlist;
    }

    public void setClientAllowlist(String v) {
        clientAllowlist = v;
    }

    boolean tokenTrusted() {
        return TOKEN_TRUSTED.equals(caseAuthorisation);
    }

    boolean unrestricted() {
        return UNRESTRICTED.equals(clientAllowlist);
    }

    /** Whether the settings, rather than an application bean, define the {@code ClientAllowlist}. */
    boolean clientAllowlistSet() {
        return allowedClients != null || clientAllowlist != null;
    }

    /**
     * Refuses the case-authorisation and client-allowlist settings that cannot work, whatever beans exist. No
     * message repeats a configured value.
     */
    public void validateDecisions() {
        if (caseAuthorisation != null && !TOKEN_TRUSTED.equals(caseAuthorisation)) {
            throw new DataPrismConfigurationException("UNSUPPORTED_CASE_AUTHORISATION",
                    PREFIX + "case-authorisation accepts only token-trusted; to authorise cases, supply a "
                            + "CaseAuthorizer bean instead");
        }
        if (clientAllowlist != null && !UNRESTRICTED.equals(clientAllowlist)) {
            throw new DataPrismConfigurationException("UNSUPPORTED_CLIENT_ALLOWLIST",
                    PREFIX + "client-allowlist accepts only unrestricted; to restrict clients, list them in "
                            + PREFIX + "allowed-clients");
        }
        if (allowedClients != null && clientAllowlist != null) {
            throw new DataPrismConfigurationException("CLIENT_ALLOWLIST_CONFLICT",
                    PREFIX + "allowed-clients and " + PREFIX + "client-allowlist are both set; choose one");
        }
        if (allowedClients != null && allowedClients.isEmpty()) {
            throw new DataPrismConfigurationException("EMPTY_CLIENT_ALLOWLIST",
                    PREFIX + "allowed-clients is empty; list at least one client, or remove it");
        }
        if (allowedClients != null) {
            for (String client : allowedClients) {
                if (client == null || client.isBlank()) {
                    throw new DataPrismConfigurationException("BLANK_ALLOWED_CLIENT",
                            PREFIX + "allowed-clients has a blank entry");
                }
            }
        }
    }

    /**
     * Only the case-authorisation and client-allowlist settings, bound straight from the environment, for the
     * checks that must run before any bean exists (and so before {@link DataPrismProperties} is bound).
     */
    static SecurityPolicyProperties decisionsFrom(Environment environment) {
        Binder binder = Binder.get(environment);
        SecurityPolicyProperties decisions = new SecurityPolicyProperties();
        decisions.caseAuthorisation = binder.bind(PREFIX + "case-authorisation", String.class).orElse(null);
        decisions.clientAllowlist = binder.bind(PREFIX + "client-allowlist", String.class).orElse(null);
        decisions.allowedClients = binder.bind(PREFIX + "allowed-clients", Bindable.listOf(String.class))
                .orElse(null);
        return decisions;
    }
}
