package io.github.aindriub.dataprism.annotations;

/** How damaging disclosure of a value would be. Input to policy, not a decision. */
public enum SensitivityLevel {

    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
