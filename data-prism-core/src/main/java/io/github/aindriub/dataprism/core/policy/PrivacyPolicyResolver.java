package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.PrivacyContext;

/**
 * Decides what actually happens to a field.
 *
 * <p>Separated from the metadata that describes it, because they answer
 * different questions and change on different schedules: what a field
 * <em>is</em> belongs to the model author and changes with the schema; what to
 * <em>do</em> with it belongs to the operator and changes with policy.
 */
public interface PrivacyPolicyResolver {

    EffectivePrivacyPolicy resolve(FieldMetadata field, PrivacyContext context);
}
