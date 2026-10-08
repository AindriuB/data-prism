package io.github.aindriub.dataprism.spring.boot.validation;

import io.github.aindriub.dataprism.spring.boot.AuditProperties;
import io.github.aindriub.dataprism.spring.boot.CorrelationProperties;
import io.github.aindriub.dataprism.spring.boot.HazelcastProperties;
import io.github.aindriub.dataprism.spring.boot.MetricsProperties;
import io.github.aindriub.dataprism.spring.boot.OperatorProperties;
import io.github.aindriub.dataprism.spring.boot.OversightProperties;
import io.github.aindriub.dataprism.spring.boot.PrivacyProperties;
import io.github.aindriub.dataprism.spring.boot.ReidentificationProperties;
import io.github.aindriub.dataprism.spring.boot.SecurityProperties;
import io.github.aindriub.dataprism.spring.boot.SecurityPolicyProperties;
import io.github.aindriub.dataprism.spring.boot.SourceProperties;
import io.github.aindriub.dataprism.spring.boot.TransportProperties;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.security.ReservedArguments;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Cross-property validation of {@link DataPrismProperties}: every refusal the binding alone cannot
 * express, run in a fixed order. It reads the bound values through their getters and refuses with
 * {@link DataPrismConfigurationException}, whose message never repeats a configured value that
 * could be personal data or a credential.
 */
public final class DataPrismPropertiesValidator {
    private final TransportProperties transport;
    private final SecurityProperties security;
    private final SecurityPolicyProperties securityPolicy;
    private final PrivacyProperties privacy;
    private final AuditProperties audit;
    private final CorrelationProperties correlation;
    private final MetricsProperties metrics;
    private final HazelcastProperties hazelcast;
    private final Map<String, SourceProperties> sources;
    private final OversightProperties oversight;
    private final ReidentificationProperties reidentification;
    private final OperatorProperties operator;

    public DataPrismPropertiesValidator(DataPrismProperties p) {
        transport = p.getTransport();
        security = p.getSecurity();
        securityPolicy = p.getSecurityPolicy();
        privacy = p.getPrivacy();
        audit = p.getAudit();
        correlation = p.getCorrelation();
        metrics = p.getMetrics();
        hazelcast = p.getHazelcast();
        sources = p.getSources();
        oversight = p.getOversight();
        reidentification = p.getReidentification();
        operator = p.getOperator();
    }

    public void validate() {
        boolean fixture = transport.isFixtureDevelopment();
        // STDIO_DEVELOPMENT_ONLY is one of four codes meaning "this deployment has
        // no usable MCP transport"; see the Javadoc on DataPrismAutoConfiguration
        // #dataPrismMcpTransportPreflight for the full map and why they are not one.
        if (transport.getMode() == TransportProperties.Mode.STDIO && !fixture) {
            refuse("STDIO_DEVELOPMENT_ONLY", "dataprism.transport.stdio requires fixture-development=true");
        }
        if (transport.getMode() == null) {
            refuse("INVALID_TRANSPORT", "dataprism.transport.mode must be http or stdio");
        }
        rootedPath(transport.getHttp().getPath(), "dataprism.transport.http.path");
        boolean stdioFixture = fixture && transport.getMode() == TransportProperties.Mode.STDIO;
        if (!stdioFixture) {
            protectedDeployment();
        }
        if (fixture && transport.getMode() != TransportProperties.Mode.STDIO) {
            refuse("FIXTURE_DEVELOPMENT_STDIO_ONLY",
                    "dataprism.transport.fixture-development requires dataprism.transport.mode=stdio");
        }
        validateCorrelation();
        validateAuditOutput();
        validateAuditEntityTypes();
        validateSources(fixture);
        validateOversight();
    }

