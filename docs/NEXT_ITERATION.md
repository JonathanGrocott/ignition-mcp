# Next Iteration Backlog

This backlog is based on live gateway testing completed on **March 3, 2026** (`scripts/test_mcp_extended.sh`: 56 passed, 0 failed).

## Priority 1 (Missing Capability)

- Add project resource inventory tool:
  - `ignition.projects.resource.list`
  - Goal: list resources by type/path for one project or all projects.

- Add explicit module-level write authorization model:
  - per-token or per-client read/write assignment in module config UI
  - because Ignition API token permissions are commonly ACCESS-scoped in 8.3.

- Add project-resource write tools:
  - create/update/delete named queries in project resources
  - optional import/export support for project resources in a controlled allowlist scope.

## Priority 2 (Quality / Hardening)

- Normalize all tag definition paths to fully-qualified format in `ignition.tags.definition.read`.
  - Current behavior can return child paths like `Ramp0` instead of `[provider]Parent/Ramp0`.

- Add integration tests for:
  - SSE `/sse` + `/message` full round-trip (not just handshake)
  - allowlist and rate-limit rejection codes
  - session ownership/hijack checks across transports
  - admin config update/readback behavior.
  - named-query execute commit behavior with dataset/scalar responses.

- Add structured error codes for policy denials:
  - allowlist blocked
  - rate-limited
  - dry-run required
  - disabled transport.

## Priority 3 (Operator UX)

- Gateway UI improvements:
  - show recent MCP audit entries
  - expose rolling request/write counters
  - provide one-click "copy endpoint" and client snippets (Claude/Codex).

- Add an optional read-only admin API-token route for status:
  - currently `/admin/*` is session-authenticated (Gateway Web UI session).
