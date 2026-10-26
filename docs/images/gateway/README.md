# Gateway UI screenshot examples

These screenshots illustrate the **Ignition MCP 0.4.0** user documentation. Captured **2026-10-25** from the pre-release native `0.4.0-SNAPSHOT` module on the local synthetic Ignition **8.3.9** development Gateway. Images are real 1280 × 720 browser captures. The snapshot label records capture provenance; it is not the version to distribute. They are embedded in the [User Guide](../../USER_GUIDE.md) and [Gateway UI guide](../../GATEWAY_UI.md).

The fixture uses loopback port **6890**, projects/tags prepared for testing, two scoped development profiles, and an already-enabled operator write gate. These are examples, not installation defaults. New/older configurations missing `writesEnabled` default to committed writes disabled. Default global compatibility grants are broad; configure Profiles only and explicit scopes for an initial client.

| Image | State illustrated |
|---|---|
| `overview.jpg` | Saved endpoint and saved access summary, System theme following dark appearance |
| `connection.jpg` | Saved endpoint, authentication header guidance and API Keys link |
| `access-writes.jpg` | Saved Allow authorized writes selection; additional controls continue below the viewport |
| `profile-editor.jpg` | Unsaved reader example with `plant-reader`, `[default]MCP`/descendants and `MyProject/*` scopes |
| `unsaved-changes.jpg` | Unsaved Read-only selection while saved mode remains authorized writes; Save/Discard bar |
| `activity.jpg` | Two actual read-only calls: tag read and native Python-library read, with observed outcomes/latency |
| `limits.jpg` | Session, request, write, batch and query/historian limits |
| `light-theme.jpg` | Overview with browser-local Light theme selected |

The profile and write-mode drafts were **discarded**, not saved. Activity shows actual read-only calls; source inspection did not execute Python or SQL. The screenshots contain synthetic example identities and no API-token secrets or passwords. No EXIF metadata is embedded. The trial banner is part of the development Gateway UI.
