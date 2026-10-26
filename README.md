# Ignition MCP

A native Ignition 8.3 Gateway module that lets MCP clients inspect Gateway data and make explicitly authorized changes. It runs inside Ignition and uses the Gateway's existing HTTP/HTTPS port.

**Start with the [illustrated User Guide](docs/USER_GUIDE.md).** It covers installation, API keys, read-only access, client connection, writes, change previews and troubleshooting.

![Ignition MCP Overview in the Gateway](docs/images/gateway/overview.jpg)

## What it can do

Ignition MCP **0.4.0** exposes **42 tools**:

- Browse/read tags, inspect definitions and UDTs, search OPC mappings and collect bounded snapshots.
- Inspect Perspective views, stored bindings/events, project resources and literal source references.
- Read Gateway status, installed modules, connection status and bounded logs.
- Inspect named queries, query tag history and list alarms, subject to permissions.
- Inspect portable JSON bundles and preview/apply supported Python-library and named-query resource changes with conflict checks and readback verification.
- Perform scoped legacy tag, definition, script, named-query and alarm operations where supported.

See the complete [Tool Reference](docs/TOOL_REFERENCE.md) for names, access classes and limitations. There is no arbitrary Gateway script execution tool. This is a tools-only MCP server; it does not expose MCP resources/prompts. The Gateway UI configures access and displays activity; change previews are returned to the MCP client.

## Quick start

1. Install the **signed** `ignition-mcp.modl` through **Platform → System → Modules** on an Ignition 8.3 development Gateway.
2. Create an API key under **Platform → Security → API Keys**.
3. Open **Services → Ignition MCP → Configuration**. Under **Access & writes**, start with **Read-only**, choose **Profiles only**, and add a reader profile matching the token name and intended tag/project paths. Click **Save changes**.
4. Copy the endpoint from **Overview** or **Connection** and configure your [Codex or Claude Code client](docs/CLAUDE_CODEX_SETUP.md).
5. Make a scoped read call. For writes, follow the [write-access and preview workflow](docs/USER_GUIDE.md#6-allow-writes).

The default endpoint is `http://localhost:8088/data/ignition-mcp/mcp`; use your actual Gateway host, port, HTTPS configuration and saved alias.

**Upgrade behavior:** `writesEnabled` defaults to **false**, including older persisted configurations missing that field. An operator must explicitly allow writes before a committing MCP call can succeed. Global and profile grants combine in the compatibility-default `additive` mode; `profiles-only` ignores all global grants. See [migration and change semantics](docs/CHANGE_WORKFLOWS.md).

## Documentation

| Guide | Audience / purpose |
|---|---|
| [User Guide](docs/USER_GUIDE.md) | Illustrated installation, first read, scoped permissions, writes and troubleshooting |
| [Gateway UI](docs/GATEWAY_UI.md) | Section-by-section UI, visual profiles, themes, drafts and save behavior |
| [Client Setup](docs/CLAUDE_CODEX_SETUP.md) | Codex desktop/CLI and Claude Code configuration, authentication and connection checks |
| [Tool Reference](docs/TOOL_REFERENCE.md) | All 42 tools, required grant families and supported write surfaces |
| [Change Workflows](docs/CHANGE_WORKFLOWS.md) | Bundle format, preview/apply contracts, expiry, conflicts and uncertain outcomes |
| [Inspection Tools](docs/INSPECTION_TOOLS.md) | Detailed inspection arguments, work budgets, partial results and unsupported formats |
| [MCP Protocol Examples](docs/MCP_REFERENCE.md) | HTTP routes, session headers and JSON-RPC examples |
| [Local Gateway Testing](docs/LOCAL_GATEWAY_TESTING.md) | Developer setup and repeatable tests; distinguish read-only from committing runners |
| [Manual Acceptance Workflow](docs/DEVELOPMENT_TEST_WORKFLOW.md) | Portable synthetic fixture setup and manual Designer acceptance steps |
| [Validation evidence](docs/VALIDATION.md) | Automated/live results and qualification limits for the release features |

## Build from source

These docs target the **0.4.0 release**. Before packaging it, set `moduleVersion` in `build.gradle.kts` to `0.4.0`; the release tag must match (`v0.4.0`). Distribute the signed release artifact, not a development snapshot.

End users installing a built module do not need the build toolchain. Developers need Java 17 and access to the Ignition Maven repositories:

```bash
./gradlew test zipModule checksumModl
node --test gateway/src/test/js/mcp-admin.test.cjs
./scripts/sign-module.sh
```

Outputs:

- Signed installable module: `build/ignition-mcp.modl`
- Unsigned intermediate: `build/ignition-mcp.unsigned.modl`
- Packaging/checksum metadata: `build/checksum/checksum.json`

`checksumModl` verifies that the packaged common/Gateway JARs match the current build outputs. Signing uses the existing local signing material; the helper creates a self-signed development certificate when none is present. Keep private signing material out of source control. See [certificate guidance](certs/README.md).

The GitHub build/release workflows build, test, sign and package artifacts. Existing local validation covers the development builds used to implement these features; final release-artifact validation and publication are separate steps. See the [validation summary](docs/VALIDATION.md) for the exact builds tested.

## Supported and unverified boundaries

The code compiles against SDK 8.3.3; selected native integrations and the UI were exercised on Gateway 8.3.9. All 42 tools passed local fixture acceptance against the final signed 0.4.0 artifact, including native binary pipeline configuration readback. These results do not qualify every Ignition patch, custom pipeline body codec, production workload or browser. Reference search is literal rather than a complete dependency resolver, and source readback verifies saved content rather than Python execution or notification delivery. See the validation summary and [changelog](CHANGELOG.md).
