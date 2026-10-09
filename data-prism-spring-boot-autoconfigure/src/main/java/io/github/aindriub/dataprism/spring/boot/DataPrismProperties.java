package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.spring.boot.validation.DataPrismPropertiesValidator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @NestedConfigurationProperty
    private TransportProperties transport = new TransportProperties();
    @NestedConfigurationProperty
    private SecurityProperties security = new SecurityProperties();
    @NestedConfigurationProperty
    private SecurityPolicyProperties securityPolicy = new SecurityPolicyProperties();
    @NestedConfigurationProperty
    private PrivacyProperties privacy = new PrivacyProperties();
    @NestedConfigurationProperty
    private AuditProperties audit = new AuditProperties();
    @NestedConfigurationProperty
    private CorrelationProperties correlation = new CorrelationProperties();
    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();
    @NestedConfigurationProperty
    private HazelcastProperties hazelcast = new HazelcastProperties();
    @NestedConfigurationProperty
    private IdentityProperties identity = new IdentityProperties();
    @NestedConfigurationProperty
    private OversightProperties oversight = new OversightProperties();
    @NestedConfigurationProperty
    private ReidentificationProperties reidentification = new ReidentificationProperties();
    @NestedConfigurationProperty
    private OperatorProperties operator = new OperatorProperties();
    /** Named source entries, one per configured Java-first REST adapter, each with base-url, timeout and mTLS or credential references. */
    private Map<String, SourceProperties> sources = new LinkedHashMap<>();

    public TransportProperties getTransport() {
        return transport;
    }

    public void setTransport(TransportProperties v) {
        transport = v == null ? new TransportProperties() : v;
    }

    public SecurityProperties getSecurity() {
        return security;
    }

    public void setSecurity(SecurityProperties v) {
        security = v == null ? new SecurityProperties() : v;
    }

    public SecurityPolicyProperties getSecurityPolicy() {
        return securityPolicy;
    }

    public void setSecurityPolicy(SecurityPolicyProperties v) {
        securityPolicy = v == null ? new SecurityPolicyProperties() : v;
    }

    public PrivacyProperties getPrivacy() {
        return privacy;
    }

    public void setPrivacy(PrivacyProperties v) {
        privacy = v == null ? new PrivacyProperties() : v;
    }

    public AuditProperties getAudit() {
        return audit;
    }

    public void setAudit(AuditProperties v) {
        audit = v == null ? new AuditProperties() : v;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsProperties v) {
        metrics = v == null ? new MetricsProperties() : v;
    }

    public HazelcastProperties getHazelcast() {
        return hazelcast;
    }

    public void setHazelcast(HazelcastProperties v) {
        hazelcast = v == null ? new HazelcastProperties() : v;
    }

    public IdentityProperties getIdentity() {
        return identity;
    }

    public void setIdentity(IdentityProperties v) {
        identity = v == null ? new IdentityProperties() : v;
    }

    public OversightProperties getOversight() {
        return oversight;
    }

    public void setOversight(OversightProperties v) {
        oversight = v == null ? new OversightProperties() : v;
    }

    public ReidentificationProperties getReidentification() {
        return reidentification;
    }

    public void setReidentification(ReidentificationProperties v) {
        reidentification = v == null ? new ReidentificationProperties() : v;
    }

    public CorrelationProperties getCorrelation() {
        return correlation;
    }

    public void setCorrelation(CorrelationProperties v) {
        correlation = v == null ? new CorrelationProperties() : v;
    }

    public OperatorProperties getOperator() {
        return operator;
    }

    public void setOperator(OperatorProperties v) {
        operator = v == null ? new OperatorProperties() : v;
    }

    public Map<String, SourceProperties> getSources() {
        return sources;
    }

    public void setSources(Map<String, SourceProperties> v) {
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

}
