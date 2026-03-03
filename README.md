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
./gradlew build
```

## Module Mount Path

Module alias defaults to:

- `/main/data/ignition-mcp/*`

Routes:

- `POST /main/data/ignition-mcp/mcp`
- `GET /main/data/ignition-mcp/mcp`
- `DELETE /main/data/ignition-mcp/mcp`
- `GET /main/data/ignition-mcp/sse`
- `POST /main/data/ignition-mcp/message`

## Auth

All routes require:

- Header: `X-Ignition-API-Token: <token>`

Route-level guard is token `ACCESS`. Tool calls are enforced per tool requirement (`READ` or `WRITE`) in the MCP dispatcher.

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
- Historian and alarm integrations are scaffolded behind the final MCP contract and safety checks; complete project-specific binding can be expanded per gateway data model/runtime APIs.
