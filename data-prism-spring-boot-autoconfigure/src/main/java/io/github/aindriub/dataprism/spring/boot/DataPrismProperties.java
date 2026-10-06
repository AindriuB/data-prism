package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.security.ReservedArguments;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete V1 {@code dataprism.*} deployment vocabulary. Reviewed Java code supplies integrations. */
@ConfigurationProperties(prefix = "dataprism", ignoreUnknownFields = false)
public class DataPrismProperties {
    /**
     * The {@code dataprism.audit.sink} value meaning "this deployment supplies its own
     * reviewed {@code AuditSink} bean" -- no sink implementation ships for it. {@code
     * validate()} accepts it as a known value; only {@code DataPrismContractValidator},
     * which alone can see whether a bean was actually supplied, can tell whether the
     * deployment kept its side of that contract.
     */
    public static final String APPROVED_SINK = "approved-sink";

    private Transport transport = new Transport();
    private Security security = new Security();
    private SecurityPolicy securityPolicy = new SecurityPolicy();
    private Privacy privacy = new Privacy();
    private Audit audit = new Audit();
    private Metrics metrics = new Metrics();
    private Hazelcast hazelcast = new Hazelcast();
    private Identity identity = new Identity();
    private Oversight oversight = new Oversight();
    private Reidentification reidentification = new Reidentification();
    private Operator operator = new Operator();
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

    public Identity getIdentity() {
        return identity;
    }

    public void setIdentity(Identity v) {
        identity = v == null ? new Identity() : v;
    }

    public Oversight getOversight() {
        return oversight;
    }

    public void setOversight(Oversight v) {
        oversight = v == null ? new Oversight() : v;
    }

    public Reidentification getReidentification() {
        return reidentification;
    }

    public void setReidentification(Reidentification v) {
        reidentification = v == null ? new Reidentification() : v;
    }

    public Operator getOperator() {
        return operator;
    }

    public void setOperator(Operator v) {
        operator = v == null ? new Operator() : v;
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
        // STDIO_DEVELOPMENT_ONLY is one of four codes meaning "this deployment has
        // no usable MCP transport"; see the Javadoc on DataPrismAutoConfiguration
        // #dataPrismMcpTransportPreflight for the full map and why they are not one.
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
        validateOversight();
    }

    /**
     * Refuses a configured server port equal to {@code dataprism.operator.port}. {@code server.port}
     * is Spring Boot's own property, not part of this vocabulary, so the caller supplies it.
     *
     * @param serverPort the effective {@code server.port}, or {@code null} when it is not known
     */
    void validateOperatorPort(Integer serverPort) {
        validateOperatorPort(serverPort, null);
    }

    /**
     * As {@link #validateOperatorPort(Integer)}, and also refuses a port equal to
     * {@code management.server.port}: the actuator listener must not share the operator connector.
     *
     * @param managementPort the effective {@code management.server.port}, or {@code null} when it is
     *                       unset or not known
     */
    void validateOperatorPort(Integer serverPort, Integer managementPort) {
        if (operator.enabled && operator.port != null && serverPort != null && serverPort > 0
                && operator.port.equals(serverPort)) {
            refuse("OPERATOR_PORT_SHARED", "dataprism.operator.port must differ from server.port");
        }
        if (operator.enabled && operator.port != null && managementPort != null && managementPort > 0
                && operator.port.equals(managementPort)) {
            refuse("OPERATOR_PORT_SHARED", "dataprism.operator.port must differ from management.server.port");
        }
    }

