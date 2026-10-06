package io.github.aindriub.dataprism.server.operator;

import org.springframework.http.HttpStatus;

/** A refusal with a stable code. Carries no message text, so none can leak into a response. */
final class OperatorException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    OperatorException(HttpStatus status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
