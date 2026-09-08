package io.github.aindriub.dataprism.core;

import java.util.List;

/**
 * Compares what the sources said about one subject.
 *
 * <p>Runs on the trusted side, on raw records, before anything is scrubbed. It
 * has to: the pseudonym for a subject is keyed on the subject, so once the
 * records are scrubbed every source's version of a name is the same string and
 * there is nothing left to compare. Correlation after scrubbing would report
 * perfect agreement about data that agrees on nothing.
 *
 * <p>Fields are matched across sources by {@link
 * io.github.aindriub.dataprism.annotations.PrivacyNamespace}, not by name. That
 * is what the namespace is for — {@code customerName} in one system and
 * {@code holderName} in another are the same fact — and it is also why a field
 * with no namespace is not correlated at all: two fields both called
 * {@code status} in different systems are usually not the same fact, and
 * comparing them would manufacture findings rather than report them.
 */
public interface EntityCorrelationService {

    List<ConsistencyFinding> correlate(List<SourceRecord> records, PrivacyContext context);

    /** One source's raw record, before scrubbing. */
    record SourceRecord(String sourceName, Object record) {
    }
}