    private void validateOversight() {
        for (String tool : oversight.approvalRequiredTools) {
            if (!OVERSIGHT_TOOLS.contains(tool)) {
                refuse("UNKNOWN_OVERSIGHT_TOOL",
                        "dataprism.oversight.approval-required-tools must name get_entity_context or compare_entity_sources");
            }
        }
        positive(oversight.approvalTtl, "dataprism.oversight.approval-ttl");
        positive(oversight.callerRateLimit.window, "dataprism.oversight.caller-rate-limit.window");
        if (oversight.callerRateLimit.requests != null && oversight.callerRateLimit.requests <= 0) {
            refuse("INVALID_OVERSIGHT_LIMIT", "dataprism.oversight.caller-rate-limit.requests must be positive");
        }
        if (oversight.maxPendingPerRequester <= 0) {
            refuse("INVALID_OVERSIGHT_LIMIT", "dataprism.oversight.max-pending-per-requester must be positive");
        }
        positive(reidentification.approvalTtl, "dataprism.reidentification.approval-ttl");
        if (reidentification.maxPendingPerRequester <= 0) {
            refuse("INVALID_OVERSIGHT_LIMIT",
                    "dataprism.reidentification.max-pending-per-requester must be positive");
        }
        if (reidentification.enabled) {
            if (!hazelcast.reidentificationEnabled) {
                refuse("REIDENTIFICATION_INDEX_DISABLED",
                        "dataprism.reidentification.enabled requires dataprism.hazelcast.reidentification-enabled=true");
            }
            if (!"embedded".equals(hazelcast.topology)) {
                refuse("REIDENTIFICATION_REQUIRES_CLUSTER",
                        "dataprism.reidentification.enabled requires dataprism.hazelcast.topology=embedded");
            }
            if (reidentification.purposes.isEmpty() || reidentification.purposes.stream().anyMatch(p -> blank(p))) {
                refuse("EMPTY_REIDENTIFICATION_PURPOSES",
                        "dataprism.reidentification.purposes must name at least one non-blank purpose");
            }
            if (reidentification.fourEyes && reidentification.roles.values().stream()
                    .noneMatch(p -> p != null && p.contains(Reidentification.Permission.APPROVE))) {
                refuse("NO_REIDENTIFICATION_APPROVER",
                        "four-eyes re-identification requires a role holding APPROVE in dataprism.reidentification.roles");
            }
            if (!operator.enabled) {
                refuse("REIDENTIFICATION_REQUIRES_OPERATOR_SURFACE",
                        "dataprism.reidentification.enabled requires dataprism.operator.enabled=true");
            }
        }
        if (operator.enabled && (operator.port == null || operator.port < 1 || operator.port > 65535
                || blank(operator.requiredAudience) || blank(operator.requiredScope))) {
            refuse("MISSING_OPERATOR_SECURITY",
                    "dataprism.operator.enabled requires port, required-audience and required-scope");
        }
        if (operator.enabled && !blank(operator.requiredAudience)
                && operator.requiredAudience.equals(security.jwt.audience)) {
            refuse("OPERATOR_AUDIENCE_SHARED",
                    "dataprism.operator.required-audience must differ from dataprism.security.jwt.audience");
        }
        if (operator.enabled && !blank(operator.address)) {
            try {
                java.net.InetAddress.getByName(operator.address.trim());
            } catch (java.net.UnknownHostException | RuntimeException unresolvable) {
                refuse("INVALID_OPERATOR_ADDRESS", "dataprism.operator.address is not a usable address");
            }
        }
        if (!operator.enabled && (!oversight.approvalRequiredTools.isEmpty()
                || oversight.callerRateLimit.requests != null)) {
            refuse("OVERSIGHT_REQUIRES_OPERATOR_SURFACE",
                    "approval-required tools and a caller rate limit require dataprism.operator.enabled=true");
        }
    }

    private static final Set<String> OVERSIGHT_TOOLS = Set.of("get_entity_context", "compare_entity_sources");

