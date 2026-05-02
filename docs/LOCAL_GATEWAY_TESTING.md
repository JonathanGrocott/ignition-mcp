# Local Gateway Testing

Use this checklist to build the module, install it on a local Ignition Gateway, and run the safe-core smoke tests.

## 1) Build The Module

```bash
./scripts/build_local_module.sh
```

The unsigned module artifact is written to:

```text
build/ignition-mcp.unsigned.modl
```

## 2) Install In Ignition

In the Gateway Web UI:

```text
Config > System > Modules > Install or Upgrade a Module
```

Install `build/ignition-mcp.unsigned.modl`, then confirm the module is enabled.

## 3) Configure MCP

Open:

```text
Services > Ignition MCP > Configuration
```

Recommended local test settings:

- `enabled = true`
- `streamableEnabled = true`
- `sseFallbackEnabled = true`
- `defaultDryRun = true`
- `allowedTagReadPatterns` includes your read test tags, for example `*` for first local validation
- `allowedTagWritePatterns` includes the write test folder and UDT type test folder:
  - `[default]MCP/*`
  - `[default]_types_/MCP/*`
- `allowedProjectResourceReadPatterns = *`
- `allowedProjectScriptWritePatterns = *` for local dry-run validation
- `allowedNamedQueryWritePatterns = *` for local dry-run validation
- `allowedReadToolPatterns = *`
- `allowedWriteToolPatterns = *`

For tighter local testing, use `authorizationProfiles` to grant access to only your test API token name.

## 4) Create `.env`

```bash
cp .env.example .env
```

Edit `.env`:

```bash
IGNITION_HOST=http://localhost:8088
IGNITION_MCP_ALIAS=ignition-mcp
IGNITION_API_TOKEN=<your Ignition API token>
```

If your gateway does not have `[Sample_Tags]RampUDT/Ramp0` and `[Sample_Tags]RampUDT/Ramp1`, set:

```bash
MCP_TEST_TAG_READ_PATH_1=[default]Some/ReadableTag
MCP_TEST_TAG_READ_PATH_2=[default]Some/OtherReadableTag
MCP_TEST_DEFINITION_READ_PATH=[default]Some
```

The write smoke tests default to `[default]MCP/*`. Override these if you use a different safe test folder:

```bash
MCP_TEST_DEFINITION_WRITE_PATH=[default]MCP/NewFromMcp
MCP_TEST_UDT_TYPE_PATH=[default]_types_/MCP/McpSmokeType
MCP_TEST_DELETE_PATH=[default]MCP/McpSmokeDeleteCandidate
```

For a read-only first pass before you configure tag write allowlists:

```bash
MCP_REQUIRE_TAG_WRITES=false
```

## 5) Run Smoke Tests

Quick local tool and tag-definition check:

```bash
./scripts/test_mcp_local.sh
```

Fuller safe-core check:

```bash
./scripts/test_mcp_extended.sh
```

The extended test validates:

- no-auth rejection
- initialize/session handling
- full tool list
- tag browse/read
- tag definition read/write dry-run/commit
- UDT definition dry-run
- project resource list/read/export
- project script list/write/delete dry-runs
- named query list/read/execute dry-runs and write/delete dry-runs
- optional project script create/read/delete commits with `MCP_COMMIT_PROJECT_SCRIPT_TESTS=true`
- optional named query create/read/delete commits with `MCP_COMMIT_NAMED_QUERY_TESTS=true`
- historian and alarm list surfaces
- blocked write behavior outside the allowlist

Optional commit checks create and then delete test project resources. Enable only on a disposable test project/provider:

```bash
MCP_COMMIT_PROJECT_SCRIPT_TESTS=true MCP_COMMIT_NAMED_QUERY_TESTS=true ./scripts/test_mcp_extended.sh
```

## 6) Configure Clients

After the smoke tests pass, connect Claude or Codex using:

```text
docs/CLAUDE_CODEX_SETUP.md
```
