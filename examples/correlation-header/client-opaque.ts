// Copy the application's own correlation id into X-Correlation-ID on every call.
// Streamable HTTP sends one HTTP request per JSON-RPC message, so the header is
// set in a custom fetch, which runs for every request.
//
// Server side: dataprism.correlation.inbound.header=X-Correlation-ID
import { AsyncLocalStorage } from "node:async_hooks";
import { randomUUID } from "node:crypto";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const url = new URL(process.env.DATAPRISM_MCP_URL ?? "https://data-prism.example.invalid/mcp");
const token = process.env.DATAPRISM_MCP_TOKEN ?? ""; // supplied at run time, never stored here

// The application's own context: run each unit of work inside correlation.run(id, ...).
const correlation = new AsyncLocalStorage<string>();

const fetchWithCorrelation: typeof fetch = (input, init) => {
  const headers = new Headers(init?.headers);
  const id = correlation.getStore();
  if (id !== undefined) {
    headers.set("X-Correlation-ID", id);
  }
  return fetch(input, { ...init, headers });
};

const transport = new StreamableHTTPClientTransport(url, {
  fetch: fetchWithCorrelation,
  requestInit: { headers: { Authorization: `Bearer ${token}` } },
});
const client = new Client({ name: "correlation-example", version: "1.0.0" });

await correlation.run(randomUUID(), async () => {
  await client.connect(transport);
  await client.callTool({
    name: "get_entity_context",
    arguments: { entityType: "CUSTOMER", subjectId: "example-subject" },
  });
});
await client.close();
