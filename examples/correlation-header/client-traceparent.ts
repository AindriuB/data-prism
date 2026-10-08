// The traceparent variant: send a W3C traceparent value on every call.
//
// Server side:
//   dataprism.correlation.inbound.header=traceparent
//   dataprism.correlation.inbound.format=traceparent
import { AsyncLocalStorage } from "node:async_hooks";
import { randomBytes } from "node:crypto";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const url = new URL(process.env.DATAPRISM_MCP_URL ?? "https://data-prism.example.invalid/mcp");
const token = process.env.DATAPRISM_MCP_TOKEN ?? "";

const traceparent = new AsyncLocalStorage<string>();

// version 00, 16-byte trace id, 8-byte span id, flags 01 (sampled)
const newTraceparent = (): string =>
  `00-${randomBytes(16).toString("hex")}-${randomBytes(8).toString("hex")}-01`;

const fetchWithTraceparent: typeof fetch = (input, init) => {
  const headers = new Headers(init?.headers);
  const value = traceparent.getStore();
  if (value !== undefined) {
    headers.set("traceparent", value);
  }
  return fetch(input, { ...init, headers });
};

const transport = new StreamableHTTPClientTransport(url, {
  fetch: fetchWithTraceparent,
  requestInit: { headers: { Authorization: `Bearer ${token}` } },
});
const client = new Client({ name: "traceparent-example", version: "1.0.0" });

await traceparent.run(newTraceparent(), async () => {
  await client.connect(transport);
  await client.callTool({
    name: "get_entity_context",
    arguments: { entityType: "CUSTOMER", subjectId: "example-subject" },
  });
});
await client.close();
