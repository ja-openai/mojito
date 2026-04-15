# Webapp MCP Scaffold

Purpose

- Add a remote MCP surface inside `webapp`.
- Keep the transport thin and reusable by layering it over a registry of typed tool handlers.
- Start with a sync-only transport that fits Mojito's current deployment constraints.

What this scaffold includes

- A generic `service.mcp` layer:
  - tool descriptors
  - typed tool handlers
  - a registry
  - a small server facade
- A remote MCP transport at `/api/mcp` that supports:
  - `initialize`
  - `tools/list`
  - `tools/call`
  - `notifications/initialized`
- The transport is Streamable-HTTP compatible in sync mode:
  - `POST /api/mcp` returns `application/json`
  - `GET /api/mcp` returns `405 Method Not Allowed` because streaming is not implemented yet
- The first bad-translation read-only tool can build on top of it by:
  - looking up translation candidates from `stringId`
  - resolving locale mismatches from the observed file/log locale
  - keeping repository optional so callers can start from the string alone

Why this shape

- Mojito already has strong Spring service and admin REST patterns.
- The repo did not have an MCP runtime or JSON-RPC transport to extend.
- This scaffold keeps protocol handling small while making tool implementations easy to add incrementally.

Current limitations

- Sync request/response only.
- No SSE streaming.
- No MCP session lifecycle.
- No resumable event or subscription state.

Next build steps

1. Add the first useful tool modules on top of the scaffold.
2. Keep tools read-only by default unless a clear mutating boundary is needed.
3. Only add streaming/session support if a real client requirement justifies the extra state.
