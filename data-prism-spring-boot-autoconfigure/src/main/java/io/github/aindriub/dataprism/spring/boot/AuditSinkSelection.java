package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.retention.JsonAuditRetention;
import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.audit.sink.SegmentedJsonAuditSink;
import io.github.aindriub.dataprism.audit.sink.Slf4jAuditSink;
import io.github.aindriub.dataprism.audit.sink.TeeAuditSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Selects a built-in {@link AuditSink} for an operator with no Java to
 * write, mirroring {@link IdentityResolverSelection} exactly, including
 * why its {@code @Bean} methods live here rather than directly on {@link
 * DataPrismAutoConfiguration}: without the same import-ordering trick,
 * {@link AuditWiring#dataPrismAuditRecorder}'s own {@code
 * @ConditionalOnBean(AuditSink.class)} would be evaluated before either
 * bean below is registered, and would never see the one the configured
 * sink value should have produced.
 */
@Configuration(proxyBeanMethods = false)
class AuditSinkSelection {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuditSinkSelection.class);

    /**
     * Nothing read from a source payload reaches this sink; see {@link
     * Slf4jAuditSink}'s own class Javadoc for why that is what makes it
     * safe to ship to ordinary log infrastructure.
     */
    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    @ConditionalOnProperty(prefix = "dataprism.audit", name = "sink", havingValue = "slf4j")
    AuditSink dataPrismSlf4jAuditSink(DataPrismProperties properties) {
        AuditProperties.Output output = properties.getAudit().getOutput();
        // Nothing configured keeps the message-only form; any output setting adds the mapped pairs.
        return output.isDefault() ? new Slf4jAuditSink()
                : new Slf4jAuditSink(output.mapping(), output.getRouting().toRouting());
    }

    /**
     * Wires {@code dataprism.audit.sink=hash-chained} to task 64's {@link
     * FileAuditSink}, bound to {@code dataprism.audit.file-path}. A blank
     * or missing path is already refused earlier, at {@link
     * DataPrismProperties#validate()}, with {@code
     * MISSING_AUDIT_FILE_PATH} — this method only ever runs with a
     * non-blank value. A path that cannot actually be opened (a parent
     * directory that does not exist, or one this process cannot write
     * to) is refused here instead, at startup, rather than surfacing on
     * the first audited request: {@link FileAuditSink.OpenFailedException}
     * is caught and re-thrown as a {@link DataPrismConfigurationException}
     * with a stable code, deliberately without repeating the configured
     * path in the message — the same choice {@link
     * PrivacyEngineWiring#dataPrismModelDescriptors} already makes for {@code
     * dataprism.privacy.descriptor-file} — so a value an operator chose
     * never reaches whatever renders this refusal.
     *
     * <p><b>What this does not close.</b> This bean's own construction
     * failure is a startup refusal: it is never reachable by an MCP
     * client, because the application never finishes starting. A
     * <em>runtime</em> write failure from the sink this method returns is
     * a different matter: {@link FileAuditSink}'s own exceptions
     * deliberately name the configured path — its own acceptance list
     * requires that — but task 74 closed the disclosure this javadoc used
     * to describe here. {@code GetEntityContextTool} and {@code
     * CompareEntitySourcesTool} now catch a thrown {@link AuditSink}
     * exception at the {@code deny}/{@code denyUnauthenticated} call
     * sites and return a fixed, path-free message, logging the caught
     * exception server-side instead — see {@code
     * AuditSinkFailureAbortsResponseTest} in {@code
     * data-prism-integration-tests}, which now asserts the sink's
     * message and the configured path are both absent from what the
     * client receives, and {@code docs/conventions.md}'s "no `catch`
     * block logs the object it caught" exception for why that server-side
     * log line is sanctioned rather than a leak. What wiring a real file
     * path behind {@code hash-chained} does not close is a narrower gap:
     * {@link io.github.aindriub.dataprism.audit.AuditEventHash} is
     * unkeyed SHA-256, so this chain resists an outside forger but not
     * the operator running this process — anyone with write access to
     * the configured file can recompute every hash in it and produce a
     * chain that still verifies. That gap is recorded and deliberately
     * deprioritised by the owner, not closed by this task; nothing this
     * bean does makes the audit file tamper-proof, immutable, or usable
     * as evidence against the operator.
     */
    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    @ConditionalOnProperty(prefix = "dataprism.audit", name = "sink", havingValue = "hash-chained")
    AuditSink dataPrismHashChainedAuditSink(DataPrismProperties properties,
            ObjectProvider<JsonProjection> projection) {
        AuditSink primary = openPrimary(properties);
        JsonProjection json = projection.getIfAvailable();
        return json == null ? primary : new TeeAuditSink(primary, json.asSink());
    }

    /**
     * The optional JSON projection of the hash-chained audit, present only when {@code
     * dataprism.audit.output.json-directory} has text. Its own bean so the application context
     * classifies it ({@link PrivacyExtensionPoints}) and closes it. Not {@code
     * @ConditionalOnMissingBean}: the type is package-private and final, so an application cannot
     * supply a competing bean, and a bean of the same name is refused by the context's default
     * refusal to override a bean definition. A directory that cannot be opened is refused at
     * startup, before the authoritative sink is opened, without repeating the path.
     */
    @Bean
    @ConditionalOnProperty(prefix = "dataprism.audit", name = "sink", havingValue = "hash-chained")
    @ConditionalOnExpression("T(org.springframework.util.StringUtils)"
            + ".hasText('${dataprism.audit.output.json-directory:}')")
    JsonProjection dataPrismJsonAuditProjection(DataPrismProperties properties, ObjectProvider<Clock> clock) {
        AuditProperties.Output output = properties.getAudit().getOutput();
        SegmentedJsonAuditSink json;
        try {
            json = new SegmentedJsonAuditSink(Path.of(output.getJsonDirectory()), output.mapping(),
                    output.getRouting().toRouting());
        } catch (FileAuditSink.OpenFailedException e) {
            LOG.error("dataprism.audit.output.json-directory could not be opened", e);
            throw new DataPrismConfigurationException("AUDIT_JSON_DIRECTORY_UNUSABLE",
                    "dataprism.audit.output.json-directory could not be opened for writing");
        }
        try {
            return new JsonProjection(json, new JsonAuditRetention(Path.of(output.getJsonDirectory()),
                    properties.getAudit().getRetention(), clock.getIfAvailable(Clock::systemUTC),
                    properties.getAudit().isRetentionOverride()));
        } catch (RuntimeException e) {
            closeQuietly(json);
            throw e;
        }
    }

    /** The authoritative sink. The cause can name a filesystem path, so it is logged and never repeated. */
    static AuditSink openPrimary(DataPrismProperties properties) {
        String directory = properties.getAudit().getDirectory();
        Path path = Path.of(directory == null || directory.isBlank()
                ? properties.getAudit().getFilePath() : directory);
        try {
            return directory == null || directory.isBlank() ? new FileAuditSink(path)
                    : new SegmentedFileAuditSink(path);
        } catch (FileAuditSink.OpenFailedException e) {
            // Logging the caught exception object here is the same sanctioned
            // exception docs/conventions.md records for GetEntityContextTool and
            // CompareEntitySourcesTool: the cause can name a server filesystem
            // path, so it stays server-side only, never repeated in the
            // DataPrismConfigurationException message thrown below.
            LOG.error("dataprism.audit.file-path or directory could not be opened for the hash-chained audit sink", e);
            throw new DataPrismConfigurationException("AUDIT_SINK_FILE_UNUSABLE",
                    "dataprism.audit.file-path or directory could not be opened for the hash-chained audit sink");
        }
    }

    static void closeQuietly(Object sink) {
        if (sink instanceof java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (java.io.IOException | RuntimeException ignored) {
                // the startup refusal is the failure that matters
            }
        }
    }
}