    private static void positive(Duration v, String property) {
        if (v == null || v.isZero() || v.isNegative()) {
            refuse("INVALID_OVERSIGHT_LIMIT", property + " must be positive");
        }
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
        if (!Set.of(APPROVED_SINK, "slf4j", "hash-chained").contains(audit.sink)) {
            refuse("UNKNOWN_AUDIT_SINK", audit.sink);
        }
        // Only hash-chained resolves to a bean that needs a file: see
        // DataPrismAutoConfiguration#dataPrismHashChainedAuditSink. Refusing here,
        // at property-validation time, means a missing path is a startup refusal
        // with a stable code rather than a NullPointerException once that bean is
        // actually constructed.
        if (!blank(audit.directory) && !blank(audit.filePath)) {
            refuse("AMBIGUOUS_AUDIT_LOCATION",
                    "set either dataprism.audit.directory or dataprism.audit.file-path, not both");
        }
        if ("hash-chained".equals(audit.sink) && blank(audit.directory)) {
            required(audit.filePath, "MISSING_AUDIT_FILE_PATH", "dataprism.audit.file-path");
        }
        if (!blank(audit.directory)) {
            // Whatever the sink: the retention bean is created from the directory alone, and purge
            // reads its earlier anchors back from the checkpoint file. Without one it could never
            // prove a chain's start, so retention cannot run at all.
            if (blank(audit.checkpoint.filePath)) {
                refuse("RETENTION_REQUIRES_CHECKPOINT",
                        "dataprism.audit.directory requires dataprism.audit.checkpoint.file-path");
            }
        }
        if (!blank(audit.checkpoint.filePath)) {
            // An unparseable path is not waved through here: canonical() maps it to a path that never
            // matches, and it is then refused as AUDIT_CHECKPOINT_FILE_UNUSABLE when the checkpoint
            // sink tries to open it (DataPrismAutoConfiguration#dataPrismAuditCheckpointSink).
            String checkpoint = audit.checkpoint.filePath;
            if ((!blank(audit.directory) && (sameOrInside(checkpoint, audit.directory)))
                    || (!blank(audit.filePath) && sameOrInside(checkpoint, audit.filePath))) {
                refuse("AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE",
                        "dataprism.audit.checkpoint.file-path must be separate from the audit file or directory");
            }
        }
        if (audit.checkpoint.interval == null || audit.checkpoint.interval.isZero()
                || audit.checkpoint.interval.isNegative()) {
            refuse("INVALID_AUDIT_CHECKPOINT_INTERVAL", "dataprism.audit.checkpoint.interval must be positive");
        }
        if (audit.retention == null) {
            refuse("AUDIT_RETENTION_BELOW_MINIMUM", "dataprism.audit.retention must be set");
        }
        if (!audit.retentionOverride) {
            try {
                // AuditRetention owns the six-month rule; reuse it rather than restate it.
                new io.github.aindriub.dataprism.audit.AuditRetention(java.nio.file.Path.of("."), audit.retention,
                        c -> { }, java.time.Clock.systemUTC());
            } catch (IllegalArgumentException e) {
                refuse("AUDIT_RETENTION_BELOW_MINIMUM", "dataprism.audit.retention " + audit.retention
                        + " is below six months; EU AI Act Art. 19 allows other periods only under Union or"
                        + " national law, in which case set dataprism.audit.retention-override=true");
            }
        }
        required(audit.writerId, "MISSING_AUDIT_WRITER", "dataprism.audit.writer-id");
        // AuditRecorder appends "/" plus a per-boot suffix to build its instanceId, so a
        // writer-id containing "/" would make that split ambiguous -- refused here, at
        // property-validation time, with a stable startup code rather than an
        // IllegalArgumentException once the AuditRecorder bean is actually constructed.
        if (!blank(audit.writerId) && audit.writerId.indexOf('/') >= 0) {
            refuse("INVALID_AUDIT_WRITER", "dataprism.audit.writer-id must not contain '/'");
        }
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

    /**
     * True when {@code candidate} is {@code location} or lies beneath it, comparing normalised
     * paths with symbolic links resolved wherever the path (or its nearest existing ancestor) exists.
     */
    static boolean sameOrInside(String candidate, String location) {
        java.nio.file.Path c = canonical(candidate);
        java.nio.file.Path l = canonical(location);
        return c.startsWith(l);
    }

    private static java.nio.file.Path canonical(String value) {
        java.nio.file.Path path;
        try {
            path = java.nio.file.Path.of(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return java.nio.file.Path.of("/invalid-path-never-matches");
        }
        java.nio.file.Path existing = path;
        while (existing != null && !java.nio.file.Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(path)).normalize();
        } catch (java.io.IOException e) {
            return path;
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
        private String sink, writerId, credentialReference, filePath, directory;
        private java.time.Period retention = java.time.Period.ofMonths(6);
        private boolean retentionOverride;
        private final Checkpoint checkpoint = new Checkpoint();

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String v) {
            directory = v;
        }

        public java.time.Period getRetention() {
            return retention;
        }

        public void setRetention(java.time.Period v) {
            retention = v;
        }

        public boolean isRetentionOverride() {
            return retentionOverride;
        }

        public void setRetentionOverride(boolean v) {
            retentionOverride = v;
        }

        public Checkpoint getCheckpoint() {
            return checkpoint;
        }

        public static class Checkpoint {
            private String filePath;
            private Duration interval = Duration.ofMinutes(5);

            public String getFilePath() {
                return filePath;
            }

            public void setFilePath(String v) {
                filePath = v;
            }

            public Duration getInterval() {
                return interval;
            }

            public void setInterval(Duration v) {
                interval = v;
            }
        }

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

        /**
         * The file the {@code hash-chained} sink appends to. Required only when
         * {@code dataprism.audit.sink=hash-chained}; see
         * {@code DataPrismAutoConfiguration#dataPrismHashChainedAuditSink}. Unused,
         * and left unset, by every other sink value.
         */
        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String v) {
            filePath = v;
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
        private String clusterName;
        private final Join join = new Join();
        private final Member member = new Member();

        /** Required with {@code embedded}; never {@code dev}. Refused with {@code single-node}. */
        public String getClusterName() {
            return clusterName;
        }

        public void setClusterName(String v) {
            clusterName = v;
        }

        public Join getJoin() {
            return join;
        }

        public Member getMember() {
            return member;
        }

        /** Whether any of the cluster-only settings is present. */
        boolean clusterSettingsSet() {
            return !blank(clusterName) || !blank(join.mode) || !join.members.isEmpty()
                    || !blank(join.kubernetes.namespace) || !blank(join.kubernetes.serviceName)
                    || !blank(join.kubernetes.serviceDns) || member.port != null || !blank(member.interfaceAddress);
        }

        /** How members find each other. {@code mode} is {@code tcp-ip}, {@code kubernetes} or {@code none}. */
        public static class Join {
            private String mode;
            private List<String> members = new ArrayList<>();
            private final Kubernetes kubernetes = new Kubernetes();

            public String getMode() {
                return mode;
            }

            public void setMode(String v) {
                mode = v;
            }

            /** {@code host} or {@code host:port} entries, for {@code tcp-ip} only. */
            public List<String> getMembers() {
                return members;
            }

            public void setMembers(List<String> v) {
                members = v == null ? new ArrayList<>() : new ArrayList<>(v);
            }

            public Kubernetes getKubernetes() {
                return kubernetes;
            }
        }

        /** For {@code kubernetes} only: a namespace and exactly one of service name or service DNS. */
        public static class Kubernetes {
            private String namespace, serviceName, serviceDns;

            public String getNamespace() {
                return namespace;
            }

            public void setNamespace(String v) {
                namespace = v;
            }

            public String getServiceName() {
                return serviceName;
            }

            public void setServiceName(String v) {
                serviceName = v;
            }

            public String getServiceDns() {
                return serviceDns;
            }

            public void setServiceDns(String v) {
                serviceDns = v;
            }
        }

        /**
         * This member's own listener. The port defaults to 5701 and is never auto-incremented.
         * With {@code tcp-ip} or {@code kubernetes} and no {@code interface}, the member binds
         * <em>every</em> network interface; set one to restrict it. {@code join.mode: none}
         * binds {@code 127.0.0.1} and refuses an interface.
         */
        public static class Member {
            private Integer port;
            private String interfaceAddress;

            public Integer getPort() {
                return port;
            }

            public void setPort(Integer v) {
                port = v;
            }

            /** Bound as {@code dataprism.hazelcast.member.interface}. */
            public String getInterface() {
                return interfaceAddress;
            }

            public void setInterface(String v) {
                interfaceAddress = v;
            }
        }

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

    /**
     * Selects the built-in {@code IdentityResolver} for operators with no
     * Java to write, rather than one being inferred from other configuration.
     * Absent (the default), nothing is selected here: an {@code IdentityResolver}
     * bean must still come from the application, or
     * {@code DataPrismAutoConfiguration#dataPrismIdentityResolverPreflight}
     * refuses startup with {@code MISSING_IDENTITY_RESOLVER}, exactly as before
     * this property existed.
     */
    public static class Identity {
        private String resolver;

        public String getResolver() {
            return resolver;
        }

        public void setResolver(String v) {
            resolver = v;
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

    /** {@code dataprism.oversight.*}: which tools need human approval, and the per-caller request limit. */
    public static class Oversight {
        private List<String> approvalRequiredTools = new java.util.ArrayList<>();
        private Duration approvalTtl = Duration.ofMinutes(15);
        private final CallerRateLimit callerRateLimit = new CallerRateLimit();
        private int maxPendingPerRequester = 5;

        public List<String> getApprovalRequiredTools() {
            return approvalRequiredTools;
        }

        public void setApprovalRequiredTools(List<String> v) {
            approvalRequiredTools = v == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
        }

        public Duration getApprovalTtl() {
            return approvalTtl;
        }

        public void setApprovalTtl(Duration v) {
            approvalTtl = v;
        }

        public CallerRateLimit getCallerRateLimit() {
            return callerRateLimit;
        }

        public int getMaxPendingPerRequester() {
            return maxPendingPerRequester;
        }

        public void setMaxPendingPerRequester(int v) {
            maxPendingPerRequester = v;
        }

        public static class CallerRateLimit {
            private Integer requests;
            private Duration window = Duration.ofMinutes(1);

            public Integer getRequests() {
                return requests;
            }

            public void setRequests(Integer v) {
                requests = v;
            }

            public Duration getWindow() {
                return window;
            }

            public void setWindow(Duration v) {
                window = v;
            }
        }
    }

    /** {@code dataprism.reidentification.*}: the controlled reverse lookup. Off by default. */
    public static class Reidentification {
        /** What a role may do; mirrors the library's own permission names. */
        public enum Permission { REQUEST, APPROVE }

        private boolean enabled;
        private List<String> purposes = new java.util.ArrayList<>();
        private Map<String, Set<Permission>> roles = new LinkedHashMap<>();
        private boolean fourEyes = true;
        private Duration approvalTtl = Duration.ofMinutes(15);
        private int maxPendingPerRequester = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            enabled = v;
        }

        public List<String> getPurposes() {
            return purposes;
        }

        public void setPurposes(List<String> v) {
            purposes = v == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
        }

        public Map<String, Set<Permission>> getRoles() {
            return roles;
        }

        public void setRoles(Map<String, Set<Permission>> v) {
            roles = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
        }

        public boolean isFourEyes() {
            return fourEyes;
        }

        public void setFourEyes(boolean v) {
            fourEyes = v;
        }

        public Duration getApprovalTtl() {
            return approvalTtl;
        }

        public void setApprovalTtl(Duration v) {
            approvalTtl = v;
        }

        public int getMaxPendingPerRequester() {
            return maxPendingPerRequester;
        }

        public void setMaxPendingPerRequester(int v) {
            maxPendingPerRequester = v;
        }
    }

    /** {@code dataprism.operator.*}: the second, separately secured port the operator endpoints will use. */
    public static class Operator {
        private boolean enabled;
        private Integer port;
        private String requiredAudience, requiredScope;
        /** The address the operator connector binds to; unset means the same as {@code server.address}. */
        private String address;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            enabled = v;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String v) {
            address = v;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer v) {
            port = v;
        }

        public String getRequiredAudience() {
            return requiredAudience;
        }

        public void setRequiredAudience(String v) {
            requiredAudience = v;
        }

        public String getRequiredScope() {
            return requiredScope;
        }

        public void setRequiredScope(String v) {
            requiredScope = v;
        }
    }
}
