# Changelog

## 0.2.0-SNAPSHOT

Safe-core MCP expansion for Ignition 8.3.x.

### Added

- Read-only project resource inventory, read, and reviewed bundle export tools.
- Typed project script list/read/write/delete/import tools.
- Typed named query write/delete/import tools alongside existing list/read/execute.
- Batch tag definition create/edit/upsert/delete with child definitions for UDT trees.
- Token-scoped authorization profiles and expanded allowlists for tools, tags, project resources, scripts, named queries, alarms, and historian reads.
- Structured error payloads for policy denials, validation failures, missing resources, SDK unavailability, dry-run requirements, conflicts, and push failures.
- Gateway admin UI observability, endpoint/client copy buttons, safe-core profile preset, and editable safe-core policy fields.
- Local build helper, `.env.example`, local gateway testing guide, and extended smoke coverage.

### Validation

- `./gradlew clean test`
- `./gradlew zipModule checksumModl`
- Live local gateway smoke:
  - `./scripts/test_mcp_local.sh`: 21 passed, 0 failed
  - `./scripts/test_mcp_extended.sh`: 109 passed, 0 failed
  - `MCP_COMMIT_PROJECT_SCRIPT_TESTS=true MCP_COMMIT_NAMED_QUERY_TESTS=true ./scripts/test_mcp_extended.sh`: 119 passed, 0 failed
