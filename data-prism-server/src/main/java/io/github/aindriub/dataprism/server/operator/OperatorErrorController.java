package io.github.aindriub.dataprism.server.operator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Replaces Boot's error endpoint so that anything which falls out of the filter chain on the
 * operator port (a request the HTTP firewall rejected, an unmapped path, a container-level error)
 * is answered with {@code {"code":"..."}} and nothing else: no timestamp, no path, no message.
 * Requests on the MCP port are handed to Boot's own controller, unchanged.
 */
@Controller
@RequestMapping("${spring.web.error.path:${error.path:/error}}")
@ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
final class OperatorErrorController implements ErrorController {

    private final BasicErrorController boot;
    private final int operatorPort;

    OperatorErrorController(ErrorAttributes errorAttributes, org.springframework.boot.autoconfigure.web.WebProperties web,
                            io.github.aindriub.dataprism.spring.boot.DataPrismProperties properties,
                            List<org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver> views) {
        this.boot = new BasicErrorController(errorAttributes, web.getError(), views);
        Integer port = properties.getOperator().getPort();
        this.operatorPort = port == null ? -1 : port;
    }

    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    ModelAndView errorHtml(HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        if (onOperatorPort(request)) {
            HttpStatus status = status(request);
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"" + codeFor(status) + "\"}");
            return null;
        }
        return boot.errorHtml(request, response);
    }

    @RequestMapping
    ResponseEntity<?> error(HttpServletRequest request) {
        if (!onOperatorPort(request)) {
            return boot.error(request);
        }
        HttpStatus status = status(request);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", codeFor(status)));
    }

    private boolean onOperatorPort(HttpServletRequest request) {
        return request.getLocalPort() == operatorPort;
    }

    private static HttpStatus status(HttpServletRequest request) {
        Object value = request.getAttribute("jakarta.servlet.error.status_code");
        if (value instanceof Integer code) {
            HttpStatus status = HttpStatus.resolve(code);
            if (status != null) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    static String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is4xxClientError() ? "INVALID_REQUEST" : "OPERATOR_ERROR";
        };
    }
}
