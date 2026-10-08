package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.security.ReservedArguments;
import io.github.aindriub.dataprism.spring.boot.validation.DataPrismPropertiesValidator;
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
    private Correlation correlation = new Correlation();
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

    public Correlation getCorrelation() {
        return correlation;
    }

    public void setCorrelation(Correlation v) {
        correlation = v == null ? new Correlation() : v;
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

    /** Runs the cross-property checks; see {@link DataPrismPropertiesValidator}. */
    public void validate() {
        new DataPrismPropertiesValidator(this).validate();
    }

    /**
     * Refuses a configured server port equal to {@code dataprism.operator.port}. {@code server.port}
     * is Spring Boot's own property, not part of this vocabulary, so the caller supplies it.
     */
    void validateOperatorPort(Integer serverPort) {
        new DataPrismPropertiesValidator(this).validateOperatorPort(serverPort);
    }

    /** As {@link #validateOperatorPort(Integer)}, and also refuses a port equal to {@code management.server.port}. */
    void validateOperatorPort(Integer serverPort, Integer managementPort) {
        new DataPrismPropertiesValidator(this).validateOperatorPort(serverPort, managementPort);
    }

    /** The explicit-membership checks for {@code topology: embedded}; names only, never values. */
    void validateCluster(boolean clusterSupplied, Integer serverPort, Integer managementPort) {
        new DataPrismPropertiesValidator(this).validateCluster(clusterSupplied, serverPort, managementPort);
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
        private final Output output = new Output();
        private List<String> entityTypes = new java.util.ArrayList<>();

        /**
         * The entity types an audit record's {@code entityType} may hold verbatim. Empty (the default)
         * means the shape fallback {@code [A-Z][A-Z0-9_]{0,63}}; anything else is audited as
         * {@code <unregistered>}.
         */
        public List<String> getEntityTypes() {
            return entityTypes;
        }

        public void setEntityTypes(List<String> v) {
            entityTypes = v == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
        }

        public Output getOutput() {
            return output;
        }

        /**
         * How the JSON rendering of an audit event is shaped: the field preset and overrides,
         * routing constants, and an optional directory for the {@code .ndjson} projection.
         */
        public static class Output {
            private String fieldPreset = "canonical";
            private Map<String, String> fieldNames = new LinkedHashMap<>();
            private final Routing routing = new Routing();
            private String jsonDirectory;

            public String getFieldPreset() {
                return fieldPreset;
            }

            public void setFieldPreset(String v) {
                fieldPreset = v;
            }

            public Map<String, String> getFieldNames() {
                return fieldNames;
            }

            public void setFieldNames(Map<String, String> v) {
                fieldNames = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            }

            public Routing getRouting() {
                return routing;
            }

            public String getJsonDirectory() {
                return jsonDirectory;
            }

            public void setJsonDirectory(String v) {
                jsonDirectory = v;
            }

            /** True when nothing here departs from the canonical names with no routing. */
            boolean isDefault() {
                return "canonical".equals(fieldPreset) && fieldNames.isEmpty() && routing.isEmpty();
            }

            /** The bound mapping; throws {@code IllegalArgumentException} led by a stable code. */
            public io.github.aindriub.dataprism.audit.format.AuditFieldMapping mapping() {
                io.github.aindriub.dataprism.audit.format.AuditFieldMapping base = switch (fieldPreset == null ? "" : fieldPreset) {
                    case "canonical" -> io.github.aindriub.dataprism.audit.format.AuditFieldMapping.canonical();
                    case "ecs" -> io.github.aindriub.dataprism.audit.format.AuditFieldMapping.ecs();
                    default -> throw new IllegalArgumentException(
                            "INVALID_AUDIT_FIELD_PRESET: field-preset must be canonical or ecs");
                };
                return base.withOverrides(fieldNames);
            }

            public static class Routing {
                private String eventDataset, dataStreamType, dataStreamDataset, dataStreamNamespace;

                public String getEventDataset() {
                    return eventDataset;
                }

                public void setEventDataset(String v) {
                    eventDataset = v;
                }

                public String getDataStreamType() {
                    return dataStreamType;
                }

                public void setDataStreamType(String v) {
                    dataStreamType = v;
                }

                public String getDataStreamDataset() {
                    return dataStreamDataset;
                }

                public void setDataStreamDataset(String v) {
                    dataStreamDataset = v;
                }

                public String getDataStreamNamespace() {
                    return dataStreamNamespace;
                }

                public void setDataStreamNamespace(String v) {
                    dataStreamNamespace = v;
                }

                boolean isEmpty() {
                    return eventDataset == null && dataStreamType == null && dataStreamDataset == null
                            && dataStreamNamespace == null;
                }

                public io.github.aindriub.dataprism.audit.format.AuditRouting toRouting() {
                    return new io.github.aindriub.dataprism.audit.format.AuditRouting(eventDataset, dataStreamType,
                            dataStreamDataset, dataStreamNamespace);
                }
            }
        }

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

    public static class Correlation {
        private final Inbound inbound = new Inbound();
        private final Outbound outbound = new Outbound();

        public Inbound getInbound() {
            return inbound;
        }

        public Outbound getOutbound() {
            return outbound;
        }

        public static class Inbound {
            private String header;
            private String format = "opaque";
            private String pattern;
            private boolean required;

            public String getHeader() {
                return header;
            }

            public void setHeader(String v) {
                header = v;
            }

            public String getFormat() {
                return format;
            }

            public void setFormat(String v) {
                format = v;
            }

            /** Unset means {@code CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN}. */
            public String getPattern() {
                return pattern;
            }

            public void setPattern(String v) {
                pattern = v;
            }

            public boolean isRequired() {
                return required;
            }

            public void setRequired(boolean v) {
                required = v;
            }

            public String effectivePattern() {
                return pattern == null
                        ? io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.DEFAULT_OPAQUE_PATTERN
                        : pattern;
            }

            /** The policy for the configured format; only meaningful after {@code validate()}. */
            public io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy policy() {
                return "traceparent".equals(format)
                        ? io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.traceparent()
                        : io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy.opaque(effectivePattern());
            }
        }

        public static class Outbound {
            private String header;

            public String getHeader() {
                return header;
            }

            public void setHeader(String v) {
                header = v;
            }
        }

        private String mdcKey;

        /**
         * The SLF4J MDC key the validated external correlation id is put under for the duration of a
         * tool call. Unset (the default) means MDC is never touched.
         */
        public String getMdcKey() {
            return mdcKey;
        }

        public void setMdcKey(String v) {
            mdcKey = v;
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
        private static boolean isBlank(String v) {
            return v == null || v.isBlank();
        }

        public boolean clusterSettingsSet() {
            return !isBlank(clusterName) || !isBlank(join.mode) || !join.members.isEmpty()
                    || !isBlank(join.kubernetes.namespace) || !isBlank(join.kubernetes.serviceName)
                    || !isBlank(join.kubernetes.serviceDns) || member.port != null || !isBlank(member.interfaceAddress);
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
