# Roadmap

The [changelog](../CHANGELOG.md) lists implemented features. The items below are remaining work or possible extensions.

## Qualification

- Validate the final signed release artifact; complete manual Designer acceptance and broader browser/accessibility checks.
- Qualify native Alarm Notification pipeline formats using representative exports and delivery tests. Preserve explicit unsupported-format responses until that support is established.
- Broaden live coverage for SSE message roundtrips, named-query execution and failure/recovery behavior. Keep controlled fixture results distinct from production qualification.

## Potential extensions

- Semantic reference resolution, including relative, inherited and dynamic references, beyond literal source matches.
- Module-specific Designer validation for additional resource formats.
- Durable change-plan receipts and explicit recovery workflows across restarts.
- MCP resources/prompts if there is a concrete client workflow that benefits from them.

The Designer resource change workflow is documented in [Change Workflows](CHANGE_WORKFLOWS.md).

See [inspection contracts](INSPECTION_TOOLS.md), [change contracts](CHANGE_WORKFLOWS.md) and [validation limits](VALIDATION.md).
