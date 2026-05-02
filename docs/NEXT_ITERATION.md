# Next Iteration Backlog

This backlog tracks work remaining after the safe-core expansion implementation. Local unit tests and module packaging pass; live gateway smoke validation should be rerun against an allowlisted test gateway before release.

## Recently Implemented

- Added read-only project resource inventory/read tools:
  - `ignition.projects.resource.list`
  - `ignition.projects.resource.read`

- Added typed project script tools:
  - `ignition.scripts.project.list`
  - `ignition.scripts.project.read`
  - `ignition.scripts.project.write`
  - `ignition.scripts.project.delete`

- Added typed named query mutation tools:
  - `ignition.namedqueries.write`
  - `ignition.namedqueries.delete`

- Expanded tag definition writes:
  - batch create/edit/upsert/delete
  - child definitions for UDT trees
  - fully-qualified child paths in recursive reads where parent path context is available.

- Added additional allowlists and tool-level authorization patterns in module config.

- Added structured policy/error payloads for tool fallback errors, tool authorization denials, rate limits, resource conflicts, mutable-project checks, push failures, and safe-core allowlist blocks.

- Expanded the admin UI observability panel with recent events, write allow/deny counters, top tools, and editable safe-core allowlists.

- Added named per-token authorization profiles:
  - profiles match API token names with glob patterns
  - matching profiles can grant tool access, tag/historian access, alarm acknowledgement sources, project resource reads, project script writes, and named query execute/write targets
  - admin status now round-trips the safe-core allowlists and profile JSON.

- Added controlled read-only project resource export bundles:
  - `ignition.projects.resource.export`
  - exports only resources allowed by the project-resource read policy
  - includes data payloads, filters, count, truncation metadata, and a versioned bundle format.

- Added controlled typed import dry-run/apply tools:
  - `ignition.scripts.project.import`
  - `ignition.namedqueries.import`
  - imports only matching typed resources from reviewed project-resource bundles
  - dry-run by default, `commit=true` required to push through ProjectManager.

- Added optional live commit smoke checks for project scripts and named queries.

- Added admin UI copy buttons for endpoint/Codex/Claude snippets plus a safe-core authorization profile preset.

## Priority 1 (Remaining Capability)

- Add additional typed import coverage if needed:
  - perspective views/windows/reports only if explicitly brought into safe-core scope
  - no generic project-resource writes.

## Priority 2 (Quality / Hardening)

- Add integration tests for:
  - SSE `/sse` + `/message` full round-trip (not just handshake)
  - allowlist and rate-limit rejection codes
  - session ownership/hijack checks across transports
  - named-query execute commit behavior with dataset/scalar responses.

- Add admin config POST round-trip tests for authorization profile JSON.

## Priority 3 (Operator UX)

- Gateway UI improvements:
  - provide one-click "copy endpoint" and client snippets (Claude/Codex).

- Add an optional read-only admin API-token route for status:
  - currently `/admin/*` is session-authenticated (Gateway Web UI session).
