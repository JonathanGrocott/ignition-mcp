# Reviewed project changes — 0.4.0

Ignition MCP 0.4.0 adds four change-workflow tools (42 tools total), an operator write gate, and a profiles-only policy mode. It builds on the read-only inspection tools without adding arbitrary Gateway script execution or generic project-resource writes.

## Operator configuration and migration

`writesEnabled` defaults to **false**, including existing persisted configurations that do not contain the field. A Gateway administrator must select **Access & writes → Allow authorized writes → Save changes** in the MCP configuration before any mutating MCP tool can commit. `commit=true` in a tool call cannot turn this operator setting on. Existing dry-runs remain available; a mutating call that would commit receives `WRITES_DISABLED`. The gate covers legacy tag/definition/alarm/script/named-query mutation tools as well as the new apply tool. It does not change unrelated Gateway/Designer interfaces.

Disabling commits rejects later commit decisions; it does not cancel SDK writes already submitted. Planned apply rechecks the gate, grants, expiry and mutability immediately before submission as well as at request entry.

`authorizationMode` has two values:

- `additive` (compatibility default): global grants and matching profile grants are combined. A global `*` still grants access even if one matching profile is narrower.
- `profiles-only`: **all global tool and target grants are ignored**. Only matching token profiles grant access. Unmatched tokens and empty profiles deny access. Multiple matching profiles still combine their grants; use exact token names or review overlapping patterns.

Authenticated initialization, ping and catalog discovery remain available; profiles govern tool calls and their target access. Legacy script/named-query source reads and inventories now enforce project-resource read grants, and tag browse/recursive definition results filter children individually. Nested definition writes check child grants and reject paths outside the parent tree. Native Python libraries can be read through `scripts.project.read` with `scriptType: "python"`; their planned writes use the library target grant.

The Gateway UI exposes write-mode choices, authorization mode and a visual profile editor under **Access & writes**. **Add reader** and **Add writer** append a profile with empty token/target scopes; they preserve existing profiles and the current write mode. These controls edit a draft; **Save changes** persists it. In Profiles only mode, saved global fields remain visible but disabled and inactive. Switching back to additive reactivates those global grants. See the [Gateway UI guide](GATEWAY_UI.md) for the complete editing and save behavior.

To authorize a planned change, the caller needs read-tool access to preview, write-tool access to apply, project-resource read permission for every target, and the corresponding script or named-query write-target grant. Python-library write paths use `project/library/path`, consistent with the existing script policy. Apply has its own tool grant; it does not require enabling a legacy direct-import tool. The write gate must also be enabled. Preview can be prepared while commits are disabled.

## Tools

| Tool | Arguments | Behavior |
|---|---|---|
| `ignition.projects.bundle.inspect` | `bundle` | Validates and inventories an uploaded portable JSON bundle. Reports resource/file identities, attributes, semantic fingerprints, decoded size and completeness without reading Gateway projects or applying files |
| `ignition.projects.changes.preview` | `targetProject`, `bundle` | Checks current target permissions, mutability, inheritance and operation constraints; stages a token-owned 10-minute plan with before/after identities, metadata, fingerprints and bounded file text previews |
| `ignition.projects.changes.apply` | `planId`, `planHash`, `commit: true` | Uses only the stored target/content, rechecks current grants and current target state, submits SDK changes with the previewed signatures, then reads back every target |
| `ignition.projects.changes.status` | `planId` | Returns the owned plan or its stored application receipt, subject to current target read grants. This is not a fresh target-state query |

Preview is **not human approval**. The caller can supply `commit=true`; the operator gate and policy provide authorization, while the stored hash/plan provide consistency. Plans are kept in bounded Gateway memory and invalidated by expiry or module/Gateway restart. They are not durable deployment jobs.

## Bundle contract

The bundle uses the existing `ignition-mcp.project-resource-bundle.v1` export envelope. JSON only: there is no arbitrary local file path, URL fetching, ZIP extraction or filesystem scan.

The inspection tool runs inside the live module but can inspect uploaded exports without importing them or accessing any installed project. It is not a standalone parser for use when the Gateway itself is offline.

```json
{
  "format": "ignition-mcp.project-resource-bundle.v1",
  "resources": [
    {
      "moduleId": "ignition",
      "resourceType": "script-python",
      "path": "MCP/Example",
      "operation": "create",
      "applicationScope": "A",
      "version": 1,
      "restricted": false,
      "overridable": true,
      "attributes": {},
      "data": {
        "code.py": {"encoding": "utf-8", "value": "def value():\n    return 42\n"}
      }
    }
  ]
}
```

Supported planned changes are:

- Native `ignition/script-python` library resources: version 1, one UTF-8 `code.py` file.
- `ignition/named-query` resources: the SDK's current resource version, with its native text/JSON payload and attributes. Export a current resource using `projects.resource.read/export` rather than guessing the native metadata schema. Legacy binary/XML payloads are rejected.

