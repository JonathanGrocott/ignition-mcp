# Manual acceptance workflow

Use this workflow to validate the signed **Ignition MCP 0.4.0** release on a dedicated development Gateway. Prepare your own synthetic fixtures; the module does not install a project, tags, credentials or a running Gateway.

## 1. Prepare test resources and access

Follow the [User Guide](USER_GUIDE.md) to install the module and connect an MCP client. Confirm the installed module and MCP initialization report `0.4.0`.

In Designer, prepare a mutable project named `McpChangeValidation` and the following test resources:

| Resource | Initial content |
|---|---|
| Memory tag `[default]MCP/Speed` | Numeric value 42, Good quality |
| Python library `MCP/Demo` | `def value():` with an indented `return 42` |
| Optional Perspective view `Inspection/Main` | Label bound to `[default]MCP/Speed` |

Create separate reader and writer API keys through the Gateway. Keep their full credentials in private client configuration. Under **Access & writes**, choose **Profiles only**, **Read-only**, and **Require explicit commit for legacy write tools**, then save.

Give the reader the selected inspection tools, tag root/descendant grants, and project-resource read scope. Give the writer the same read scope, preview/status tools, `ignition.projects.changes.apply`, and script write targets limited to `McpChangeValidation/library/MCP/*`. See [grant examples](USER_GUIDE.md#6-allow-writes). Avoid query-execution and alarm-acknowledgment grants for this workflow.

## 2. Verify reads and preview with commits disabled

Example prompt, using the MCP server name configured in your client:

> Read [default]MCP/Speed, including quality and timestamp. Read the MCP/Demo Python library in McpChangeValidation. Do not write anything.

Expect tag value 42 and saved Python source returning 42. If the optional view exists, inspect its stored binding as a separate read.

Then request:

> Prepare a change preview in McpChangeValidation that changes only MCP/Demo so value() returns 43. Preserve its resource metadata. Show the before/after text and plan ID/hash, but do not apply it.

Expect `dryRun: true`, `writesEnabledAtPreview: false`, and a 10-minute expiry. Reading the library again must still show 42. A deliberate apply attempt while the operator gate is disabled must return `WRITES_DISABLED`.

Plans belong to the API token name that created them. Another token cannot take over the plan. Obtain a fresh preview after expiry, resource changes or module/Gateway restart.

## 3. Apply, read back and inspect in Designer

Select **Allow authorized writes** in the Gateway and **Save changes**. Apply the exact unexpired plan with its ID/hash and `commit: true`.

Expect a verified receipt and saved source returning 43. Replaying the same plan must return its stored receipt without another write. The reader must continue to reject mutations regardless of the operator gate.

Open the test project in Designer. Under **Scripting → Project Library → MCP → Demo**, reopen/refresh the resource as needed and inspect its source. In **Tools → Script Console**, deliberately execute:

```python
print MCP.Demo.value()
```

Expect 43. This Designer step executes the function; MCP source inspection does not. If testing the optional Perspective view, open its session and confirm the live tag binding separately.

## 4. Exercise conflict handling and finish

Prepare a preview changing Demo to 44. Before applying it, make and save a different edit in Designer. Applying the stale preview must return `CONFLICT` and preserve the Designer edit. Read the actual state, then prepare a fresh preview for any intended restoration.

Select **Read-only** and save when finished. Verify that permitted reads still work and later commits are rejected. Retain the target's original export if it is needed for recovery; there is no automatic rollback guarantee.

For repeatable Python runners, environment variables and their commit behavior, use [Local Gateway Testing](LOCAL_GATEWAY_TESTING.md). The committing runner requires a loopback host and a project name beginning `McpChangeValidation`, creates unique fixtures, and can leave them after an interruption.

See [validation results and limits](VALIDATION.md). Record the exact release artifact/checksum used for your own acceptance run.
