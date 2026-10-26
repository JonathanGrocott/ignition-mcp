# Native inspection tools

Available in **Ignition MCP 0.4.0**. These 15 inspection tools are part of the 42-tool catalog. All are read-only and use the Gateway SDK/project manager directly. Perspective inspection reads stored project resources through the native SDK.

Version **0.4.0** also includes [reviewed change workflows](CHANGE_WORKFLOWS.md), an operator write gate and optional profiles-only authorization. The additive global/profile behavior below describes compatibility mode; profiles-only mode ignores the global grants.

## Tool contracts

| Tool | Required arguments | Optional arguments and result |
|---|---|---|
| `ignition.perspective.views.list` | None | `project`, `pathPrefix`, `maxResults`, `maxResourcesExamined`; returns `views` with effective/defining project and resource identity |
| `ignition.perspective.views.read` | `project`, `path` | `maxComponents`, `maxDepth`, `includeProperties`, `includeScripts`; returns view properties/params, parameter directions, bounded component tree, complete binding definitions including transforms, event actions, and JSON pointers |
| `ignition.alarms.pipelines.list` | None | Inventory arguments; discovers Alarm Notification resource types containing `pipeline` rather than assuming one fixed type ID |
| `ignition.alarms.pipelines.read` | `project`, `path` | `resourceType` from inventory; returns stored JSON or native `data.bin` configuration. Native binary v1/v2 is parsed as data without loading stored classes or executing methods, scripts or notifications. Unknown body codecs/versions return `UNSUPPORTED_FORMAT` |
| `ignition.projects.search` | `query` | `project`, `moduleId`, `resourceType`, `pathPrefix`, `caseSensitive`, `maxResults`, `maxResourcesExamined`; literal matches in JSON scalar values and other UTF-8 source files, with JSON pointers or 1-based line numbers |
| `ignition.projects.references` | `target` | Search arguments; case-sensitive by default. Returns literal reference **candidates**, not a complete semantic dependency graph |
| `ignition.udts.list` | None | `provider` (default `default`) or explicit `path`; bounded traversal of `_types_` folders, stopping at each UDT definition |
| `ignition.udts.read` | Provider-qualified `path` to a UDT definition | Tag traversal limits; returns authorized definition/member properties, local overrides, inheritance flags, and parameters |
| `ignition.tags.opc.search` | Provider-qualified root `path` | `query` filters case-insensitively on server/item/path text; returns configuration mappings. Does not browse devices or interpret PLC logic |
| `ignition.tags.snapshot` | Provider-qualified root `path` | `includeValues` (default true) and tag traversal limits; returns configurations plus atomic tag values, quality, good flag and timestamps |
| `ignition.gateway.status` | None | Gateway lifecycle state, JVM uptime/memory and basic redundancy enabled/active/master flags |
| `ignition.gateway.modules` | None | `maxResults`; installed module identity/version/state, trial and fault flags |
| `ignition.gateway.connections` | None | `maxResults` per database/OPC category; connection names/status and database pool counts. No credentials, connection strings or active queries |
| `ignition.gateway.logs` | None | `lastMinutes`, `maxResults`, `minLevel`, `logger`, `search`; bounded Gateway log query. Messages can contain sensitive application data; diagnostic properties and stack traces are omitted |
| `ignition.gateway.diagnose` | None | `maxResults`; combines authorized status/module/connection tools. Returns per-step results, coverage and `healthy`, `degraded`, `down` or `unknown` verdict |

## Policy and limits

Every tool passes through the existing dispatcher tool authorization, request rate limiting and audit path. Project inventory, reads and search additionally enforce `allowedProjectResourceReadPatterns` (including matching profile grants) **before reading resource content**. Tag tools enforce tag-read policy on the canonical root and on each discovered child before configuration/value reads. A grant on a parent does not implicitly grant a child. To traverse a tree, authorize both its browse root and the required descendants. A narrowly granted descendant cannot be discovered through a denied ancestor; supply an authorized root directly.

`gateway.diagnose` checks authorization for each child tool; permission to diagnose alone does not grant status/module/connection access. New read tools inherit the current global read-tool `*` default. The existing additive profile/global grant semantics have not changed. Review those grants before exposing new operational data, particularly logs.

| Budget | Default | Maximum |
|---|---:|---:|
| Project inventory `maxResults` | 200 | 1,000 |
| Project resource metadata examined | 5,000 | 10,000 |
| Search matches | 100 | 500 |
| Search files / source bytes / JSON nodes or text lines | — | 2,000 / 8 MiB / 50,000 |
| Source file size | — | 1 MiB |
| Pipeline files / source bytes | — | 32 / 8 MiB total; 1 MiB per file |
| Native pipeline expanded bytes / nodes / depth | — | 8 MiB / 10,000 / 64 |
| Search/detail nesting depth | — | 64 |
| View components | 300 | 1,000 |
| View component depth | 20 | 64 |
| Tag configurations examined | 200 | 1,000 |
| Tag browses | 30 | 100 |
| Tag traversal depth | 5 | 20 |
| Tag total SDK wait/traversal budget | 10 seconds | 30 seconds |
| Gateway modules/connections | 200 | 500 |
| Gateway log events | 100 | 500 |
| Log window | 15 minutes | 24 hours |
| Individual log message | — | 4,000 characters |
| Serialized inspection result | — | 2 MiB |

