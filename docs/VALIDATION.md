# Validation and compatibility

This record summarizes local validation performed on **2026-10-25** for **Ignition MCP 0.4.0**. The final signed **0.4.0** artifact passed Gateway/MCP acceptance after the development milestones described below. The checks were local; no hosted CI or GitHub publication is claimed.

## Environment

- Java 17; compile/test Ignition SDK 8.3.3.
- Native Gateway checks on Ignition 8.3.9 in isolated development instances, using synthetic projects, tags and independent API tokens.
- Memory tags and a UDT supplied tag fixtures. Stored OPC mappings did not establish a PLC connection.
- Initial named-query persistence checks used a synthetic database name. Later interactive checks executed a separate SQLite fixture and returned scalar 42. MCP reads of Python source did not execute its functions.

## Final 0.4.0 artifact acceptance

The signed `ignition-mcp-0.4.0.modl` was installed on the dedicated Ignition 8.3.9 development Gateway. Its SHA-256 matched the installed module bytes:

```text
6a0f7a75c9a3511c937801ed39dbb2312bacae9f934e98c3f7b59856c404a7e6
```

| Check | Result on this artifact |
|---|---|
| Descriptor / embedded runtime version | Both `0.4.0`; reader and writer MCP initialization also reported `0.4.0` |
| Native module lifecycle | `ACTIVE`; Gateway renders the version as `0.4.0 (b0)` |
| Packaging / signing | Current project JARs verified; all 6 payload signatures verified using SHA256withRSA |
| Automated regression | 96 Java tests and 9 UI-state tests passed; no failures/errors/skips |
| Complete tool acceptance | All 42 tools returned successful responses against this exact signed artifact; 36 result assertions passed |
| Strict inspection runner | All 15 inspection tools passed; zero skips and zero unsupported responses |
| Reviewed-change runner | All 14 assertions passed, including committed create/edit/delete, metadata preservation, replay and stale-plan conflict |
| Populated runtime cases | Native UDT/member edits and values, script/query import readback, SQLite execution returning 42, Good historian samples, real synthetic alarm acknowledgment and native binary pipeline readback |
| Authorization | Reader mutation and writer target outside its scope were denied |
| Live operator write gate | 5 assertions passed after saving Read-only in the Gateway UI: inspection/preview remained available, both commit paths returned `WRITES_DISABLED`, Speed stayed 42 and the staged resource stayed absent; original scoped write mode restored |
| Cleanup / baseline | Unique temporary resources removed; baseline Speed remained 42/Good, Demo saved source remained 43, synthetic alarm reset to 0 |

The distributable and matching signed-artifact checksum are in `build/releases/0.4.0/`, alongside `acceptance.json`. Detailed local evidence and credentials are excluded from Git. The Gradle-generated `build/checksum/checksum.json` describes the unsigned package; use the `.modl.sha256` sidecar to verify the signed distributable.

This acceptance covers the Gateway/MCP module behavior and saved content. Designer save/reopen and deliberate Python-console execution, notification delivery, PLC connectivity and production-load qualification remain separate.

## Development milestone results

| Area | Observed result |
|---|---|
| Automated Java tests | 89 passed, zero failures/errors, for the reviewed-change build; includes the inspection tests |
| Gateway UI state tests | 9 passed: profile/grant preservation, validation, draft/status/save races, external configuration changes, themes and initial-load failures |
| Module packaging | Embedded project JARs matched current build outputs; stale/duplicate JAR checks passed; signed development module accepted by Ignition |
| Tool discovery | 42 tools in the reviewed-change build; reported build version checked |
| Inspection milestone | 14 populated tool checks, 8 additional result assertions and 7 policy assertions passed; pipeline read skipped without a native fixture |
| Read-only regression | 14 inspection tools passed against the reviewed-change build; pipeline read remained unqualified |
| Preview only | 3 assertions passed; planned resource remained absent |
| Write gate and plan ownership | 7 assertions passed across operator mode, catalog/version and ownership checks |
| Committed project workflow | 14 assertions passed: native library create/edit/readback, metadata preservation, receipt replay, stale-plan conflict, deletion and native named-query roundtrip/deletion |
| Profiles-only and legacy policy | 16 assertions passed: unmatched token denial, scoped inventories/source reads, hidden content exclusion, per-child tag filtering, nested-write denial and plan target/owner checks |
| Missing-settings migration | 3 assertions passed after restart: commits defaulted disabled, preview remained available, authorized planned/legacy commits were blocked |
| Plan lifetime | Gateway restart invalidated previously staged plans |
| Gateway browser UI | Section navigation, visual profile save/restore, draft/refresh/discard, light/dark appearance, narrow layout and programmatic label associations checked |
| End-to-end readback | A scoped tag read returned 42 with Good quality; a reviewed native library edit was read back as source returning 43 |

