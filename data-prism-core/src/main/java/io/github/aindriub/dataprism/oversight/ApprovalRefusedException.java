package io.github.aindriub.dataprism.oversight;

/** An approve, reject or bounded create that the store refused. */
public final class ApprovalRefusedException extends RuntimeException {

    public enum Code { UNKNOWN_APPROVAL, NOT_PENDING, EXPIRED, SELF_APPROVAL, TOO_MANY_PENDING }

    private final Code code;

    public ApprovalRefusedException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
