# Tool reference

The **Ignition MCP 0.4.0** catalog contains **42 tools**. This table was checked against the pre-release module's `tools/list` response on Ignition 8.3.9; the [validation record](VALIDATION.md) identifies the tested build. Older releases may have fewer tools. Ask the connected server for its current schemas rather than assuming a client cached the latest catalog.

**Read** tools need a matching **Read tools** grant. **Write** tools need a matching **Write tools** grant even for a dry-run, plus the relevant target grants. Committing a Write tool also requires the saved operator gate. The module's read/write classification is its own policy; an Ignition API key authenticates the caller and does not bypass it.

Required schema fields below are a starting point: handlers additionally validate paths, nonempty lists and operation-specific values. Some older tools advertise a shared schema; not every property applies to every operation. For copyable calls, see the [User Guide](USER_GUIDE.md) and [protocol examples](MCP_REFERENCE.md).

## Tags and UDTs

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.tags.browse` | Read | None in schema | Browse Ignition tag providers and path roots |
| `ignition.tags.read` | Read | None in schema | Read one or more tag values |
| `ignition.tags.write` | Write | None in schema | Write one or more tag values (allowlist + rate limited) |
| `ignition.tags.definition.read` | Read | `paths` | Read tag configuration definitions for Designer-visible tags |
| `ignition.tags.definition.write` | Write | `path` | Create, edit, upsert or delete tag/UDT definitions, including nested members (allowlist + dry-run + commit) |
| `ignition.udts.list` | Read | None in schema | Discover UDT definitions below a provider's _types_ folder within tag, depth, browse and time budgets |
| `ignition.udts.read` | Read | `path` | Inspect a UDT definition and authorized members, including parameters, inherited and local properties |
| `ignition.tags.opc.search` | Read | `path` | Search authorized tag configurations for OPC server/item addresses; returns mappings, not PLC logic |
| `ignition.tags.snapshot` | Read | `path` | Capture authorized tags and optional live values/quality with explicit tag, depth, browse and time budgets; not an atomic snapshot |

UDT writes use **`ignition.tags.definition.write`**. The dedicated `ignition.udts.list/read` tools provide inspection. Create a type with `tagObjectType: "UdtType"` under a provider's `_types_` tree, such as `[default]_types_/MCP/Motor`; use `operation: "edit"` for an existing definition. Properties, bindings and nested `children` are handled by the tag-definition API. Authorize the definition path and each child write path. Commits require the saved Gateway write gate, write-tool/target grants and the applicable commit flag. These calls use the tag-definition dry-run/commit flow; project change-plan IDs/hashes do not apply. A dry-run does not prove that Ignition will accept the committed UDT configuration. Accepted property values must be compatible with SDK coercion; complex alarm configuration objects are currently provisioned through Ignition's tag configuration workflow. The generic `properties` argument does not decode raw alarm-definition JSON arrays.

## Perspective and alarm pipelines

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.perspective.views.list` | Read | None in schema | List authorized Perspective views with resource identities and inheritance provenance |
| `ignition.perspective.views.read` | Read | `project`, `path` | Inspect a bounded Perspective component tree, parameters, binding configs/transforms and event actions; scripts are optional |
| `ignition.alarms.pipelines.list` | Read | None in schema | List authorized Alarm Notification pipeline project resources (not runtime alarm events) |
| `ignition.alarms.pipelines.read` | Read | `project`, `path` | Read stored JSON or native binary alarm pipeline configuration without executing scripts or notifications |

## Projects and source search

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.projects.list` | Read | None in schema | List configured designer projects |
| `ignition.projects.resource.list` | Read | None in schema | List project resources by project/module/type/path |
| `ignition.projects.resource.read` | Read | `project`, `resourceType`, `path` | Read one project resource and optionally include data files |
| `ignition.projects.resource.export` | Read | `project` | Export an allowlisted project resource bundle for review or backup |
| `ignition.projects.search` | Read | `query` | Search authorized project resource text and JSON values with bounded work and source locations (literal search, no regex) |
| `ignition.projects.references` | Read | `target` | Find literal reference candidates with source locations; cannot resolve dynamic references or prove a complete dependency graph |

## Project scripts

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.scripts.project.list` | Read | None in schema | List project script resources |
| `ignition.scripts.project.read` | Read | `project`, `scriptType` | Read one project script resource |
| `ignition.scripts.project.write` | Write | `project`, `scriptType`, `code` | Create or edit one project script resource |
| `ignition.scripts.project.delete` | Write | `project`, `scriptType` | Delete one project script resource |
| `ignition.scripts.project.import` | Write | None in schema | Import project script resources from a reviewed project resource bundle |

## Named queries

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.namedqueries.list` | Read | None in schema | List named queries by project |
| `ignition.namedqueries.read` | Read | `project`, `path` | Read a named query definition (SQL, type, and parameters) |
| `ignition.namedqueries.execute` | Write | `project`, `path` | Execute a named query (allowlisted, dry-run by default) |
| `ignition.namedqueries.write` | Write | `project`, `path`, `query` | Create or edit a named query resource |
| `ignition.namedqueries.delete` | Write | `project`, `path` | Delete a named query resource |
| `ignition.namedqueries.import` | Write | None in schema | Import named query resources from a reviewed project resource bundle |

## History and alarms

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.historian.query` | Read | `paths`, `start`, `end` | Query tag historian data with row limits and provider defaults |
| `ignition.alarms.list` | Read | None in schema | List active/cleared alarms |
| `ignition.alarms.acknowledge` | Write | `source` | Acknowledge an alarm by source (allowlisted, rate limited) |

