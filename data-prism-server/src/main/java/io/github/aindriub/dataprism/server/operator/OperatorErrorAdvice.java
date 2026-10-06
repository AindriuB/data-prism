package io.github.aindriub.dataprism.server.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Every error body is {@code {"code":"..."}}. No exception message is ever copied into a response,
 * and only the exception's class name is logged: a message could carry a request value.
 *
 * <p>Limited to the operator controllers, so an MVC exception on the MCP port goes to Boot's error
 * path as it always did. A request that matches no operator handler (an unknown path, a wrong method)
 * falls to {@link OperatorErrorController}, which answers with a code on the operator port.
 */
@RestControllerAdvice(assignableTypes = {OversightOperatorController.class, ReidentificationOperatorController.class})
@ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
final class OperatorErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorErrorAdvice.class);

    @ExceptionHandler(OperatorException.class)
    ResponseEntity<Map<String, String>> refused(OperatorException e) {
        return body(e.status(), e.code());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> methodNotAllowed() {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, String>> mediaType() {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> failed(Exception e) {
        LOG.warn("operator request failed: {}", e.getClass().getSimpleName());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "OPERATOR_ERROR");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
