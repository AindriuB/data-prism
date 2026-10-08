"""Copy the application's own correlation id into X-Correlation-ID on every call.

Streamable HTTP sends one HTTP request per JSON-RPC message, so the header must
be set per request, not once at connection time. An httpx2 request hook runs for
every request and reads the id from a contextvar the application sets.

Server side: dataprism.correlation.inbound.header=X-Correlation-ID
"""
import asyncio
import contextvars
import os
import uuid

import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

URL = os.environ.get("DATAPRISM_MCP_URL", "https://data-prism.example.invalid/mcp")
TOKEN = os.environ["DATAPRISM_MCP_TOKEN"]  # supplied at run time, never stored here

# The application's own context: set it wherever a unit of work begins.
current_correlation_id: contextvars.ContextVar[str] = contextvars.ContextVar("correlation_id")


async def add_correlation_header(request: httpx2.Request) -> None:
    correlation_id = current_correlation_id.get(None)
    if correlation_id is not None:
        request.headers["X-Correlation-ID"] = correlation_id


async def main() -> None:
    current_correlation_id.set(str(uuid.uuid4()))
    http_client = httpx2.AsyncClient(
        headers={"Authorization": f"Bearer {TOKEN}"},
        event_hooks={"request": [add_correlation_header]},
    )
    async with http_client:
        async with streamable_http_client(URL, http_client=http_client) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                await session.call_tool(
                    "get_entity_context",
                    {"entityType": "CUSTOMER", "subjectId": "example-subject"},
                )


if __name__ == "__main__":
    asyncio.run(main())
