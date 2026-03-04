# ignition-mcp

Gateway-scoped Ignition 8.3 module that exposes a tools-only MCP server over Ignition data routes.

## Status

Implemented V1 surface:

- Transport routes: `POST/GET/DELETE /mcp`, `GET /sse`, `POST /message`
- Auth: Ignition API token validation on every route
- Tool contracts:
  - `ignition.tags.browse`
  - `ignition.tags.read`
  - `ignition.tags.write`
  - `ignition.tags.definition.read`
  - `ignition.tags.definition.write`
  - `ignition.historian.query`
  - `ignition.alarms.list`
  - `ignition.alarms.acknowledge`
- Safety controls:
  - request and write rate limits (per token)
  - tag/alarm allowlist checks
  - dry-run default for mutating tools (`commit=true` required to commit)
- Persisted gateway config resource (`mcp-config`) via `NamedResourceHandler`

## Build

Prereqs:

- Java 17
- Access to Ignition 8.3 Maven repos

Commands:

```bash
./gradlew test
./gradlew clean zipModule checksumModl
```

Build output:

- unsigned module: `build/ignition-mcp.modl`
- checksum file: `build/ignition-mcp.modl.sha256`

## Install On Ignition Gateway

1. Copy the built module file into your Ignition modules directory.
2. Restart the Ignition gateway service.
3. Confirm module status in Gateway Web UI (`Config > Modules`).

Example on macOS/Linux:

```bash
cp build/ignition-mcp.modl /usr/local/ignition/user-lib/modules/ignition-mcp.unsigned.modl
sudo systemctl restart ignition
```

## Module Mount Path

Module alias defaults to:

- `/data/ignition-mcp/*`

Routes:

- `POST /data/ignition-mcp/mcp`
- `GET /data/ignition-mcp/mcp`
- `DELETE /data/ignition-mcp/mcp`
- `GET /data/ignition-mcp/sse`
- `POST /data/ignition-mcp/message`

Gateway admin routes (Gateway Web UI session required):

- `GET /data/ignition-mcp/admin/status`
- `GET /data/ignition-mcp/admin/config`
- `POST /data/ignition-mcp/admin/config`

## Auth

MCP transport routes require:

- `X-Ignition-API-Token: <token>` header, or
- `Authorization: Bearer <token>` header

Admin routes use authenticated Ignition Gateway Web UI sessions and CSRF protection.

In Ignition 8.3, API token capabilities are typically ACCESS-scoped. This module still distinguishes tool intent (`READ` vs `WRITE`) and enforces write safety with:

- allowlist checks (`allowedTagWritePatterns`, `allowedAlarmAckSources`)
- dry-run default (`defaultDryRun`)
- per-token write rate limiting (`maxWriteOpsPerMinutePerToken`)

## Session Header

For streamable and SSE fallback session continuity:

- `Mcp-Session-Id`

You can provide it as a request header or query parameter `sessionId`.

## Example MCP Calls

Initialize:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

List tools:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

Call a mutating tool in dry-run mode:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "ignition.tags.write",
    "arguments": {
      "writes": [
        { "path": "[default]MCP/Setpoint", "value": 42 }
      ]
    }
  }
}
```

Commit mutation:

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "ignition.tags.write",
    "arguments": {
      "commit": true,
      "writes": [
        { "path": "[default]MCP/Setpoint", "value": 42 }
      ]
    }
  }
}
```

Read tag definitions (Designer-visible config):

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "ignition.tags.definition.read",
    "arguments": {
      "paths": ["[default]MCP/Setpoint"],
      "recursive": false
    }
  }
}
```

Create/edit tag definition (requires write permission and `commit=true`):

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "ignition.tags.definition.write",
    "arguments": {
      "operation": "upsert",
      "path": "[default]MCP/NewTag",
      "tagObjectType": "AtomicTag",
      "properties": {
        "enabled": true,
        "documentation": "Created by MCP"
      },
      "commit": true
    }
  }
}
```

## Config Resource Fields

Single profile fields are implemented in `McpServerConfigResource`:

- `enabled`
- `mountAlias`
- `allowedOrigins`
- `allowedHosts`
- `streamableEnabled`
- `sseFallbackEnabled`
- `maxConcurrentSessions`
- `maxRequestsPerMinutePerToken`
- `maxWriteOpsPerMinutePerToken`
- `defaultDryRun`
- `maxBatchWriteSize`
- `allowedTagReadPatterns`
- `allowedTagWritePatterns`
- `allowedAlarmAckSources`
- `historianDefaultProvider`
- `historianMaxRows`

## Notes

- This is a gateway-only V1 implementation (no Designer/Vision scope).
- Gateway UI page is available under `Services > Ignition MCP > Configuration`.
- MCP traffic uses the existing Ignition web server port (same port as Gateway HTTP/HTTPS). A separate MCP port is not exposed in this version.
- Historian and alarm integrations are scaffolded behind the final MCP contract and safety checks; complete project-specific binding can be expanded per gateway data model/runtime APIs.

## Client Setup

- See [Claude and Codex setup guide](docs/CLAUDE_CODEX_SETUP.md).

## Smoke Test

Use the provided script to run initialize/list-tools/tag-definition read+write tests:

```bash
./scripts/test_mcp_local.sh
```

Token source order in the script:

- `IGNITION_API_TOKEN`
- `X_IGNITION_API_TOKEN`
- `API_TOKEN`
- `TOKEN`