Invalid limits produce `VALIDATION_ERROR`. Resource and tag work limits return explicit truncation/incomplete metadata. Search reports skipped binary/oversize files. Oversize results fail with `RESULT_TOO_LARGE` rather than silently dropping content. Tag SDK timeouts cancel pending futures where supported. Browse continuation pages are not followed; the response reports `browseContinuation` rather than claiming the subtree is complete.

Scripts in Perspective bindings/events are omitted by default (`scriptOmitted: true`). Set `includeScripts: true` to inspect their stored source. This never runs them. Set `includeProperties: true` to include component `props` and `custom` fields; view-level params/props/custom are always included.

Native pipelines return `configuration["data.bin"]` with format/version/compression metadata, explicit stored `properties` (including `blocks`, `startingBlock` and `enabled` when saved), and a passive `serialization` tree preserving class names, method names, arguments and reference IDs. Omitted default properties are not inferred. Unknown objects remain stored structure; unsupported body codecs fail explicitly. Reading stored script text never executes it, and this tool does not edit pipelines or inspect active notification jobs.

A tag snapshot collects live values **after** configuration discovery and is not an atomic process snapshot. Bad/uncertain quality remains visible and values are not replaced with plausible defaults. Configuration discovery may still be complete when values have bad quality; inspect each value's `good`/`quality` fields separately.

Gateway diagnosis covers lifecycle, installed modules and database/OPC connections only. It does not verify licensing, notification delivery, historian writes, PLC logic or project correctness. Missing managers, denied child tools, unknown states, failed checks or truncated inventories prevent a `healthy` verdict. Trial status is reported rather than treated as a license qualification.

Module faults use the `FAULTED` lifecycle state; native 8.3 fault metadata can exist on a module that is not faulted. Intentionally disabled (`INACTIVE`) modules are listed but do not themselves degrade the verdict. Pending/faulted modules and non-valid database/non-connected OPC states do degrade it.

## Example calls

Use the existing authenticated `tools/call` transport, with the following names and arguments:

```json
{"name":"ignition.perspective.views.read","arguments":{"project":"MyProject","path":"Equipment/Motor","includeScripts":true,"maxComponents":100}}
```

```json
{"name":"ignition.projects.references","arguments":{"project":"MyProject","target":"[default]Conveyors/Line1/Speed","maxResults":50}}
```

```json
{"name":"ignition.udts.read","arguments":{"path":"[default]_types_/Motor","maxTags":100}}
```

```json
{"name":"ignition.tags.snapshot","arguments":{"path":"[default]MCP","maxTags":100,"maxBrowses":20,"maxDepth":4}}
```

```json
{"name":"ignition.gateway.diagnose","arguments":{"maxResults":100}}
```

## Verification

The [2026-10-25 validation record](VALIDATION.md) separates automated, populated live, policy and unverified evidence.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test zipModule checksumModl
```

The added tests cover authorized/denied resources and children, source locations, binding transforms/event actions, component/resource/tag/browse budgets, SDK timeouts, bad-quality values, unsupported pipeline formats, partial diagnostic failures, child-tool policy and log query bounds.

For a **read-only** live check against an installed development build:

```bash
set -a
source .env
set +a
export MCP_BASE_URL=http://localhost:8088/data/ignition-mcp
export MCP_INSPECTION_PROJECT=MyProject
export MCP_INSPECTION_TAG_PATH='[default]MCP'
export MCP_INSPECTION_REPORT=build/inspection-smoke.json
python3 scripts/test_mcp_inspection.py
```

This script never writes tags, acknowledges alarms, executes scripts/queries, imports resources or creates fixtures. Its report distinguishes passes, missing-fixture skips, and unsupported pipeline formats. A successful catalog or empty inventory is not proof that populated resources decode correctly. Use representative real exports for every installed module version, particularly pipeline formats. Designer/browser view behavior is outside these read-only source inspection checks.

Set `MCP_INSPECTION_EXPECT_FIXTURES=true` to require populated view, source-match, UDT, tag snapshot and OPC mapping results. Set `MCP_INSPECTION_UDT_PATH` to an authorized UDT subtree when provider-wide browsing is outside the token's scope. Separately set `MCP_INSPECTION_EXPECT_PIPELINE=true` to require a readable pipeline; a missing fixture or unsupported format then fails the runner. The [disabled native fixture](../gateway/src/test/resources/pipelines/README.md) provides a repeatable pipeline case.

## Follow-on work

- Qualify additional native pipeline block/body codecs and Ignition versions against representative target-version resources. Native v2 fixture readback on Ignition 8.3.9 does not establish notification delivery or every custom block format.
- Add semantic reference extraction/resolution, relative tag paths, UDT instance discovery and dynamic-reference annotations beyond literal matches.
- Staged bundle inspection and before/after verification are now included in [0.4 change workflows](CHANGE_WORKFLOWS.md). Broader resource types remain follow-on work.
- The operator write gate and profiles-only mode are now included in [0.4 change workflows](CHANGE_WORKFLOWS.md); configure scoped profiles before using mutation tools.
- Evaluate arbitrary Gateway script execution as a separate capability with explicit authorization; it is not included in these tools.
- Consider MCP resources/prompts as a separate protocol expansion.
