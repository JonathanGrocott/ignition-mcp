# Roadmap

The [0.4.0 changelog](../CHANGELOG.md) lists implemented features. The items below are remaining work or possible extensions, not commitments or currently supported capabilities.

## Qualification

- Validate the final signed release artifact; complete manual Designer acceptance and broader browser/accessibility checks.
- Qualify native Alarm Notification pipeline formats using representative exports and delivery tests. Preserve explicit unsupported-format responses until that support is established.
- Broaden live coverage for SSE message roundtrips, named-query execution and failure/recovery behavior. Keep controlled fixture results distinct from production qualification.

## Potential extensions

- Semantic reference resolution, including relative, inherited and dynamic references, beyond literal source matches.
- Additional typed project-resource changes after their metadata, concurrency and recovery behavior are qualified.
- Durable change-plan receipts and explicit recovery workflows across restarts.
- MCP resources/prompts if there is a concrete client workflow that benefits from them.

Arbitrary Gateway script execution and generic resource writes are outside the current module's supported surface. Any expansion needs a separate design for access control, bounded execution and recovery.

See [inspection contracts](INSPECTION_TOOLS.md), [change contracts](CHANGE_WORKFLOWS.md) and [validation limits](VALIDATION.md).
