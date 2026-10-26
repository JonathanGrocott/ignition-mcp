# Connect Codex or Claude Code

For **Ignition MCP 0.4.0**. First complete the [User Guide](USER_GUIDE.md): install the signed module, create an Ignition API key, and save a matching token profile. Start with Read-only and Profiles only.

## Endpoint and credentials

Copy the **saved endpoint** from the Gateway's Overview or Connection section. The default is:

```text
http://localhost:8088/data/ignition-mcp/mcp
```

Use your actual host, port, HTTPS configuration and alias. The development screenshots use port **6890**. `localhost` refers to the computer running the MCP client; remote/cloud clients need a reachable Gateway address. MCP uses Ignition's existing port.

Supply the full `token-name:secret` as either `X-Ignition-API-Token` or a Bearer token. The profile contains only the name. Gateway browser login and MCP authentication are separate. A key requiring a secure channel needs HTTPS; preserve its security requirements.

Keep real credentials in private client configuration or environment variables. Never commit them to the project, paste them into permission profiles, or include them in URLs. The module does not implement OAuth; use the HTTP header configuration below.

## Codex desktop

Codex shares MCP configuration with its CLI through `~/.codex/config.toml`. In the app's MCP settings, configure an HTTP server with the saved Gateway URL and authentication header. If using TOML, a private user-level entry can look like:

```toml
[mcp_servers.ignition-mcp]
url = "http://localhost:8088/data/ignition-mcp/mcp"
http_headers = { "X-Ignition-API-Token" = "REPLACE_WITH_TOKEN_NAME:REPLACE_WITH_SECRET" }
```

Replace the example credential locally. Merge this section into existing configuration; do not overwrite other servers or settings. Keep the file private. Do not put a literal credential in a repository's `.codex/config.toml`.

This private-header option is useful when the desktop app is already running. Exporting a variable in a terminal does **not** populate the environment of an already-running desktop process. If your app launch environment already supplies the credential, use this alternative instead of the literal header:

```toml
[mcp_servers.ignition-mcp]
url = "http://localhost:8088/data/ignition-mcp/mcp"
bearer_token_env_var = "IGNITION_API_TOKEN"
```

Use one entry for that server name. [Codex's MCP documentation](https://developers.openai.com/codex/mcp) describes URL, header and environment-based configuration.

## Codex CLI

Set `IGNITION_API_TOKEN` privately in the environment of the process that will run Codex. Then register the endpoint:

```bash
codex mcp add ignition-mcp \
  --url http://localhost:8088/data/ignition-mcp/mcp \
  --bearer-token-env-var IGNITION_API_TOKEN
codex mcp list
```

The CLI writes the server configuration; it does not make the variable available to a separate desktop process. Configuration listing proves registration, not a successful authenticated tool call.

## Claude Code

With `IGNITION_API_TOKEN` already set in the terminal environment:

```bash
claude mcp add \
  --transport http \
  --scope user \
  --header "X-Ignition-API-Token: ${IGNITION_API_TOKEN}" \
  ignition-mcp \
  http://localhost:8088/data/ignition-mcp/mcp
```

The shell expands this header when registering the server; treat the resulting user configuration as private. Use `/mcp` inside Claude Code to inspect the connection.

For a project configuration that keeps the credential outside the file, `.mcp.json` supports environment substitution:

```json
{
  "mcpServers": {
    "ignition-mcp": {
      "type": "http",
      "url": "http://localhost:8088/data/ignition-mcp/mcp",
      "headers": {"X-Ignition-API-Token": "${IGNITION_API_TOKEN}"}
    }
  }
}
```

Set that variable in Claude Code's process environment. Follow its project-server approval prompts. See [Claude Code's MCP documentation](https://code.claude.com/docs/en/mcp) for HTTP servers, scopes and variable expansion.

## Reload without interrupting other chats

**Gateway permission changes do not require restarting Codex.** Save them in the Gateway; subsequent calls are evaluated against the current policy.

For a new/changed client server entry, use the client's MCP reload, reconnect or server-refresh control when available. After reloading, check that the **active chat** can actually see the `ignition.*` tools, then make a read call. Control names and live-refresh behavior depend on the client version. A busy desktop app need not be restarted just to change a Gateway grant.

If a chat still lacks the server's tools, do not report success from a separate CLI probe as proof that chat has reloaded. Check the client connection and its active catalog. A client without live reload may need a new session when convenient; that is a client limitation, separate from the Gateway endpoint. Preserve other running chats.

Example first prompt, using your real paths:

> Use ignition-mcp to read [default]MCP/Speed, including quality and timestamp. Read the MCP/Demo Python library in MyProject. Do not make changes.

The expected tool calls are documented in the [User Guide](USER_GUIDE.md#5-make-your-first-reads). The module reads source; it does not execute the Python function.

## Direct connection check

With `IGNITION_API_TOKEN` already set, this authenticates and initializes without calling a resource tool:

```bash
curl --silent --show-error --include \
  'http://localhost:8088/data/ignition-mcp/mcp' \
  -H "X-Ignition-API-Token: ${IGNITION_API_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"connection-check","version":"1"}}}'
```

Expect HTTP 200, a JSON-RPC `result`, server identity and an `Mcp-Session-Id` response header. Initialization/catalog access does not prove resource authorization. Use the returned session ID for later calls and delete it when finished; see [protocol examples](MCP_REFERENCE.md). Avoid verbose request logging that exposes authentication headers.

For failures, use the [troubleshooting table](USER_GUIDE.md#9-troubleshoot). Check transport reachability, full token value, Ignition key requirements, profile/token-name matching, tool grants and target scopes in that order.
