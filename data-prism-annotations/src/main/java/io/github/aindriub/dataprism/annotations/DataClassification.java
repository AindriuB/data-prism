package io.github.aindriub.dataprism.annotations;

/**
 * What kind of sensitive data a field holds. Classification says what the value
 * <em>is</em>; the runtime policy decides what happens to it.
 */
public enum DataClassification {

    PII,
    PHI,
    FINANCIAL,
    BANKING,
    TAX_IDENTIFIER,
    GOVERNMENT_IDENTIFIER,
    ADDRESS,
    CONTACT,
    CREDENTIAL,
    SECURITY,
    CONFIDENTIAL
}
