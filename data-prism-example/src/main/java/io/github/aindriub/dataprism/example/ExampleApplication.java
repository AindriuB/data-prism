package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.modelcontextprotocol.server.McpSyncServer;

/**
 * Runs the MCP server on stdio.
 *
 * <p>Under stdio transport, standard output <em>is</em> the protocol: one stray
 * {@code System.out.println}, banner or console appender corrupts the JSON-RPC
 * stream, and the failure looks like a client parse error rather than a logging
 * mistake. Everything diagnostic goes to stderr, which is what
 * {@code simplelogger.properties} pins.
 */
public final class ExampleApplication {

    private ExampleApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        DataPrismAssembly assembly = DataPrismAssembly.standard();
        McpSyncServer server = new DataPrismMcpServer(
                assembly.orchestrator(), assembly::privacyContext).start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        System.err.println("data-prism listening on stdio; tool: get_entity_context");

        Thread.currentThread().join();
    }
}
