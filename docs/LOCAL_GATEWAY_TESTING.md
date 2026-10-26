# Local Gateway testing

This guide is for developers validating **Ignition MCP 0.4.0**. For installation and everyday use, start with the [illustrated User Guide](USER_GUIDE.md). Use a dedicated development Gateway/provider/project and preserve its data volume when upgrading the module.

## Build and install

For the release build, set `moduleVersion` in `build.gradle.kts` to **`0.4.0`** before building. Verify that the packaged module and MCP initialization both report `0.4.0`, without a development suffix. Keep the final artifact's checksum with its test results; earlier snapshot validation does not verify that final artifact.

Use Java 17. On macOS, select it for the build if needed:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test zipModule checksumModl
node --test gateway/src/test/js/mcp-admin.test.cjs
./scripts/sign-module.sh
```

Install the **signed** `build/ignition-mcp.modl` through **Platform → System → Modules**. The unsigned file is a build intermediate. Review the signing certificate and confirm the module is running. The alternative `scripts/build_local_module.sh` does a clean build and signing; it may remove ignored build outputs, so retain needed local evidence first.

Open **Services → Ignition MCP → Configuration**. Create API keys through **Platform → Security → API Keys**. Start with:

- Service and Streamable HTTP enabled.
- **Read-only**, **Profiles only**, and **Require explicit commit for legacy write tools** enabled.
- A reader profile matching the test token name, with only the read tools and tag/project scopes needed by the selected checks.
- Explicit root and descendant tag grants, for example `[default]MCP` plus `[default]MCP/*`.
- Resource-read scope for the test project, such as `McpChangeValidation/*`.

Do not use a global `*` write grant as a first-connect recipe. Save configuration deliberately. The [manual acceptance workflow](DEVELOPMENT_TEST_WORKFLOW.md) describes how to prepare synthetic resources; the module itself does not install those fixtures.

## Private environment

The Python runners read process environment variables; they do not automatically load `.env`. Set the token privately, then set the nonsecret values:

```bash
export MCP_BASE_URL=http://localhost:8088/data/ignition-mcp
export MCP_INSPECTION_PROJECT=MyProject
export MCP_INSPECTION_TAG_PATH='[default]MCP'
export MCP_INSPECTION_PROVIDER=default
export MCP_INSPECTION_REPORT=build/inspection-smoke.json
```

Create `build/` before requesting report output. `MCP_BASE_URL` is the mount base, **without** the final `/mcp`. Use the port of your test Gateway. Keep credentials out of committed files and screenshots. If loading a trusted private env file, export its assignments into the process running the client.

## Start with read-only checks

```bash
python3 scripts/test_mcp_inspection.py
```

This runner never provisions fixtures, writes tags/resources, acknowledges alarms, executes scripts/queries or imports a project. It checks the catalog and authorized inspection surfaces. Its report distinguishes passes, missing-resource skips and explicitly unsupported pipeline formats. It closes its MCP session when done.

The token needs each requested tool grant. `gateway.diagnose` also checks the grants for its child status/module/connection tools. Project/tag results require their own scopes. A narrow reader will correctly receive denial for tools outside its profile.

Set `MCP_INSPECTION_EXPECT_FIXTURES=true` only after preparing representative view, source-search, UDT and tag/OPC fixtures. Empty inventory/catalog success does not establish populated format support. See [inspection contracts](INSPECTION_TOOLS.md).

## Preview checks without committing

Prepare a mutable test project and a token with bundle inspection, resource listing, preview and target read/write grants. A preview needs write-target permission even though it does not write. Keep the operator gate Read-only.

```bash
export MCP_CHANGE_PROJECT=McpChangeValidation
export MCP_CHANGE_REPORT=build/change-preview-smoke.json
python3 scripts/test_mcp_changes.py
```

With `MCP_COMMIT_CHANGE_TESTS` unset/false, this validates a synthetic bundle, stages a create plan and checks that the resource remains absent. It does not execute scripts or SQL. Plans consume bounded memory and expire after 10 minutes.

## Opt-in change commits

Only on the dedicated loopback development Gateway, save **Allow authorized writes** and grant the runner's exact tools/targets. The runner needs planned apply and native named-query write for its query roundtrip, in addition to read/preview grants. It requires a target project name beginning `McpChangeValidation` and a loopback hostname for commit mode.

```bash
MCP_COMMIT_CHANGE_TESTS=true python3 scripts/test_mcp_changes.py
```

It uses unique `MCP/Workflow_...` and `MCP/Query_...` resources, verifies create/edit/replay/conflict/delete behavior and checks stored metadata. An interrupted run can leave those fixtures. It does not execute their Python or SQL. Review the [change contract](CHANGE_WORKFLOWS.md) and current target state before recovery; there is no atomic rollback guarantee. Return the operator gate to the mode intended for that Gateway after testing.

## Legacy shell smoke runners

**These are not read-only defaults.** Review their source and environment before use:

| Runner | Behavior to account for |
|---|---|
| `scripts/test_mcp_local.sh` | Tag definition commit is enabled by default through `MCP_REQUIRE_TAG_WRITES=true`; set it to `false` to skip that block |
| `scripts/test_mcp_extended.sh` | Default tag-definition commits, a committing named-query execution when a suitable query is found, and explicit blocked-write probes; optional script/query create-delete commits use separate flags |

In the extended runner, `MCP_REQUIRE_TAG_WRITES=false` skips tag-definition creation blocks; it does **not** disable named-query execution or every committing request. The optional `MCP_COMMIT_PROJECT_SCRIPT_TESTS` and `MCP_COMMIT_NAMED_QUERY_TESTS` flags control additional resource commits, not the execution probe. Do not use it as a first-read check on an operational Gateway. A supposedly blocked probe can change data if its target is actually granted.

These scripts load a trusted `.env` by default and require `curl` and `jq`. Their fixture assumptions and older compatibility write surfaces differ from the native planned-change workflow. Prefer the Python read-only/preview runners for initial validation.

## Record what was verified

Keep automated, native Gateway, client, browser and Designer results separate. Reading saved source proves persistence, not function execution or SQL behavior. Running local checks does not prove hosted CI or production readiness. The [validation summary](VALIDATION.md) states the checks already performed and remaining limits.
