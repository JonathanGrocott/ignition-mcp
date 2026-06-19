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
  - `ignition.projects.list`
  - `ignition.projects.resource.list`
  - `ignition.projects.resource.read`
  - `ignition.projects.resource.export`
  - `ignition.scripts.project.list`
  - `ignition.scripts.project.read`
  - `ignition.scripts.project.write`
  - `ignition.scripts.project.delete`
  - `ignition.scripts.project.import`
  - `ignition.namedqueries.list`
  - `ignition.namedqueries.read`
  - `ignition.namedqueries.execute`
  - `ignition.namedqueries.write`
  - `ignition.namedqueries.delete`
  - `ignition.namedqueries.import`
  - `ignition.historian.query`
  - `ignition.alarms.list`
  - `ignition.alarms.acknowledge`
- Safety controls:
  - request and write rate limits (per token)
  - tag/alarm/named-query allowlist checks
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
./scripts/sign-module.sh
```

Build output:

- unsigned intermediate module: `build/ignition-mcp.unsigned.modl`
- signed module: `build/ignition-mcp.modl`
- checksum metadata: `build/checksum/checksum.json`

If signing material does not exist yet, `./scripts/sign-module.sh` creates a simple self-signed certificate under `certs/`; those files are ignored by git. Keep the same generated files if you want future local builds to use the same certificate, so Ignition users only need to accept that certificate once.

Verify the module contains the signing payload:

```bash
unzip -l build/ignition-mcp.modl | grep -E 'certificates.p7b|signatures.properties'
```

## GitHub Actions

This repo includes CI/CD workflows similar to your other Ignition module projects:

- `.github/workflows/build.yml`
  - Runs on pushes/PRs to `main|master|develop`
  - Builds and tests the module
  - Signs and uploads `.modl` + checksum artifacts
- `.github/workflows/release.yml`
  - Runs on version tags `v*` (and manual dispatch)
  - Builds/tests, signs the module, creates checksum file, and publishes a GitHub Release with module artifacts

## Install On Ignition Gateway

1. Copy the built module file into your Ignition modules directory.
2. Restart the Ignition gateway service.
3. Confirm module status in Gateway Web UI (`Config > Modules`).

Example on macOS/Linux:

```bash
cp build/ignition-mcp.modl /usr/local/ignition/user-lib/modules/ignition-mcp.modl
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

List configured designer projects:

```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "tools/call",
  "params": {
    "name": "ignition.projects.list",
    "arguments": {
      "includeNamedQueryCounts": true
    }
  }
}
```

List named queries for one project:

```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "method": "tools/call",
  "params": {
    "name": "ignition.namedqueries.list",
    "arguments": {
      "project": "samplequickstart"
    }
  }
}
```

Read one named query (SQL and parameters):

```json
{
  "jsonrpc": "2.0",
  "id": 9,
  "method": "tools/call",
  "params": {
    "name": "ignition.namedqueries.read",
    "arguments": {
      "project": "samplequickstart",
      "path": "Reports/Audit Log"
    }
  }
}
```

Execute one named query (dry-run default):

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "ignition.namedqueries.execute",
    "arguments": {
      "project": "samplequickstart",
      "path": "Ignition 101/Data Entry/Tank List"
    }
  }
}
```

Create/edit a named query definition (dry-run by default, `commit=true` to push):

```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "tools/call",
  "params": {
    "name": "ignition.namedqueries.write",
    "arguments": {
      "project": "samplequickstart",
      "path": "Reports/New Audit Query",
      "type": "Query",
      "database": "Sample_SQLite_Database",
      "query": "SELECT 1",
      "parameters": []
    }
  }
}
```

List project resources:

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "tools/call",
  "params": {
    "name": "ignition.projects.resource.list",
    "arguments": {
      "project": "samplequickstart",
      "moduleId": "ignition",
      "includeData": false
    }
  }
}
```

Export an allowlisted project resource bundle:

```json
{
  "jsonrpc": "2.0",
  "id": 121,
  "method": "tools/call",
  "params": {
    "name": "ignition.projects.resource.export",
    "arguments": {
      "project": "samplequickstart",
      "moduleId": "ignition",
      "pathPrefix": "MCP",
      "maxResources": 250
    }
  }
}
```

Dry-run import project scripts or named queries from a reviewed export bundle:

```json
{
  "jsonrpc": "2.0",
  "id": 122,
  "method": "tools/call",
  "params": {
    "name": "ignition.scripts.project.import",
    "arguments": {
      "targetProject": "samplequickstart",
      "bundle": {
        "resources": [
          {
            "moduleId": "ignition",
            "resourceType": "script-app-library",
            "path": "MCP/Shared",
            "applicationScope": 7,
            "version": 1,
            "data": {
              "resource.json": {
                "encoding": "utf-8",
                "value": "{\"scripts\":{\"MCP/Shared\":\"print 'hello'\"}}"
              }
            }
          }
        ]
      }
    }
  }
}
```

Create/edit a project startup script:

```json
{
  "jsonrpc": "2.0",
  "id": 13,
  "method": "tools/call",
  "params": {
    "name": "ignition.scripts.project.write",
    "arguments": {
      "project": "samplequickstart",
      "scriptType": "startup",
      "code": "print 'startup from MCP'"
    }
  }
}
```

Create/edit tag definition (requires write permission and `commit=true`):

```json
{
  "jsonrpc": "2.0",
  "id": 10,
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

Create a UDT type with a child member:

```json
{
  "jsonrpc": "2.0",
  "id": 14,
  "method": "tools/call",
  "params": {
    "name": "ignition.tags.definition.write",
    "arguments": {
      "operation": "create",
      "path": "[default]_types_/MCP/Pump",
      "tagObjectType": "UdtType",
      "children": [
        {
          "name": "Speed",
          "tagObjectType": "AtomicTag",
          "properties": {
            "enabled": true,
            "documentation": "UDT member created by MCP"
          }
        }
      ],
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
- `allowedNamedQueryExecutePatterns`
- `allowedProjectResourceReadPatterns`
- `allowedProjectScriptWritePatterns`
- `allowedNamedQueryWritePatterns`
- `allowedReadToolPatterns`
- `allowedWriteToolPatterns`
- `authorizationProfiles`
- `historianDefaultProvider`
- `historianMaxRows`
- `namedQueryMaxRows`

`authorizationProfiles` adds reusable per-token grants on top of the global allowlists. A profile matches API token names with `tokenPatterns`; matching profiles can grant tool access and safe-core target allowlists without opening those targets globally:

```json
[
  {
    "name": "ops-mcp-writer",
    "tokenPatterns": ["ops-*"],
    "allowedReadToolPatterns": ["ignition.tags.*", "ignition.projects.*"],
    "allowedWriteToolPatterns": ["ignition.tags.write", "ignition.tags.definition.write"],
    "allowedTagReadPatterns": ["[default]Ops/*"],
    "allowedTagWritePatterns": ["[default]Ops/MCP/*", "[default]_types_/Ops/MCP/*"],
    "allowedAlarmAckSources": ["prov:ops/*"],
    "allowedNamedQueryExecutePatterns": ["OpsProject/Reports/*"],
    "allowedProjectResourceReadPatterns": ["OpsProject/ignition/*/*"],
    "allowedProjectScriptWritePatterns": ["OpsProject/library/MCP/*"],
    "allowedNamedQueryWritePatterns": ["OpsProject/MCP/*"]
  }
]
```

## Notes

- This is a gateway-only V1 implementation (no Designer/Vision scope).
- Gateway UI page is available under `Services > Ignition MCP > Configuration`.
- Admin UI includes live observability panels (tool calls, success/error ratio, write allow/deny counts, top tools, recent events).
- MCP traffic uses the existing Ignition web server port (same port as Gateway HTTP/HTTPS). A separate MCP port is not exposed in this version.
- Historian and alarm integrations are scaffolded behind the final MCP contract and safety checks; complete project-specific binding can be expanded per gateway data model/runtime APIs.

## Client Setup

- See [Claude and Codex setup guide](docs/CLAUDE_CODEX_SETUP.md).
- Next planned capabilities: [next iteration backlog](docs/NEXT_ITERATION.md).

## Smoke Test

Use the provided scripts to run MCP smoke tests:

```bash
./scripts/build_local_module.sh
./scripts/test_mcp_local.sh
./scripts/test_mcp_extended.sh
```

For a full local gateway checklist, see [Local Gateway Testing](docs/LOCAL_GATEWAY_TESTING.md).

Token source order in the script:

- `IGNITION_API_TOKEN`
- `X_IGNITION_API_TOKEN`
- `API_TOKEN`
- `TOKEN`