## Gateway diagnostics

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.gateway.status` | Read | None in schema | Read Gateway lifecycle, JVM memory/uptime and redundancy status |
| `ignition.gateway.modules` | Read | None in schema | List installed module identities, versions, lifecycle states and trial flags |
| `ignition.gateway.connections` | Read | None in schema | Read database and OPC connection status without credentials or connection strings |
| `ignition.gateway.logs` | Read | None in schema | Read a bounded time window of Gateway logs; operational messages may contain sensitive application data |
| `ignition.gateway.diagnose` | Read | None in schema | Combine authorized Gateway status, modules and connections checks; reports coverage and partial failures, not a complete plant health assessment |

## Bundle and reviewed changes

| Tool | Class | Required schema fields | Purpose |
|---|---|---|---|
| `ignition.projects.bundle.inspect` | Read | `bundle` | Inspect an uploaded JSON resource bundle without accessing Gateway projects or applying files; reports formats and hashes |
| `ignition.projects.changes.preview` | Read | `bundle`, `targetProject` | Prepare a token-owned, expiring plan for explicit native Python-library/named-query resource changes; includes before/after hashes and bounded content previews |
| `ignition.projects.changes.apply` | Write | `planId`, `planHash`, `commit` | Apply exactly a stored plan with commit=true and its planHash, operator write gate and current policies; rechecks conflicts and verifies every resource by readback |
| `ignition.projects.changes.status` | Read | `planId` | Read the stored result of a token-owned plan; receipts are observations at application time, not a fresh health check |

## Target grants

| Tool family | Additional scope / behavior |
|---|---|
| Tag reads, definitions, UDTs, OPC mapping search, snapshots and historian | **Readable tags**; tree operations check root and children separately |
| Tag value/definition writes | **Tag write targets**; nested definitions need child grants |
| Project resource/source reads, view/pipeline inspection and search | **Readable project resources**, keyed as `project/module/type/path` |
| Legacy script write/delete/import | **Project script write targets**, keyed as `project/script-type/path` |
| Named-query write/delete/import | **Named query write targets**, keyed as `project/query-path` |
| Named-query execute | Separate **Named query execution targets**; classified Write even for SELECT |
| Alarm acknowledge | **Alarm acknowledgment sources**; listing alarms does not use this acknowledgment allowlist |
| Gateway diagnose | Checks the caller's grants for its child status/module/connection tools; a diagnose grant alone does not confer them |
| Bundle inspect | Operates on the supplied JSON only; no installed project access |
| Changes preview | Resource-read and corresponding script/query write-target grants; can stage a plan while the operator gate is Read-only |
| Changes apply | Current target read/write grants, exact plan ownership/hash, operator gate and explicit `commit: true` |
| Changes status | Owned plan and current target-read grants; returns a stored receipt, not fresh target state |

Native Python libraries use `scriptType: "python"` for source reads and `project/library/path` for their write-policy key. For supported native Python and named-query edits, prefer preview/apply over legacy direct write/import calls. Legacy script APIs and planned native resource APIs have different supported formats; the presence of a tool does not establish compatibility with every SDK resource type.

The bundled resource export is JSON (`ignition-mcp.project-resource-bundle.v1`). It is not an Ignition Gateway backup or a promise that every exported resource can be applied. Inspect bundle completeness before reuse.

## Limits and interpretation

| Gateway setting | UI location | Default |
|---|---|---:|
| `maxConcurrentSessions` | Limits → Concurrent client sessions | 200 |
| `maxRequestsPerMinutePerToken` | Limits → Requests per minute, per token | 300 |
| `maxWriteOpsPerMinutePerToken` | Limits → Write requests per minute, per token | 60 |
| `maxBatchWriteSize` | Limits → Resources per write batch | 50 |
| `historianMaxRows` | Limits → Historian result rows | 5,000 |
| `namedQueryMaxRows` | Limits → Named query result rows | 1,000 |
| `historianDefaultProvider` | Limits → Default historian provider | Empty, Ignition default behavior |

Write-class requests consume the write rate budget even when they are dry-runs. Tool-specific work limits still apply below module-wide caps. Planned changes accept at most 100 entries and also enforce the current batch limit; plans expire after 10 minutes.

`truncated`, `complete: false`, skipped files, bad-quality values and unsupported formats must remain visible in downstream answers. Tag snapshots are not atomic process snapshots. Stored source reads do not execute scripts/SQL or prove Designer behavior. Literal reference search is not complete dependency resolution. Native pipeline readback is qualified for the stated fixture in [validation](VALIDATION.md), not notification delivery or every custom block format.

See [inspection limits](INSPECTION_TOOLS.md), [change contracts](CHANGE_WORKFLOWS.md) and [validation evidence](VALIDATION.md) for exact bounds and qualification limits. The server exposes tools only; MCP resources/prompts and arbitrary Gateway script execution are not implemented.
