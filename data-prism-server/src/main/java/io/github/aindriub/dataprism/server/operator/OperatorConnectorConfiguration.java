package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The second listener, in the same process: a separate Tomcat connector on
 * {@code dataprism.operator.port}. Nothing here makes a second application. The security chain for
 * that port is {@code ServerSecurityConfiguration}'s operator chain.
 *
 * <p>An absent or out-of-range port adds no connector and the port filter then matches nothing;
 * {@code DataPrismProperties.validate()} refuses startup for it (MISSING_OPERATOR_SECURITY), so the
 * process never serves.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
class OperatorConnectorConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> dataPrismOperatorConnector(
            DataPrismProperties properties, ServerProperties server) {
        return factory -> {
            Integer port = properties.getOperator().getPort();
            if (!validPort(port)) {
                return;
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            connector.setMaxPostSize((int) OperatorPortFilter.MAX_BODY_BYTES);
            String address = properties.getOperator().getAddress();
            if (address != null && !address.isBlank()) {
                // Validated at startup (INVALID_OPERATOR_ADDRESS), so this resolves.
                connector.setProperty("address", resolve(address.trim()));
            } else if (server.getAddress() != null) {
                connector.setProperty("address", server.getAddress().getHostAddress());
            }
            factory.addAdditionalTomcatConnectors(connector);
            // Tomcat's own error report is HTML and echoes the failure; on this port it is a code.
            factory.addContextCustomizers(context -> {
                if (context.getParent() instanceof org.apache.catalina.core.StandardHost host) {
                    OperatorErrorReportValve valve = new OperatorErrorReportValve(port);
                    host.setErrorReportValveClass(valve.getClass().getName());
                    host.getPipeline().addValve(valve);
                }
            });
        };
    }

    @Bean
    FilterRegistrationBean<OperatorPortFilter> dataPrismOperatorPortFilter(DataPrismProperties properties) {
        Integer port = properties.getOperator().getPort();
        FilterRegistrationBean<OperatorPortFilter> registration =
                new FilterRegistrationBean<>(new OperatorPortFilter(validPort(port) ? port : -1));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        return registration;
    }

    private static String resolve(String address) {
        try {
            return java.net.InetAddress.getByName(address).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException("INVALID_OPERATOR_ADDRESS");
        }
    }

    private static boolean validPort(Integer port) {
        return port != null && port >= 1 && port <= 65535;
    }
}
