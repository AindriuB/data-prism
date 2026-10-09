package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditCheckpointSink;
import io.github.aindriub.dataprism.audit.AuditEventListener;
import io.github.aindriub.dataprism.audit.AuditEventListeners;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.checkpoint.FileAuditCheckpointSink;
import io.github.aindriub.dataprism.audit.retention.AuditRetention;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.spring.boot.validation.DataPrismPropertiesValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/** The audit recorder, checkpoint sink, retention and the maintenance task. */
@Configuration(proxyBeanMethods = false)
class AuditWiring {
    @Bean @ConditionalOnMissingBean @ConditionalOnBean(AuditSink.class)
    AuditRecorder dataPrismAuditRecorder(AuditSink sink, Clock clock, DataPrismProperties properties,
            ObjectProvider<AuditCheckpointSink> checkpoints,
            @Qualifier("dataPrismAuditEventListeners") AuditEventListeners listeners) {
        AuditCheckpointSink checkpoint = checkpoints.getIfAvailable();
        return new AuditRecorder(sink, clock, properties.getAudit().getWriterId(), checkpoint, listeners);
    }

    /**
     * Delivers each logged audit event to the application's {@link AuditEventListener} beans (in {@code
     * @Order}), after the configured sink accepted it, on one daemon thread behind a bounded queue.
     * Unconditional and not replaceable: an application adds listeners, it cannot swap the dispatcher
     * (a same-named bean is refused as a bean-definition override; a second bean of the type is refused
     * here), so a replacement cannot run listeners before the write or in the call. It starts no thread
     * when there are no listeners. The context drains it on close, after the recorder.
     */
    @Bean(destroyMethod = "close")
    AuditEventListeners dataPrismAuditEventListeners(ObjectProvider<AuditEventListener> listeners,
            DataPrismProperties properties, ConfigurableListableBeanFactory beanFactory) {
        if (beanFactory.getBeanNamesForType(AuditEventListeners.class, true, false).length > 1) {
            throw new DataPrismConfigurationException("AUDIT_EVENT_LISTENERS_NOT_REPLACEABLE",
                    "the audit event listener dispatcher is built in; add AuditEventListener beans instead");
        }
        return new AuditEventListeners(listeners.orderedStream().toList(),
                properties.getAudit().getListeners().getQueueCapacity());
    }

    private static final org.slf4j.Logger AUDIT_LOG = org.slf4j.LoggerFactory.getLogger(DataPrismAutoConfiguration.class);

    /**
     * The checkpoint file, kept apart from the audit file. A path that cannot be opened is logged
     * server-side only; the client-visible message never repeats it.
     */
    @Bean @ConditionalOnMissingBean(AuditCheckpointSink.class)
    @ConditionalOnProperty(prefix = "dataprism.audit.checkpoint", name = "file-path")
    FileAuditCheckpointSink dataPrismAuditCheckpointSink(DataPrismProperties properties,
            ObjectProvider<AuditSink> auditSink) {
        // Resolving the audit sink first makes it create the audit directory. Only then can the
        // containment check compare real paths: on a case-insensitive filesystem a not-yet-created
        // FRESH directory and a checkpoint under fresh/ look unrelated until the directory exists.
        auditSink.getIfAvailable();
        AuditProperties audit = properties.getAudit();
        String auditLocation = audit.getDirectory() != null && !audit.getDirectory().isBlank()
                ? audit.getDirectory() : audit.getFilePath();
        if (auditLocation != null && !auditLocation.isBlank()
                && DataPrismPropertiesValidator.sameOrInside(audit.getCheckpoint().getFilePath(), auditLocation)) {
            throw new DataPrismConfigurationException(FileAuditCheckpointSink.SAME_AS_AUDIT_FILE,
                    "dataprism.audit.checkpoint.file-path must not be the audit file");
        }
        try {
            return new FileAuditCheckpointSink(Path.of(audit.getCheckpoint().getFilePath()),
                    Path.of(auditLocation == null || auditLocation.isBlank() ? "." : auditLocation));
        } catch (FileAuditCheckpointSink.CheckpointSinkException e) {
            if (FileAuditCheckpointSink.SAME_AS_AUDIT_FILE.equals(e.code())) {
                throw new DataPrismConfigurationException(FileAuditCheckpointSink.SAME_AS_AUDIT_FILE,
                        "dataprism.audit.checkpoint.file-path must not be the audit file");
            }
            AUDIT_LOG.error("dataprism.audit.checkpoint.file-path could not be opened", e);
            throw new DataPrismConfigurationException("AUDIT_CHECKPOINT_FILE_UNUSABLE",
                    "dataprism.audit.checkpoint.file-path could not be opened");
        }
    }
    /** Daily purge of expired segments; present only when {@code dataprism.audit.directory} is set. */
    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dataprism.audit", name = "directory")
    AuditRetention dataPrismAuditRetention(DataPrismProperties properties, Clock clock,
            ObjectProvider<AuditCheckpointSink> checkpoints) {
        AuditProperties audit = properties.getAudit();
        return new AuditRetention(Path.of(audit.getDirectory()), audit.getRetention(),
                checkpoints.getObject(), clock, audit.isRetentionOverride());
    }
    /**
     * Runs the purge once now and then every 24h, and a PERIODIC checkpoint every {@code
     * checkpoint.interval}, the first soon after boot. When it checkpoints, it is registered as
     * depending on the recorder, so on context close it stops (and its last PERIODIC is written)
     * before the recorder writes its SHUTDOWN checkpoint.
     */
    @Bean(destroyMethod = "close")
    AuditMaintenance dataPrismAuditMaintenance(DataPrismProperties properties, ObjectProvider<AuditRecorder> recorder,
            ObjectProvider<AuditRetention> retention, ObjectProvider<PrivacyMetrics> metrics,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        String checkpointPath = properties.getAudit().getCheckpoint().getFilePath();
        AuditRecorder checkpointing = checkpointPath == null || checkpointPath.isBlank() ? null
                : recorder.getIfAvailable();
        if (checkpointing != null) {
            // by type, so an application AuditRecorder under any other name is ordered too
            for (String name : beanFactory.getBeanNamesForType(AuditRecorder.class)) {
                beanFactory.registerDependentBean(name, "dataPrismAuditMaintenance");
            }
        }
        return new AuditMaintenance(checkpointing,
                retention.getIfAvailable(), properties.getAudit().getCheckpoint().getInterval(),
                metrics.getIfAvailable(PrivacyMetrics::none));
    }
}