    private static final java.util.regex.Pattern HEADER_TOKEN =
            java.util.regex.Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "cookie", "proxy-authorization");

    /** True if the value is an RFC 9110 token and not a header that carries a credential. */
    private static boolean usableHeaderName(String name) {
        return name != null && HEADER_TOKEN.matcher(name).matches()
                && !CREDENTIAL_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    private void validateCorrelation() {
        CorrelationProperties.Inbound inbound = correlation.getInbound();
        if (!blank(inbound.getHeader()) && !usableHeaderName(inbound.getHeader())) {
            refuse("INVALID_CORRELATION_HEADER",
                    "dataprism.correlation.inbound.header must be an HTTP header name other than a credential header");
        }
        if (!blank(correlation.getOutbound().getHeader()) && !usableHeaderName(correlation.getOutbound().getHeader())) {
            refuse("INVALID_CORRELATION_HEADER",
                    "dataprism.correlation.outbound.header must be an HTTP header name other than a credential header");
        }
        if (!"opaque".equals(inbound.getFormat()) && !"traceparent".equals(inbound.getFormat())) {
            refuse("INVALID_CORRELATION_FORMAT", "dataprism.correlation.inbound.format must be opaque or traceparent");
        }
        if ("traceparent".equals(inbound.getFormat())) {
            if (inbound.getPattern() != null) {
                refuse("CORRELATION_PATTERN_NOT_APPLICABLE",
                        "dataprism.correlation.inbound.pattern does not apply to the traceparent format");
            }
        } else {
            try {
                io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.opaque(inbound.effectivePattern());
            } catch (IllegalArgumentException e) {
                refuse("INVALID_CORRELATION_PATTERN", "dataprism.correlation.inbound.pattern is not a valid regular expression");
            }
        }
        if (inbound.isRequired()) {
            if (blank(inbound.getHeader())) {
                refuse("CORRELATION_REQUIRED_WITHOUT_HEADER",
                        "dataprism.correlation.inbound.required needs dataprism.correlation.inbound.header");
            }
            if (transport.getMode() != TransportProperties.Mode.HTTP) {
                refuse("CORRELATION_REQUIRES_HTTP_TRANSPORT",
                        "dataprism.correlation.inbound.required needs the HTTP transport");
            }
        }
        validateCorrelationMdcKey();
    }

    private void validateCorrelationMdcKey() {
        String key = correlation.getMdcKey();
        if (key == null) {
            return;
        }
        if (!io.github.aindriub.dataprism.core.correlation.CorrelationMdc.KEY_PATTERN.matcher(key).matches()) {
            refuse("INVALID_CORRELATION_MDC_KEY",
                    "dataprism.correlation.mdc-key must match [A-Za-z][A-Za-z0-9_.-]{0,63}");
        }
        if (io.github.aindriub.dataprism.core.correlation.CorrelationMdc.isReserved(key)) {
            refuse("CORRELATION_MDC_KEY_RESERVED",
                    "dataprism.correlation.mdc-key is a name that tracing or structured logging already writes");
        }
        if (blank(correlation.getInbound().getHeader())) {
            refuse("CORRELATION_MDC_KEY_WITHOUT_HEADER",
                    "dataprism.correlation.mdc-key needs dataprism.correlation.inbound.header");
        }
    }

    /** Refuses a bad name without repeating it: the value could itself be personal data. */
    private void validateAuditEntityTypes() {
        try {
            io.github.aindriub.dataprism.audit.AuditedEntityTypes.of(audit.getEntityTypes());
        } catch (IllegalArgumentException e) {
            refuse("INVALID_AUDIT_ENTITY_TYPE", "every dataprism.audit.entity-types entry must match "
                    + io.github.aindriub.dataprism.audit.AuditedEntityTypes.REGISTERED_NAME.pattern());
        }
    }

    private void validateAuditOutput() {
        AuditProperties.Output output = audit.getOutput();
        try {
            output.mapping();
            output.getRouting().toRouting().checkAgainst(output.mapping());
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            int colon = message.indexOf(':');
            String code = colon > 0 ? message.substring(0, colon) : message;
            refuse(switch (code) {
                case "UNKNOWN_AUDIT_FIELD", "INVALID_AUDIT_FIELD_PATH", "AUDIT_FIELD_MAPPING_CONFLICT",
                        "INVALID_AUDIT_ROUTING_VALUE", "INVALID_AUDIT_FIELD_PRESET" -> code;
                default -> "INVALID_AUDIT_FIELD_PATH";
            }, "dataprism.audit.output is not valid: " + code);
        }
        if (!blank(output.getJsonDirectory())) {
            if (!"hash-chained".equals(audit.getSink()) || blank(audit.getDirectory())) {
                refuse("AUDIT_JSON_REQUIRES_SEGMENTED_SINK",
                        "dataprism.audit.output.json-directory requires dataprism.audit.sink=hash-chained"
                                + " and dataprism.audit.directory");
            }
            if (sameOrInside(output.getJsonDirectory(), audit.getDirectory())
                    || sameOrInside(audit.getDirectory(), output.getJsonDirectory())) {
                refuse("AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT",
                        "dataprism.audit.output.json-directory must be separate from dataprism.audit.directory");
            }
        }
    }

    /**
     * Refuses a configured server port equal to {@code dataprism.operator.port}. {@code server.port}
     * is Spring Boot's own property, not part of this vocabulary, so the caller supplies it.
     *
     * @param serverPort the effective {@code server.port}, or {@code null} when it is not known
     */
    public void validateOperatorPort(Integer serverPort) {
        validateOperatorPort(serverPort, null);
    }

    /**
     * As {@link #validateOperatorPort(Integer)}, and also refuses a port equal to
     * {@code management.server.port}: the actuator listener must not share the operator connector.
     *
     * @param managementPort the effective {@code management.server.port}, or {@code null} when it is
     *                       unset or not known
     */
    public void validateOperatorPort(Integer serverPort, Integer managementPort) {
        if (operator.isEnabled() && operator.getPort() != null && serverPort != null && serverPort > 0
                && operator.getPort().equals(serverPort)) {
            refuse("OPERATOR_PORT_SHARED", "dataprism.operator.port must differ from server.port");
        }
        if (operator.isEnabled() && operator.getPort() != null && managementPort != null && managementPort > 0
                && operator.getPort().equals(managementPort)) {
            refuse("OPERATOR_PORT_SHARED", "dataprism.operator.port must differ from management.server.port");
        }
    }

    /**
     * The explicit-membership checks for {@code topology: embedded}, which are all on property
     * names, never values. When the application supplies its own cluster these settings would do
     * nothing, so setting any of them is refused rather than ignored.
     *
     * @param clusterSupplied whether the application defined its own {@code PrivacyCluster}
     * @param serverPort      the effective {@code server.port}, or {@code null} when not known
     * @param managementPort  the effective {@code management.server.port}, or {@code null}
     */
    public void validateCluster(boolean clusterSupplied, Integer serverPort, Integer managementPort) {
        if (!"embedded".equals(hazelcast.getTopology())) {
            return;
        }
        if (clusterSupplied) {
            if (hazelcast.clusterSettingsSet()) {
                refuse("CLUSTER_SETTINGS_IGNORED", "dataprism.hazelcast.cluster-name, join.* and member.* "
                        + "have no effect when the application supplies its own PrivacyCluster");
            }
            return;
        }
        HazelcastProperties h = hazelcast;
        required(h.getClusterName(), "MISSING_CLUSTER_NAME", "dataprism.hazelcast.cluster-name");
        if ("dev".equalsIgnoreCase(h.getClusterName().strip())) {
            refuse("RESERVED_CLUSTER_NAME", "dataprism.hazelcast.cluster-name must not be the Hazelcast default");
        }
        required(h.getJoin().getMode(), "MISSING_CLUSTER_JOIN", "dataprism.hazelcast.join.mode");
        HazelcastProperties.Kubernetes k = h.getJoin().getKubernetes();
        boolean kubernetesSet = !blank(k.getNamespace()) || !blank(k.getServiceName()) || !blank(k.getServiceDns());
        switch (h.getJoin().getMode().strip()) {
            case "tcp-ip" -> {
                if (h.getJoin().getMembers().isEmpty()) {
                    refuse("INVALID_CLUSTER_MEMBERS", "dataprism.hazelcast.join.members must name at least one member");
                }
                if (kubernetesSet) {
                    refuse("INVALID_KUBERNETES_JOIN", "dataprism.hazelcast.join.kubernetes.* applies only to "
                            + "join.mode kubernetes");
                }
            }
            case "kubernetes" -> {
                if (blank(k.getNamespace())) {
                    refuse("INVALID_KUBERNETES_JOIN", "dataprism.hazelcast.join.kubernetes.namespace must be set");
                }
                if (blank(k.getServiceName()) == blank(k.getServiceDns())) {
                    refuse("INVALID_KUBERNETES_JOIN", "exactly one of dataprism.hazelcast.join.kubernetes."
                            + "service-name and service-dns must be set");
                }
                if (!h.getJoin().getMembers().isEmpty()) {
                    refuse("INVALID_CLUSTER_MEMBERS", "dataprism.hazelcast.join.members applies only to "
                            + "join.mode tcp-ip");
                }
            }
            case "none" -> {
                if (!h.getJoin().getMembers().isEmpty()) {
                    refuse("INVALID_CLUSTER_MEMBERS", "dataprism.hazelcast.join.members applies only to "
                            + "join.mode tcp-ip");
                }
                if (kubernetesSet) {
                    refuse("INVALID_KUBERNETES_JOIN", "dataprism.hazelcast.join.kubernetes.* applies only to "
                            + "join.mode kubernetes");
                }
                if (!blank(h.getMember().getInterface())) {
                    refuse("INVALID_CLUSTER_INTERFACE", "dataprism.hazelcast.member.interface cannot be set "
                            + "with join.mode none, which binds 127.0.0.1");
                }
            }
            default -> refuse("UNSUPPORTED_CLUSTER_JOIN", "dataprism.hazelcast.join.mode must be tcp-ip, "
                    + "kubernetes or none");
        }
        int port = h.getMember().getPort() == null ? 5701 : h.getMember().getPort();
        if (serverPort != null && serverPort > 0 && port == serverPort) {
            refuse("CLUSTER_PORT_SHARED", "dataprism.hazelcast.member.port must differ from server.port");
        }
        if (managementPort != null && managementPort > 0 && port == managementPort) {
            refuse("CLUSTER_PORT_SHARED", "dataprism.hazelcast.member.port must differ from management.server.port");
        }
        if (operator.isEnabled() && operator.getPort() != null && port == operator.getPort()) {
            refuse("CLUSTER_PORT_SHARED", "dataprism.hazelcast.member.port must differ from dataprism.operator.port");
        }
    }

    private void validateOversight() {
        for (String tool : oversight.getApprovalRequiredTools()) {
            if (!OVERSIGHT_TOOLS.contains(tool)) {
                refuse("UNKNOWN_OVERSIGHT_TOOL",
                        "dataprism.oversight.approval-required-tools must name get_entity_context or compare_entity_sources");
            }
        }
        positive(oversight.getApprovalTtl(), "dataprism.oversight.approval-ttl");
        positive(oversight.getCallerRateLimit().getWindow(), "dataprism.oversight.caller-rate-limit.window");
        if (oversight.getCallerRateLimit().getRequests() != null && oversight.getCallerRateLimit().getRequests() <= 0) {
            refuse("INVALID_OVERSIGHT_LIMIT", "dataprism.oversight.caller-rate-limit.requests must be positive");
        }
        if (oversight.getMaxPendingPerRequester() <= 0) {
            refuse("INVALID_OVERSIGHT_LIMIT", "dataprism.oversight.max-pending-per-requester must be positive");
        }
        positive(reidentification.getApprovalTtl(), "dataprism.reidentification.approval-ttl");
        if (reidentification.getMaxPendingPerRequester() <= 0) {
            refuse("INVALID_OVERSIGHT_LIMIT",
                    "dataprism.reidentification.max-pending-per-requester must be positive");
        }
        if (reidentification.isEnabled()) {
            if (!hazelcast.isReidentificationEnabled()) {
                refuse("REIDENTIFICATION_INDEX_DISABLED",
                        "dataprism.reidentification.enabled requires dataprism.hazelcast.reidentification-enabled=true");
            }
            if (!"embedded".equals(hazelcast.getTopology())) {
                refuse("REIDENTIFICATION_REQUIRES_CLUSTER",
                        "dataprism.reidentification.enabled requires dataprism.hazelcast.topology=embedded");
            }
            if (reidentification.getPurposes().isEmpty() || reidentification.getPurposes().stream().anyMatch(p -> blank(p))) {
                refuse("EMPTY_REIDENTIFICATION_PURPOSES",
                        "dataprism.reidentification.purposes must name at least one non-blank purpose");
            }
            if (reidentification.isFourEyes() && reidentification.getRoles().values().stream()
                    .noneMatch(p -> p != null && p.contains(ReidentificationProperties.Permission.APPROVE))) {
                refuse("NO_REIDENTIFICATION_APPROVER",
                        "four-eyes re-identification requires a role holding APPROVE in dataprism.reidentification.roles");
            }
            if (!operator.isEnabled()) {
                refuse("REIDENTIFICATION_REQUIRES_OPERATOR_SURFACE",
                        "dataprism.reidentification.enabled requires dataprism.operator.enabled=true");
            }
        }
        if (operator.isEnabled() && (operator.getPort() == null || operator.getPort() < 1 || operator.getPort() > 65535
                || blank(operator.getRequiredAudience()) || blank(operator.getRequiredScope()))) {
            refuse("MISSING_OPERATOR_SECURITY",
                    "dataprism.operator.enabled requires port, required-audience and required-scope");
        }
        if (operator.isEnabled() && !blank(operator.getRequiredAudience())
                && operator.getRequiredAudience().equals(security.getJwt().getAudience())) {
            refuse("OPERATOR_AUDIENCE_SHARED",
                    "dataprism.operator.required-audience must differ from dataprism.security.jwt.audience");
        }
        if (operator.isEnabled() && !blank(operator.getAddress())) {
            try {
                java.net.InetAddress.getByName(operator.getAddress().trim());
            } catch (java.net.UnknownHostException | RuntimeException unresolvable) {
                refuse("INVALID_OPERATOR_ADDRESS", "dataprism.operator.address is not a usable address");
            }
        }
        if (!operator.isEnabled() && (!oversight.getApprovalRequiredTools().isEmpty()
                || oversight.getCallerRateLimit().getRequests() != null)) {
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
        required(security.getJwt().getIssuer(), "MISSING_JWT_ISSUER", "dataprism.security.jwt.issuer");
        required(security.getJwt().getAudience(), "MISSING_JWT_AUDIENCE", "dataprism.security.jwt.audience");
        if (blank(security.getJwt().getJwkSetUri()) == blank(security.getJwt().getIssuerDiscoveryUri())) {
            refuse("INVALID_JWT_LOCATION", "configure exactly one JWT JWKS or issuer-discovery URI");
        }
        trustedUri(
                blank(security.getJwt().getJwkSetUri()) ? security.getJwt().getIssuerDiscoveryUri() : security.getJwt().getJwkSetUri(),
                "INVALID_JWT_LOCATION",
                false);
        required(security.getCallerClaims().getPrincipal(), "MISSING_CALLER_CLAIM", "dataprism.security.caller-claims.principal");
        required(security.getCallerClaims().getRoles(), "MISSING_CALLER_CLAIM", "dataprism.security.caller-claims.roles");
        required(
                security.getCallerClaims().getInvestigation(),
                "MISSING_CALLER_CLAIM",
                "dataprism.security.caller-claims.investigation");
        if (Set.of(security.getCallerClaims().getPrincipal(), security.getCallerClaims().getRoles(), security.getCallerClaims().getInvestigation())
                .size()
                != 3) {
            refuse("DUPLICATE_CALLER_CLAIM", "principal, roles and investigation claims must be distinct");
        }
        if (ReservedArguments.NAMES.contains(security.getCallerClaims().getPrincipal())
                || ReservedArguments.NAMES.contains(security.getCallerClaims().getRoles())
                || ReservedArguments.NAMES.contains(security.getCallerClaims().getInvestigation())) {
            refuse("RESERVED_CALLER_CLAIM", "caller claim mappings cannot use reserved tool argument names");
        }
        validatePolicy();
        required(privacy.getProfile(), "MISSING_PRIVACY_PROFILE", "dataprism.privacy.profile");
        if (privacy.getScopeLifetime() == null || privacy.getScopeLifetime().isZero() || privacy.getScopeLifetime().isNegative()) {
            refuse("INVALID_SCOPE_LIFETIME", "dataprism.privacy.scope-lifetime must be positive");
        }
        required(privacy.getHmacKey().getKeyId(), "MISSING_HMAC_KEY_REFERENCE", "dataprism.privacy.hmac-key.key-id");
        if (blank(privacy.getHmacKey().getEnvironmentVariable()) == blank(privacy.getHmacKey().getProviderReference())) {
            refuse("MISSING_HMAC_KEY_REFERENCE",
                    "configure exactly one HMAC environment-variable or provider-reference");
        }
        if (!blank(privacy.getHmacKey().getValue())) {
            refuse("LITERAL_SECRET_FORBIDDEN", "dataprism.privacy.hmac-key.value is forbidden");
        }
        required(audit.getSink(), "MISSING_AUDIT_SINK", "dataprism.audit.sink");
        if (!Set.of(DataPrismProperties.APPROVED_SINK, "slf4j", "hash-chained").contains(audit.getSink())) {
            refuse("UNKNOWN_AUDIT_SINK", audit.getSink());
        }
        // Only hash-chained resolves to a bean that needs a file: see
        // DataPrismAutoConfiguration#dataPrismHashChainedAuditSink. Refusing here,
        // at property-validation time, means a missing path is a startup refusal
        // with a stable code rather than a NullPointerException once that bean is
        // actually constructed.
        if (!blank(audit.getDirectory()) && !blank(audit.getFilePath())) {
            refuse("AMBIGUOUS_AUDIT_LOCATION",
                    "set either dataprism.audit.directory or dataprism.audit.file-path, not both");
        }
        if ("hash-chained".equals(audit.getSink()) && blank(audit.getDirectory())) {
            required(audit.getFilePath(), "MISSING_AUDIT_FILE_PATH", "dataprism.audit.file-path");
        }
        if (!blank(audit.getDirectory())) {
            // Whatever the sink: the retention bean is created from the directory alone, and purge
            // reads its earlier anchors back from the checkpoint file. Without one it could never
            // prove a chain's start, so retention cannot run at all.
            if (blank(audit.getCheckpoint().getFilePath())) {
                refuse("RETENTION_REQUIRES_CHECKPOINT",
                        "dataprism.audit.directory requires dataprism.audit.checkpoint.file-path");
            }
        }
        if (!blank(audit.getCheckpoint().getFilePath())) {
            // An unparseable path is not waved through here: canonical() maps it to a path that never
            // matches, and it is then refused as AUDIT_CHECKPOINT_FILE_UNUSABLE when the checkpoint
            // sink tries to open it (DataPrismAutoConfiguration#dataPrismAuditCheckpointSink).
            String checkpoint = audit.getCheckpoint().getFilePath();
            if ((!blank(audit.getDirectory()) && (sameOrInside(checkpoint, audit.getDirectory())))
                    || (!blank(audit.getFilePath()) && sameOrInside(checkpoint, audit.getFilePath()))) {
                refuse("AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE",
                        "dataprism.audit.checkpoint.file-path must be separate from the audit file or directory");
            }
        }
        if (audit.getCheckpoint().getInterval() == null || audit.getCheckpoint().getInterval().isZero()
                || audit.getCheckpoint().getInterval().isNegative()) {
            refuse("INVALID_AUDIT_CHECKPOINT_INTERVAL", "dataprism.audit.checkpoint.interval must be positive");
        }
        if (audit.getRetention() == null) {
            refuse("AUDIT_RETENTION_BELOW_MINIMUM", "dataprism.audit.retention must be set");
        }
        if (!audit.isRetentionOverride()) {
            try {
                // AuditRetention owns the six-month rule; reuse it rather than restate it.
                new io.github.aindriub.dataprism.audit.retention.AuditRetention(java.nio.file.Path.of("."), audit.getRetention(),
                        c -> { }, java.time.Clock.systemUTC());
            } catch (IllegalArgumentException e) {
                refuse("AUDIT_RETENTION_BELOW_MINIMUM", "dataprism.audit.retention " + audit.getRetention()
                        + " is below six months; EU AI Act Art. 19 allows other periods only under Union or"
                        + " national law, in which case set dataprism.audit.retention-override=true");
            }
        }
        required(audit.getWriterId(), "MISSING_AUDIT_WRITER", "dataprism.audit.writer-id");
        // AuditRecorder appends "/" plus a per-boot suffix to build its instanceId, so a
        // writer-id containing "/" would make that split ambiguous -- refused here, at
        // property-validation time, with a stable startup code rather than an
        // IllegalArgumentException once the AuditRecorder bean is actually constructed.
        if (!blank(audit.getWriterId()) && audit.getWriterId().indexOf('/') >= 0) {
            refuse("INVALID_AUDIT_WRITER", "dataprism.audit.writer-id must not contain '/'");
        }
        if (audit.getCredentialReference() != null && audit.getCredentialReference().isBlank()) {
            refuse("INVALID_AUDIT_REFERENCE", "dataprism.audit.credential-reference");
        }
        required(metrics.getSink(), "MISSING_METRICS_SINK", "dataprism.metrics.sink");
        if (!Set.of("micrometer").contains(metrics.getSink())) {
            refuse("UNKNOWN_METRICS_SINK", metrics.getSink());
        }
        if (metrics.getRegistryReference() != null && metrics.getRegistryReference().isBlank()) {
            refuse("INVALID_METRICS_REFERENCE", "dataprism.metrics.registry-reference");
        }
        required(hazelcast.getTopology(), "MISSING_CLUSTER_TOPOLOGY", "dataprism.hazelcast.topology");
        if (!blank(hazelcast.getTopology()) && !Set.of("embedded", "single-node").contains(hazelcast.getTopology())) {
            refuse("UNSUPPORTED_HAZELCAST_TOPOLOGY", "topology must be embedded or single-node");
        }
        if (hazelcast.getIdentityCacheTtl() != null
                && (hazelcast.getIdentityCacheTtl().isZero() || hazelcast.getIdentityCacheTtl().isNegative())) {
            refuse("INVALID_HAZELCAST_TTL", "dataprism.hazelcast.identity-cache-ttl must be positive");
        }
        if (hazelcast.isPersistenceEnabled() || hazelcast.isMapStoreEnabled()) {
            refuse("UNSAFE_HAZELCAST_PERSISTENCE", "persistence and MapStore require a reviewed configuration");
        }
        if (!blank(hazelcast.getTlsKeyReference()) || !blank(hazelcast.getTlsTrustReference())) {
            refuse("HAZELCAST_TLS_UNSUPPORTED", "dataprism.hazelcast.tls-key-reference and "
                    + "dataprism.hazelcast.tls-trust-reference are not supported: Hazelcast member-to-member "
                    + "TLS is not available in the open-source edition. Isolate the cluster network instead "
                    + "(network isolation: a private network or Kubernetes NetworkPolicy)");
        }
        if ("single-node".equals(hazelcast.getTopology()) && hazelcast.clusterSettingsSet()) {
            refuse("CLUSTER_SETTINGS_IGNORED", "dataprism.hazelcast.cluster-name, join.* and member.* "
                    + "have no effect with topology single-node; remove them or use topology embedded");
        }
        if (hazelcast.isReidentificationEnabled() && blank(hazelcast.getReidentificationControlsReference())) {
            refuse("MISSING_REIDENTIFICATION_CONTROLS",
                    "enabled re-identification requires reviewed controls reference");
        }
    }

    private void validatePolicy() {
        if (securityPolicy.getPurposes() == null || securityPolicy.getPurposes().isEmpty()) {
            refuse("EMPTY_PURPOSE_LIST", "dataprism.security-policy.purposes");
        }
        for (String p : securityPolicy.getPurposes()) {
            required(p, "BLANK_PURPOSE", "dataprism.security-policy.purposes");
        }
        if (securityPolicy.getRoles() == null || securityPolicy.getRoles().isEmpty()) {
            refuse("MISSING_ROLE_POLICY", "dataprism.security-policy.roles");
        }
        securityPolicy.getRoles().forEach((role, caps) -> {
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
            trustedUri(source.getBaseUrl(), "INVALID_SOURCE_URL", fixture);
            if (source.getTimeout() == null || source.getTimeout().isZero() || source.getTimeout().isNegative()) {
                refuse("INVALID_SOURCE_TIMEOUT", name);
            }
            if (source.getMtls() != null && blank(source.getMtls().getKeyReference()) != blank(source.getMtls().getTrustReference())) {
                refuse("INVALID_SOURCE_MTLS", name + " requires key and trust references together");
            }
            if (!blank(source.getServiceCredentialReference()) && source.getServiceCredentialReference().contains(" ")) {
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
    public static boolean sameOrInside(String candidate, String location) {
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

    static boolean blank(String v) {
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
}
