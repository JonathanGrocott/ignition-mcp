# Ignition MCP Client Setup (Claude + Codex)

This guide connects MCP-capable clients to the Ignition gateway-hosted MCP server in this module.

## 1) Prerequisites

- Ignition module is installed and enabled.
- You can reach the MCP route:
  - `http://<gateway-host>:<gateway-port>/data/<mount-alias>/mcp`
  - Default: `http://localhost:8088/data/ignition-mcp/mcp`
- You created an Ignition API token.
- Optional but recommended: open Gateway UI page at `Services > Ignition MCP > Configuration` and verify:
  - `enabled = true`
  - `streamableEnabled = true`
  - `allowedHosts` / `allowedOrigins` include your client host/origin if set.
  - `defaultDryRun` and write allowlists match your intended write behavior.

Notes:

- MCP is served on the same HTTP/HTTPS port as the Ignition gateway (no separate MCP port in this version).
- Admin routes (`/data/<alias>/admin/*`) are for Gateway Web UI sessions, not API tokens.

Set your token in an environment variable:

```bash
export IGNITION_MCP_TOKEN="<your_token_here>"
```

## 2) Claude Code

`claude mcp add` supports HTTP transport and custom headers.

```bash
claude mcp add \
  --transport http \
  --scope user \
  --header "X-Ignition-API-Token: ${IGNITION_MCP_TOKEN}" \
  ignition-mcp \
  http://localhost:8088/data/ignition-mcp/mcp
```

Useful commands:

```bash
claude mcp list
claude mcp get ignition-mcp
```

If you changed `mountAlias`, update the URL path accordingly.

## 3) Codex

Codex supports streamable HTTP MCP servers through `codex mcp add --url`.
Use bearer auth:

```bash
codex mcp add ignition-mcp \
  --url http://localhost:8088/data/ignition-mcp/mcp \
  --bearer-token-env-var IGNITION_MCP_TOKEN
```

Useful commands:

```bash
codex mcp list
codex mcp get ignition-mcp
```

Notes:

- This module accepts both:
  - `X-Ignition-API-Token: <token>`
  - `Authorization: Bearer <token>`
- Codex bearer token mode maps directly to the second option.

## 4) Quick Connectivity Test

You can validate the endpoint before configuring a client:

```bash
curl -X POST "http://localhost:8088/data/ignition-mcp/mcp" \
  -H "X-Ignition-API-Token: ${IGNITION_MCP_TOKEN}" \
  -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

Expected: HTTP `200` with `jsonrpc` initialize result.

## 5) Troubleshooting

- `401 Unauthorized`:
  - token is invalid or missing
  - header format incorrect
- `403 Forbidden`:
  - request blocked by host/origin allowlist settings
- `503 Service Unavailable`:
  - MCP module disabled in config
- Tool calls blocked:
  - check allowlists (`allowedTagWritePatterns`, `allowedAlarmAckSources`, `allowedNamedQueryExecutePatterns`)
  - check rate limits and `defaultDryRun` behavior
