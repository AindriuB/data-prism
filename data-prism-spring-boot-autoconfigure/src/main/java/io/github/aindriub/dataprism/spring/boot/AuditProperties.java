package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditProperties {
    /** Audit sink: approved-sink (an application-supplied AuditSink bean), slf4j or hash-chained. Required in production. */
    private String sink;
    /** Identity of this writer in the audit trail. Required for every sink. */
    private String writerId;
    /** Reference to the sink credentials. A reference only, never a literal secret. */
    private String credentialReference;
    /** Single hash-chained audit file. Required with sink hash-chained unless directory is set; mutually exclusive with directory. */
    private String filePath;
    /** With hash-chained: directory receiving one audit-YYYY-MM-DD.log segment per UTC day. Mutually exclusive with file-path. */
    private String directory;
    /** With directory: segments dated before today minus this period are deleted after a retention anchor is recorded. Defaults to six months. */
    private java.time.Period retention = java.time.Period.ofMonths(6);
    /** Must be true to accept a retention shorter than six months. Inert with six months or more. */
    private boolean retentionOverride;
    private final Checkpoint checkpoint = new Checkpoint();
    private final Output output = new Output();
    /** Optional entity type names the audit trail may record. Each entry must match [A-Za-z][A-Za-z0-9_-]{0,63}; empty by default. */
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
        /** Field naming of the JSON audit rendering: canonical (each field under its own name) or ecs (Elastic Common Schema names). */
        private String fieldPreset = "canonical";
        /** Overrides the output path of one canonical field, keyed by field name. A dot nests; a path matches [A-Za-z_@][A-Za-z0-9_@]*(\.[A-Za-z0-9_@]+)*. */
        private Map<String, String> fieldNames = new LinkedHashMap<>();
        private final Routing routing = new Routing();
        /** Also write each event as audit-YYYY-MM-DD.ndjson segments in this directory. Needs sink hash-chained with directory. */
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
            /** Constant written as event.dataset: 1 to 100 characters of [a-z0-9_.]. */
            private String eventDataset;
            /** Constant written as data_stream.type: logs. */
            private String dataStreamType;
            /** Constant written as data_stream.dataset: [a-z0-9_.], 1 to 100 characters. */
            private String dataStreamDataset;
            /** Constant written as data_stream.namespace: [a-z0-9_], 1 to 100 characters. */
            private String dataStreamNamespace;

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
        /** Separate file receiving BOOT, PERIODIC, SHUTDOWN and RETENTION_ANCHOR checkpoints. Required with directory; keep it under different access controls from the audit files. */
        private String filePath;
        /** How often a PERIODIC checkpoint is written; the first is written soon after boot. Defaults to five minutes. */
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