Other resource types can be inventoried by bundle inspection but cannot be planned/applied. Views, reports, event/startup/timer scripts, folders, unary resources, manifests and Gateway configuration are outside this apply surface. Inherited resources cannot be overridden implicitly; explicitly target the defining project and obtain its permissions instead.

Operations are explicit per resource: `create`, `edit`, `upsert` (default) or `delete`. A delete entry must contain identity/operation and **no data payload**. There is no implicit deletion of resources absent from a bundle. A truncated bundle, count mismatch, duplicate identity, noncanonical/traversal path, unknown encoding, or unsupported planned type rejects the plan; unsupported resources are not silently skipped.

Ignition letter scope codes and numeric scope bitmasks are both preserved. Unknown resource attributes are retained; calculated `lastModification` and `lastModificationSignature` attributes are excluded from planned content/fingerprints. JSON object key order and JSON-file whitespace do not cause false differences; script/SQL file bytes, arrays, flags, scope, version, documentation and noncalculated attributes do participate in comparisons.

## Apply and recovery semantics

1. Review the preview and the supplied bundle. File text previews are capped at 4,000 characters per before/after side and explicitly report truncation; read/export the authorized resource to inspect omitted content.
2. Apply using its `planId` and exact `planHash`. Replacement bundle/project arguments are rejected. `defaultDryRun=false` does not remove the explicit `commit=true` requirement for this tool.
3. The server rechecks all target read/write grants, the operator gate, project mutability and batch limit. It refuses resources that changed semantically since preview and uses the original SDK signatures in modify/delete operations for the SDK's concurrent-edit checks.
4. An acknowledged push is followed by independent reads of every target, including unchanged entries and deleted-resource absence. Only matching readback returns `verified`/`committed`. All-unchanged plans verify without a push.

| State | Meaning and next step |
|---|---|
| `pending` | Preview exists; nothing has been submitted |
| `verified` | Every target matched at `observedAt`; replay returns that receipt without another write |
| `conflict` | A target differed from its preview; no push was made. Prepare a new preview |
| `verification-failed` | Push completed but one or more readbacks failed or differed. Some changes may have landed; inspect target state |
| `outcome-unknown` | Push/future failed, timed out or was interrupted. Some changes may already have landed; inspect before making a new plan |

An attempted plan is not automatically retried. Replays of unsuccessful attempts return the stored failure; successful replays return the stored success. After an uncertain outcome, use resource inspection/export to establish current state and prepare a new preview deliberately. Expired/unknown/other-token plan IDs return `NOT_FOUND`. The status receipt does not claim the project is still unchanged after the application observation.

There is no atomic-batch or automatic-rollback guarantee. The SDK may apply some resources before a failure. Before replacing important resources, retain an export outside the active project. The server does not blindly roll back over concurrent Designer/operator changes. These checks verify resource persistence, not Python execution, SQL correctness, dependency completeness, notification delivery or Designer/browser behavior. Saving library/query source can affect the project's normal lifecycle and consumers.

## Bounds

- Bundle JSON and decoded payload: 2 MiB each; 1–100 resource entries.
- Per resource: 512 KiB, 32 data files, 256 attributes; JSON nesting depth 64.
- Plans additionally obey current `maxBatchWriteSize` (default 50, at most 100 here), both at preview and apply.
- Preview/result: 2 MiB. Oversize previews are rejected before a usable plan is returned.
- Plan store: 32 records, 16 MiB estimated payload/summary material, 10-minute TTL. Capacity errors require waiting for expiry.
- SDK push future: 30-second wait; timeout means unknown outcome, not cancellation or proof of no writes.

## Validation commands

See the [0.4 validation record](VALIDATION.md) for automated and isolated Gateway results and their limits.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test zipModule checksumModl
node --check gateway/src/main/resources/mounted/mcp-admin.js
```

Preview-only live checks (requires a prepared mutable development project and scoped read/write target grants; operator commits may stay disabled):

```bash
export MCP_BASE_URL=http://localhost:8088/data/ignition-mcp
export MCP_CHANGE_PROJECT=McpChangeValidation
# IGNITION_API_TOKEN must already be in the environment.
python3 scripts/test_mcp_changes.py
```

For a **disposable loopback Gateway** with writes explicitly enabled, set `MCP_COMMIT_CHANGE_TESTS=true`. The script requires a `McpChangeValidation...` project name, uses unique `MCP/Workflow_...` and `MCP/Query_...` paths, verifies its create/edit/conflict/replay/delete operations, and never executes its stored scripts or SQL. Existing resources are not selected for replacement. An interrupted/failed run can leave its uniquely named fixtures for inspection. `MCP_CHANGE_REPORT` writes a summary without credentials or source payloads.

Native binary pipeline decoding, semantic dependency resolution, arbitrary script execution, durable deployment orchestration and MCP resources/prompts remain separate follow-on work.
