package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditProperties {
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
