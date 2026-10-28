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
    CONFIDENTIAL,
    BIOMETRIC,
    GENETIC,
    ETHNIC_ORIGIN,
    POLITICAL_OPINION,
    RELIGIOUS_BELIEF,
    TRADE_UNION,
    SEX_LIFE_ORIENTATION,

    /**
     * Unstructured text, such as notes or comments, that may contain personal
     * data in prose. Nothing about its content can be assumed, so a profile
     * should treat it as sensitive.
     */
    FREE_TEXT;

    /**
     * GDPR Art. 9 special categories. Policy never lets a field carrying any of
     * these reach the model weaker than {@code REDACT}.
     */
    public static final java.util.Set<DataClassification> SPECIAL_CATEGORIES = java.util.Set.of(
            PHI, BIOMETRIC, GENETIC, ETHNIC_ORIGIN, POLITICAL_OPINION,
            RELIGIOUS_BELIEF, TRADE_UNION, SEX_LIFE_ORIENTATION);
}
