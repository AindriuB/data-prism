package io.github.aindriub.dataprism.core.link;

/** Whether the labels in a catalog listing are aliases or real names; bound into a page link. */
public enum LinkLabelKind {
    ALIAS(0),
    REAL(1);

    private final int code;

    LinkLabelKind(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    static LinkLabelKind fromCode(int code) {
        for (LinkLabelKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        return null;
    }
}
