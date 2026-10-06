package io.github.aindriub.dataprism.server.operator;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

import java.io.IOException;
import java.io.Writer;

/**
 * Tomcat answers some requests itself, before any Spring filter runs (an encoded slash, a malformed
 * request line), with an HTML report that names the status and echoes the failure. On the operator
 * port that report is replaced by {@code {"code":"..."}}. Every other port keeps Tomcat's behaviour.
 */
final class OperatorErrorReportValve extends ErrorReportValve {

    private final int operatorPort;

    OperatorErrorReportValve(int operatorPort) {
        this.operatorPort = operatorPort;
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        if (request.getLocalPort() != operatorPort) {
            super.report(request, response, throwable);
            return;
        }
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write("{\"code\":\"" + codeFor(status) + "\"}");
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException unwritable) {
            // The status line is already out; there is nothing more to say.
        }
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 414, 431 -> "INVALID_REQUEST";
            default -> status < 500 ? "INVALID_REQUEST" : "OPERATOR_ERROR";
        };
    }
}