Live profile-editor checks restored the saved configuration and compared it with the baseline. Screenshot examples use synthetic resources and contain no API-token secrets. See [capture notes](images/gateway/README.md).

## Interactive end-to-end checks

A subsequent rebuilt `0.4.0-SNAPSHOT` module was exercised through a native MCP client on the same Gateway version. **90 Java tests and 9 UI-state tests passed.** All **42 tools** were invoked: **41 returned successful responses**, while pipeline read returned `NOT_FOUND` because no pipeline fixture was installed. Empty inventories and expected errors are not populated-format qualification.

- Native UDT type/instance creation, definition/member editing, value writes and deletion were committed and read back. This exposed and fixed an immutable-list failure in the SDK deletion call; a regression test models the SDK's list sorting.
- A separate SQLite test database returned scalar 42 through named-query execution. Script/query import, readback and deletion succeeded on unique synthetic resources.
- The built-in Core Historian stored test values; historian queries returned populated samples with Good quality. Earlier timestamps before fixture creation retained their bad/stale quality.
- A synthetic alarm transitioned from ActiveUnacked to ActiveAcked after committing acknowledgment, then was reset. Its configuration was provisioned through the Gateway's native file configuration/scanner, not the tag-write tool's generic property conversion.
- Bundle inspect, preview, apply, status and native Python-source readback succeeded. In-memory receipts expired on the subsequent setup restart as designed.
- Reader writes and writer targets outside the scoped fixture paths were denied. Live read calls reconnected after Gateway restarts without an application restart, using the expired-session HTTP 404 behavior.

These development checks qualify the stated fixture paths and operations. They do not establish arbitrary tag-property JSON conversion, PLC connectivity, or execution of stored Python code. Final release-artifact results are recorded above.

## Native pipeline qualification

The remaining pipeline case was completed on the same Ignition 8.3.9 development Gateway using an SDK-authored native binary resource, not a JSON substitute. The fixture is disabled and contains only two connected Delay blocks: 7,000 ms followed by 11,000 ms. The Gateway's Alarm Pipelines page recognized it as inactive with zero items.

The rebuilt development module passed **96 Java tests and 9 UI-state tests**. Through this Codex session's native MCP connection, `ignition.alarms.pipelines.list` discovered the resource and `ignition.alarms.pipelines.read` returned its block IDs, start/output links, delay settings, disabled state and Designer positions with `complete: true`. All **15 inspection tools** then passed the strict read-only runner: zero missing-fixture skips and zero unsupported responses.

Together with the earlier 41 successful native tool checks, this completed development-stage fixture coverage for **all 42 tools**, preceding the final artifact acceptance above. The new parser reads bounded serialization data without loading stored classes or invoking methods. Tests cover compressed/plain data, shared references, layout values, unknown stored class names, policy denial before content access, truncated/malformed data, unknown codecs/references, nesting limits and decompression limits.

See the [fixture and regeneration notes](../gateway/src/test/resources/pipelines/README.md). Only the stated native v2 fixture is live-qualified; other Ignition versions, custom body codecs and notification delivery require their own acceptance checks.

## What these checks establish

The native checks establish selected SDK integration, resource persistence, policy enforcement and UI behavior on the stated Gateway version. Bounded/partial results and unsupported formats remained explicit. Module fault detection was checked against lifecycle state, including intentionally inactive modules.

Concurrency, partial writes, lost acknowledgments, readback failures and uncertain push outcomes are covered by automated SDK test doubles. Those failure modes were not injected into a live Gateway. There is no atomic-batch or automatic-rollback guarantee.

## Remaining qualification

- Hosted CI and any separately rebuilt/published artifact require their own checksum and acceptance checks. The local signed 0.4.0 artifact above is verified.
- Notification delivery, additional custom pipeline body codecs and other Ignition versions remain unqualified. The disabled native pipeline read case above is verified.
- Designer save/reopen and deliberate console execution require manual acceptance. Source readback alone does not prove application behavior, SQL correctness or dependency completeness.
- Full accessibility compliance, every browser/Ignition patch, production workloads, historian persistence and PLC behavior are outside these checks.
- Reference search is literal; tag snapshots are bounded observations rather than atomic process snapshots.

See [local Gateway testing](LOCAL_GATEWAY_TESTING.md) for repeatable commands, the [manual acceptance workflow](DEVELOPMENT_TEST_WORKFLOW.md) for Designer steps, and [change contracts](CHANGE_WORKFLOWS.md) for recovery semantics.
