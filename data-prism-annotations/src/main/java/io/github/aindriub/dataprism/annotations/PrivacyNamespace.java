package io.github.aindriub.dataprism.annotations;

/**
 * The semantic identity of a value, independent of what any one system calls it.
 *
 * <p>This is what makes cross-system consistency work. A {@code name} in one API
 * and a {@code customerName} in another both declare
 * {@link #PERSON_NAME}, so the same subject resolves to the same synthetic name
 * in both, within one privacy scope.
 *
 * <p>The namespace is part of the pseudonymisation key. Changing a field's
 * namespace changes its synthetic value.
 */
public enum PrivacyNamespace {

    NONE,

    PERSON_IDENTITY,
    PERSON_NAME,
    PERSON_FIRST_NAME,
    PERSON_LAST_NAME,

    ORGANISATION_IDENTITY,
    ORGANISATION_NAME,

    ACCOUNT_IDENTITY,

    ADDRESS,
    EMAIL,
    PHONE,

    GOVERNMENT_IDENTIFIER,

    FINANCIAL_VALUE,
    BANK_ACCOUNT,

    EMPLOYMENT_IDENTITY,

    CUSTOM
}
