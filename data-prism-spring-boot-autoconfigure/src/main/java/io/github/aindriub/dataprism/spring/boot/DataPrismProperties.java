package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.security.ReservedArguments;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete V1 {@code dataprism.*} deployment vocabulary. Reviewed Java code supplies integrations. */
@ConfigurationProperties(prefix = "dataprism", ignoreUnknownFields = false)
public class DataPrismProperties {
    private Transport transport = new Transport();
    private Security security = new Security();
    private SecurityPolicy securityPolicy = new SecurityPolicy();
    private Privacy privacy = new Privacy();
    private Audit audit = new Audit();
    private Metrics metrics = new Metrics();
    private Hazelcast hazelcast = new Hazelcast();
    private Map<String, Source> sources = new LinkedHashMap<>();

    public Transport getTransport() {
        return transport;
    }

    public void setTransport(Transport v) {
        transport = v == null ? new Transport() : v;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security v) {
        security = v == null ? new Security() : v;
    }

    public SecurityPolicy getSecurityPolicy() {
        return securityPolicy;
    }

    public void setSecurityPolicy(SecurityPolicy v) {
        securityPolicy = v == null ? new SecurityPolicy() : v;
    }

    public Privacy getPrivacy() {
        return privacy;
    }

    public void setPrivacy(Privacy v) {
        privacy = v == null ? new Privacy() : v;
    }

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit v) {
        audit = v == null ? new Audit() : v;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics v) {
        metrics = v == null ? new Metrics() : v;
    }

    public Hazelcast getHazelcast() {
        return hazelcast;
    }

    public void setHazelcast(Hazelcast v) {
        hazelcast = v == null ? new Hazelcast() : v;
    }

    public Map<String, Source> getSources() {
        return sources;
    }

    public void setSources(Map<String, Source> v) {
        sources = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
    }

    /** Refuses malformed deployment configuration before the privacy pipeline is built. */
    @PostConstruct
    void validateAfterBinding() {
        validate();
    }

    public void validate() {
        boolean fixture = transport.fixtureDevelopment;
        if (transport.mode == Transport.Mode.STDIO && !fixture) {
            refuse("STDIO_DEVELOPMENT_ONLY", "dataprism.transport.stdio requires fixture-development=true");
        }
        if (transport.mode == null) {
            refuse("INVALID_TRANSPORT", "dataprism.transport.mode must be http or stdio");
        }
        rootedPath(transport.http.path, "dataprism.transport.http.path");
        boolean stdioFixture = fixture && transport.mode == Transport.Mode.STDIO;
        if (!stdioFixture) {
            protectedDeployment();
        }
        if (fixture && transport.mode != Transport.Mode.STDIO) {
            refuse("FIXTURE_DEVELOPMENT_STDIO_ONLY",
                    "dataprism.transport.fixture-development requires dataprism.transport.mode=stdio");
        }
        validateSources(fixture);
    }

    private void protectedDeployment() {
        required(security.jwt.issuer, "MISSING_JWT_ISSUER", "dataprism.security.jwt.issuer");
        required(security.jwt.audience, "MISSING_JWT_AUDIENCE", "dataprism.security.jwt.audience");
        if (blank(security.jwt.jwkSetUri) == blank(security.jwt.issuerDiscoveryUri)) {
            refuse("INVALID_JWT_LOCATION", "configure exactly one JWT JWKS or issuer-discovery URI");
        }
        trustedUri(
                blank(security.jwt.jwkSetUri) ? security.jwt.issuerDiscoveryUri : security.jwt.jwkSetUri,
                "INVALID_JWT_LOCATION",
                false);
        required(security.callerClaims.principal, "MISSING_CALLER_CLAIM", "dataprism.security.caller-claims.principal");
        required(security.callerClaims.roles, "MISSING_CALLER_CLAIM", "dataprism.security.caller-claims.roles");
        required(
                security.callerClaims.investigation,
                "MISSING_CALLER_CLAIM",
                "dataprism.security.caller-claims.investigation");
        if (Set.of(security.callerClaims.principal, security.callerClaims.roles, security.callerClaims.investigation)
                .size()
                != 3) {
            refuse("DUPLICATE_CALLER_CLAIM", "principal, roles and investigation claims must be distinct");
        }
        if (ReservedArguments.NAMES.contains(security.callerClaims.principal)
                || ReservedArguments.NAMES.contains(security.callerClaims.roles)
                || ReservedArguments.NAMES.contains(security.callerClaims.investigation)) {
            refuse("RESERVED_CALLER_CLAIM", "caller claim mappings cannot use reserved tool argument names");
        }
        validatePolicy();
        required(privacy.profile, "MISSING_PRIVACY_PROFILE", "dataprism.privacy.profile");
        if (privacy.scopeLifetime == null || privacy.scopeLifetime.isZero() || privacy.scopeLifetime.isNegative()) {
            refuse("INVALID_SCOPE_LIFETIME", "dataprism.privacy.scope-lifetime must be positive");
        }
        required(privacy.hmacKey.keyId, "MISSING_HMAC_KEY_REFERENCE", "dataprism.privacy.hmac-key.key-id");
        if (blank(privacy.hmacKey.environmentVariable) == blank(privacy.hmacKey.providerReference)) {
            refuse("MISSING_HMAC_KEY_REFERENCE",
                    "configure exactly one HMAC environment-variable or provider-reference");
        }
        if (!blank(privacy.hmacKey.value)) {
            refuse("LITERAL_SECRET_FORBIDDEN", "dataprism.privacy.hmac-key.value is forbidden");
        }
        required(audit.sink, "MISSING_AUDIT_SINK", "dataprism.audit.sink");
        if (!Set.of("approved-sink", "slf4j", "hash-chained").contains(audit.sink)) {
            refuse("UNKNOWN_AUDIT_SINK", audit.sink);
        }
        required(audit.writerId, "MISSING_AUDIT_WRITER", "dataprism.audit.writer-id");
        if (audit.credentialReference != null && audit.credentialReference.isBlank()) {
            refuse("INVALID_AUDIT_REFERENCE", "dataprism.audit.credential-reference");
        }
        required(metrics.sink, "MISSING_METRICS_SINK", "dataprism.metrics.sink");
        if (!Set.of("micrometer").contains(metrics.sink)) {
            refuse("UNKNOWN_METRICS_SINK", metrics.sink);
        }
        if (metrics.registryReference != null && metrics.registryReference.isBlank()) {
            refuse("INVALID_METRICS_REFERENCE", "dataprism.metrics.registry-reference");
        }
        required(hazelcast.topology, "MISSING_CLUSTER_TOPOLOGY", "dataprism.hazelcast.topology");
        if (!blank(hazelcast.topology) && !Set.of("embedded", "single-node").contains(hazelcast.topology)) {
            refuse("UNSUPPORTED_HAZELCAST_TOPOLOGY", "topology must be embedded or single-node");
        }
        if (hazelcast.identityCacheTtl != null
                && (hazelcast.identityCacheTtl.isZero() || hazelcast.identityCacheTtl.isNegative())) {
            refuse("INVALID_HAZELCAST_TTL", "dataprism.hazelcast.identity-cache-ttl must be positive");
        }
        if (hazelcast.persistenceEnabled || hazelcast.mapStoreEnabled) {
            refuse("UNSAFE_HAZELCAST_PERSISTENCE", "persistence and MapStore require a reviewed configuration");
        }
        if (blank(hazelcast.tlsKeyReference) != blank(hazelcast.tlsTrustReference)) {
            refuse("INVALID_HAZELCAST_TLS", "configure both Hazelcast TLS references");
        }
        if (hazelcast.reidentificationEnabled && blank(hazelcast.reidentificationControlsReference)) {
            refuse("MISSING_REIDENTIFICATION_CONTROLS",
                    "enabled re-identification requires reviewed controls reference");
        }
    }

    private void validatePolicy() {
        if (securityPolicy.purposes == null || securityPolicy.purposes.isEmpty()) {
            refuse("EMPTY_PURPOSE_LIST", "dataprism.security-policy.purposes");
        }
        for (String p : securityPolicy.purposes) {
            required(p, "BLANK_PURPOSE", "dataprism.security-policy.purposes");
        }
        if (securityPolicy.roles == null || securityPolicy.roles.isEmpty()) {
            refuse("MISSING_ROLE_POLICY", "dataprism.security-policy.roles");
        }
        securityPolicy.roles.forEach((role, caps) -> {
            required(role, "BLANK_ROLE", "dataprism.security-policy.roles");
            if (caps == null || caps.isEmpty()) {
                refuse("EMPTY_ROLE_CAPABILITIES", "role " + role);
            }
            for (String cap : caps) {
                if (!Capability.KNOWN.contains(cap)) {
                    refuse("UNKNOWN_CAPABILITY", cap);
                }
            }
        });
    }

    private void validateSources(boolean fixture) {
        sources.forEach((name, source) -> {
            required(name, "BLANK_SOURCE_NAME", "dataprism.sources");
            if (source == null) {
                refuse("INVALID_SOURCE", name);
            }
            trustedUri(source.baseUrl, "INVALID_SOURCE_URL", fixture);
            if (source.timeout == null || source.timeout.isZero() || source.timeout.isNegative()) {
                refuse("INVALID_SOURCE_TIMEOUT", name);
            }
            if (source.mtls != null && blank(source.mtls.keyReference) != blank(source.mtls.trustReference)) {
                refuse("INVALID_SOURCE_MTLS", name + " requires key and trust references together");
            }
            if (!blank(source.serviceCredentialReference) && source.serviceCredentialReference.contains(" ")) {
                refuse("INVALID_SOURCE_CREDENTIAL_REFERENCE", name);
            }
        });
    }

    private static void rootedPath(String v, String p) {
        if (blank(v) || !v.startsWith("/") || v.contains("//") || v.contains("?")) {
            refuse("INVALID_HTTP_PATH", p);
        }
    }

    private static void trustedUri(String raw, String code, boolean fixture) {
        try {
            URI u = URI.create(raw);
            boolean local = "localhost".equalsIgnoreCase(u.getHost()) || "127.0.0.1".equals(u.getHost());
            if (u.getScheme() == null
                    || u.getHost() == null
                    || u.getUserInfo() != null
                    || u.getQuery() != null
                    || u.getFragment() != null
                    || !("https".equalsIgnoreCase(u.getScheme())
                            || (fixture && local && "http".equalsIgnoreCase(u.getScheme())))) {
                refuse(code, "must be an approved HTTPS base URI");
            }
        } catch (IllegalArgumentException e) {
            refuse(code, "must be a URI");
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static void required(String v, String c, String p) {
        if (blank(v)) {
            refuse(c, p + " must be set");
        }
    }

    private static void refuse(String c, String d) {
        throw new DataPrismConfigurationException(c, d);
    }

    public static class Transport {
        public enum Mode { HTTP, STDIO }

        private Mode mode = Mode.HTTP;
        private boolean fixtureDevelopment;
        private Http http = new Http();

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode v) {
            mode = v;
        }

        public boolean isFixtureDevelopment() {
            return fixtureDevelopment;
        }

        public void setFixtureDevelopment(boolean v) {
            fixtureDevelopment = v;
        }

        public Http getHttp() {
            return http;
        }

        public void setHttp(Http v) {
            http = v == null ? new Http() : v;
        }

        public static class Http {
            private String path = "/mcp";

            public String getPath() {
                return path;
            }

            public void setPath(String v) {
                path = v;
            }
        }
    }

    public static class Security {
        private Jwt jwt = new Jwt();
        private CallerClaims callerClaims = new CallerClaims();

        public Jwt getJwt() {
            return jwt;
        }

        public void setJwt(Jwt v) {
            jwt = v == null ? new Jwt() : v;
        }

        public CallerClaims getCallerClaims() {
            return callerClaims;
        }

        public void setCallerClaims(CallerClaims v) {
            callerClaims = v == null ? new CallerClaims() : v;
        }

        public static class Jwt {
            private String issuer, audience, jwkSetUri, issuerDiscoveryUri;

            public String getIssuer() {
                return issuer;
            }

            public void setIssuer(String v) {
                issuer = v;
            }

            public String getAudience() {
                return audience;
            }

            public void setAudience(String v) {
                audience = v;
            }

            public String getJwkSetUri() {
                return jwkSetUri;
            }

            public void setJwkSetUri(String v) {
                jwkSetUri = v;
            }

            public String getIssuerDiscoveryUri() {
                return issuerDiscoveryUri;
            }

            public void setIssuerDiscoveryUri(String v) {
                issuerDiscoveryUri = v;
            }
        }

        public static class CallerClaims {
            private String principal, roles, investigation;

            public String getPrincipal() {
                return principal;
            }

            public void setPrincipal(String v) {
                principal = v;
            }

            public String getRoles() {
                return roles;
            }

            public void setRoles(String v) {
                roles = v;
            }

            public String getInvestigation() {
                return investigation;
            }

            public void setInvestigation(String v) {
                investigation = v;
            }
        }
    }

    public static class SecurityPolicy {
        private List<String> purposes = List.of();
        private Map<String, List<String>> roles = new LinkedHashMap<>();

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
    }

    public static class Privacy {
        private String profile, locale = "neutral";
        private Duration scopeLifetime;
        private HmacKey hmacKey = new HmacKey();
        private String descriptorFile;

        public String getProfile() {
            return profile;
        }

        public void setProfile(String v) {
            profile = v;
        }

        public String getLocale() {
            return locale;
        }

        public void setLocale(String v) {
            locale = v;
        }

        public Duration getScopeLifetime() {
            return scopeLifetime;
        }

        public void setScopeLifetime(Duration v) {
            scopeLifetime = v;
        }

        public HmacKey getHmacKey() {
            return hmacKey;
        }

        public void setHmacKey(HmacKey v) {
            hmacKey = v == null ? new HmacKey() : v;
        }

        /**
         * Optional path to a YAML model-descriptor file. Unset by default, in which
         * case no descriptor file is ever read and classification comes only from
         * annotations. See {@code DataPrismAutoConfiguration#dataPrismFieldMetadataResolver}
         * for how it is loaded and why every failure mode refuses startup.
         */
        public String getDescriptorFile() {
            return descriptorFile;
        }

        public void setDescriptorFile(String v) {
            descriptorFile = v;
        }

        public static class HmacKey {
            private String keyId, environmentVariable, providerReference, value;

            public String getKeyId() {
                return keyId;
            }

            public void setKeyId(String v) {
                keyId = v;
            }

            public String getEnvironmentVariable() {
                return environmentVariable;
            }

            public void setEnvironmentVariable(String v) {
                environmentVariable = v;
            }

            public String getProviderReference() {
                return providerReference;
            }

            public void setProviderReference(String v) {
                providerReference = v;
            }

            public String getValue() {
                return value;
            }

            public void setValue(String v) {
                value = v;
            }
        }
    }

    public static class Audit {
        private String sink, writerId, credentialReference;

        public String getSink() {
            return sink;
        }

        public void setSink(String v) {
            sink = v;
        }

        public String getWriterId() {
            return writerId;
        }

        public void setWriterId(String v) {
            writerId = v;
        }

        public String getCredentialReference() {
            return credentialReference;
        }

        public void setCredentialReference(String v) {
            credentialReference = v;
        }
    }

    public static class Metrics {
        private String sink, registryReference;

        public String getSink() {
            return sink;
        }

        public void setSink(String v) {
            sink = v;
        }

        public String getRegistryReference() {
            return registryReference;
        }

        public void setRegistryReference(String v) {
            registryReference = v;
        }
    }

    public static class Hazelcast {
        private String topology, tlsKeyReference, tlsTrustReference, reidentificationControlsReference;
        private Duration identityCacheTtl;
        private boolean reidentificationEnabled, persistenceEnabled, mapStoreEnabled;

        public String getTopology() {
            return topology;
        }

        public void setTopology(String v) {
            topology = v;
        }

        public Duration getIdentityCacheTtl() {
            return identityCacheTtl;
        }

        public void setIdentityCacheTtl(Duration v) {
            identityCacheTtl = v;
        }

        public boolean isReidentificationEnabled() {
            return reidentificationEnabled;
        }

        public void setReidentificationEnabled(boolean v) {
            reidentificationEnabled = v;
        }

        public boolean isPersistenceEnabled() {
            return persistenceEnabled;
        }

        public void setPersistenceEnabled(boolean v) {
            persistenceEnabled = v;
        }

        public boolean isMapStoreEnabled() {
            return mapStoreEnabled;
        }

        public void setMapStoreEnabled(boolean v) {
            mapStoreEnabled = v;
        }

        public String getTlsKeyReference() {
            return tlsKeyReference;
        }

        public void setTlsKeyReference(String v) {
            tlsKeyReference = v;
        }

        public String getTlsTrustReference() {
            return tlsTrustReference;
        }

        public void setTlsTrustReference(String v) {
            tlsTrustReference = v;
        }

        public String getReidentificationControlsReference() {
            return reidentificationControlsReference;
        }

        public void setReidentificationControlsReference(String v) {
            reidentificationControlsReference = v;
        }
    }

    public static class Source {
        private String baseUrl, serviceCredentialReference;
        private Duration timeout;
        private Mtls mtls = new Mtls();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String v) {
            baseUrl = v;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration v) {
            timeout = v;
        }

        public String getServiceCredentialReference() {
            return serviceCredentialReference;
        }

        public void setServiceCredentialReference(String v) {
            serviceCredentialReference = v;
        }

        public Mtls getMtls() {
            return mtls;
        }

        public void setMtls(Mtls v) {
            mtls = v == null ? new Mtls() : v;
        }

        public static class Mtls {
            private String keyReference, trustReference;

            public String getKeyReference() {
                return keyReference;
            }

            public void setKeyReference(String v) {
                keyReference = v;
            }

            public String getTrustReference() {
                return trustReference;
            }

            public void setTrustReference(String v) {
                trustReference = v;
            }
        }
    }
}
