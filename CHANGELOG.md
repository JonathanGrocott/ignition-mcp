# Changelog

## 0.4.0 (2026-10-25)

This release combines the native inspection and reviewed-change milestones. Validation records distinguish the development builds from final signed-artifact acceptance.

### Added

- Uploaded portable-bundle inventory and token-owned, expiring project change preview/apply/receipt tools (42 tools total).
- Typed native Python library/current named-query changes with scope/attribute preservation, explicit deletes, baseline and SDK-signature checks, post-apply readback, and no automatic retry after uncertain outcomes.
- Operator-controlled committed-write gate, disabled by default including existing configs without the field. Existing dry-runs remain available.
- Optional profiles-only authorization that ignores global grants; Gateway editor controls and draft read-only/profile templates.
- Focused workflow/policy/concurrency tests and a preview-only-by-default live workflow test script.
- Illustrated user documentation, client setup, tool/protocol references and a portable manual acceptance workflow.
- Redesigned Gateway interface with task-based sections, visual token-profile editing, explicit saved/draft write modes, browser light/dark themes, responsive styling and protection against status refreshes overwriting unsaved edits. See [Gateway UI guide](docs/GATEWAY_UI.md).
- Fifteen read-only native tools for Perspective view inspection, Alarm Notification pipeline resource inventory and JSON/native binary configuration reads, literal project search/reference candidates, UDT discovery/member inspection, OPC mappings, bounded tag snapshots, Gateway status/modules/connections/logs and composite diagnostics.
- Bounded passive native pipeline parsing, a disabled SDK-authored fixture and positive pipeline readback on Ignition 8.3.9, completing successful local coverage of all 42 tools across the qualification runs.
- Project-resource and per-child tag allowlist enforcement, bounded work/results, partial failure and truncation metadata, optional stored script detail, and source locations.
- Focused SDK-backed tests and a read-only live inspection smoke script. See `docs/INSPECTION_TOOLS.md` for contracts, validation instructions and remaining format limits.

### Migration

- Enable `writesEnabled` explicitly in Gateway configuration to restore committed MCP mutations after upgrading. `commit=true` cannot enable the server setting.
- Review [change workflow contracts](docs/CHANGE_WORKFLOWS.md) for resource-type limits, plan expiry, partial/unknown write outcomes and policy semantics.

### Fixed

- Native tag/UDT deletion now passes a mutable path list to Ignition, whose runtime sorts it during deletion.
- Expired Streamable HTTP sessions return HTTP 404 so clients can reinitialize after module/Gateway restarts; another caller's live session remains forbidden.

- Existing script/named-query reads and inventories now honor project-resource grants; tag browse/recursive definition reads filter each child and nested definition writes check child scope and parent containment.
- Native Python library source reads are available as `scriptType: python`; native library mutations use the reviewed workflow.
- Typed bundle reconstruction preserves Ignition scope codes, noncalculated attributes and resource flags, including named-query database/parameter metadata.
- Incremental module staging could retain stale project JARs. Packaging now tracks current JAR inputs, clears staging directories and verifies embedded JAR contents before producing checksum metadata.
- MCP initialization now reports the module build version instead of a hardcoded snapshot version.

### Validation

- Final signed 0.4.0 artifact: 96 Java tests and 9 UI-state tests passed. All 42 tools passed local acceptance on Ignition 8.3.9, with 36 result assertions, 15 strict inspection checks and 14 reviewed-change workflow assertions. Packaged/runtime versions, installed checksum and all 6 payload signatures were verified. Publication remains separate.
- Isolated Ignition 8.3.9: 14 committed-workflow, 16 profiles-only policy and 3 missing-settings migration assertions passed; 14 inspection tools passed regression checks. See [validation evidence and limits](docs/VALIDATION.md).

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
