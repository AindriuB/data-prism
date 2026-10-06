package io.github.aindriub.dataprism.server.operator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * A request Spring Security's firewall refuses (a double slash, a path parameter, an encoded
 * traversal) never reaches a controller. On the operator port it is answered here with
 * {@code {"code":"INVALID_REQUEST"}} and nothing derived from the request. On the MCP port the
 * default behaviour is kept.
 */
@Component
@ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
final class OperatorRequestRejectedHandler implements RequestRejectedHandler {

    private final int operatorPort;
    private final RequestRejectedHandler fallback = new HttpStatusRequestRejectedHandler();

    OperatorRequestRejectedHandler(io.github.aindriub.dataprism.spring.boot.DataPrismProperties properties) {
        Integer port = properties.getOperator().getPort();
        this.operatorPort = port == null ? -1 : port;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       RequestRejectedException rejected) throws IOException, jakarta.servlet.ServletException {
        if (request.getLocalPort() != operatorPort) {
            fallback.handle(request, response, rejected);
            return;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"INVALID_REQUEST\"}");
        response.getWriter().flush();
    }
}
