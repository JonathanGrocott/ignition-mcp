# MCP HTTP examples

For **Ignition MCP 0.4.0**; most users should let [Codex or Claude Code](CLAUDE_CODEX_SETUP.md) handle transport/session details. The server advertises protocol version **2025-06-18** and a tools-only catalog. It does not expose MCP resources, prompts or arbitrary script execution.

## Routes

Paths below follow the saved mount alias; the default base is `/data/ignition-mcp`.

| Route | Purpose |
|---|---|
| `POST /mcp` | Authenticated JSON-RPC requests through Streamable HTTP |
| `GET /mcp` | Retrieve queued events in SSE format with an owned session ID |
| `DELETE /mcp` | Close the caller's session |
| `GET /sse`, `POST /message` | Optional legacy SSE fallback when enabled |
| `GET /admin/status`, `GET /admin/config` | Gateway web-session configuration/telemetry reads |
| `POST /admin/config` | Gateway web-session configuration write with its normal CSRF checks |

API-token headers authenticate MCP transport routes. Admin routes use Gateway web-session read/write permissions; an MCP API token is not a substitute. The UI route `/app/ignition-mcp` is separate from the MCP endpoint. No extra listening port is opened.

Use HTTPS and the Gateway/key's security requirements for non-loopback connections. Host/origin restrictions are additional transport checks, not substitutes for authentication or per-tool/target policy.

## Initialize and retain the session

Set `IGNITION_API_TOKEN` privately before running commands. Use a private scratch directory for the response files, which contain a session identifier:

```bash
mkdir -p build/mcp-check
chmod 700 build/mcp-check
curl --silent --show-error \
  'http://localhost:8088/data/ignition-mcp/mcp' \
  -H "X-Ignition-API-Token: ${IGNITION_API_TOKEN}" \
  -H 'Content-Type: application/json' \
  -D build/mcp-check/headers.txt \
  -o build/mcp-check/initialize.json \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"manual-check","version":"1"}}}'
```

Read `Mcp-Session-Id` from the response headers and set `MCP_SESSION_ID` to that value in your private shell. Use the same authenticated caller for subsequent requests. An expired or unknown Streamable HTTP session returns HTTP 404; initialize a fresh session without the old ID. A live session owned by another caller returns HTTP 403. See the [Streamable HTTP session contract](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#session-management). `Authorization: Bearer ${IGNITION_API_TOKEN}` is an alternative to the custom header; do not place credentials in the URL.

Authenticated initialization/catalog discovery is available independently of tool grants. An initialized session is not proof of permission to read a tag or change a resource.

## Discover tools and perform a read

Send this JSON as the next POST body with the same authentication and `Mcp-Session-Id` header:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

A complete read request:

```bash
curl --silent --show-error \
  'http://localhost:8088/data/ignition-mcp/mcp' \
  -H "X-Ignition-API-Token: ${IGNITION_API_TOKEN}" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}" \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ignition.tags.read","arguments":{"paths":["[default]MCP/Speed"]}}}'
```

The normal response contains `result.content`, `result.structuredContent` and `result.isError`. Inspect structured values, quality and timestamps. The example path must exist and be authorized; the module does not create it.

## A tag-write dry-run

The tag-value schema uses a **`writes` array of `{path, value}` objects**:

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "ignition.tags.write",
    "arguments": {
      "writes": [{"path": "[default]MCP/Speed", "value": 43}],
      "commit": false
    }
  }
}
```

`commit: false` explicitly requests a dry-run. It still needs write-tool/target authorization. To actually write, deliberately use `commit: true`, save Allow authorized writes in the Gateway, and grant that tool and tag target. An explicit commit cannot enable the operator gate.

With **Require explicit commit for legacy write tools** enabled (`defaultDryRun: true`), omitted commit defaults to dry-run. Disabling that setting permits authorized legacy calls without the field to commit; retain the default unless intentionally using that behavior. Planned project apply always requires explicit `commit: true` regardless of this setting.

For supported project resource edits, use [preview/apply/readback](USER_GUIDE.md#7-preview-apply-and-verify-a-project-change). Do not send a replacement bundle to apply; only its stored plan ID/hash and commit flag are accepted.

## Errors have two layers

HTTP status handles transport/authentication failures. A JSON-RPC response can be HTTP 200 while still describing a failure:

- `error.code`, `error.message`, `error.data`: dispatch/protocol/policy errors, including `TOOL_NOT_AUTHORIZED` or `WRITES_DISABLED` in data.
- `result.isError: true`: handler failure; inspect `structuredContent.code`/message where supplied. Older handlers may provide only error text.

Do not assume an absent exception or HTTP 200 means a write committed. Check `dryRun`, `committed`, verification and per-target results. A plan with no changed resources can verify with `committed: false` because no SDK push was needed. An uncertain/failed readback may follow a partial write; inspect state before recovery. See the [troubleshooting table](USER_GUIDE.md#9-troubleshoot).

## Close the session

```bash
curl --silent --show-error --request DELETE \
  'http://localhost:8088/data/ignition-mcp/mcp' \
  -H "X-Ignition-API-Token: ${IGNITION_API_TOKEN}" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}"
```

Avoid verbose request logs and retain session files privately only as long as needed. See the [Tool Reference](TOOL_REFERENCE.md) for all names and [local testing guide](LOCAL_GATEWAY_TESTING.md) for repeatable read-only and preview checks.
