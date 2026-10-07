"""The traceparent variant: send a W3C traceparent value on every call.

Server side:
  dataprism.correlation.inbound.header=traceparent
  dataprism.correlation.inbound.format=traceparent
The audit event records the trace id (the second field of the value).
"""
import asyncio
import contextvars
import os
import secrets

import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

URL = os.environ.get("DATAPRISM_MCP_URL", "https://data-prism.example.invalid/mcp")
TOKEN = os.environ["DATAPRISM_MCP_TOKEN"]

current_traceparent: contextvars.ContextVar[str] = contextvars.ContextVar("traceparent")


def new_traceparent() -> str:
    # version 00, 16-byte trace id, 8-byte span id, flags 01 (sampled)
    return f"00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01"


async def add_traceparent(request: httpx2.Request) -> None:
    value = current_traceparent.get(None)
    if value is not None:
        request.headers["traceparent"] = value


async def main() -> None:
    current_traceparent.set(new_traceparent())
    http_client = httpx2.AsyncClient(
        headers={"Authorization": f"Bearer {TOKEN}"},
        event_hooks={"request": [add_traceparent]},
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
