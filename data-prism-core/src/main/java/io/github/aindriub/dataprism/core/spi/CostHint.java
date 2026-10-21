package io.github.aindriub.dataprism.core.spi;

/** Advisory cost of one read from a source. It does not change how the read budget is charged. */
public enum CostHint {
    LOW, MEDIUM, HIGH
}
