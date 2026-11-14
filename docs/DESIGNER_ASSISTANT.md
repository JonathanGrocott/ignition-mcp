# Designer Assistant 0.1.0

Designer Assistant is a separate module for Ignition **8.3.9**. Its dock can chat with a configured model and create a new Perspective view in the current Designer project through **Ignition MCP 0.5.0**.

## Install and try it

1. Install Ignition MCP 0.5.0 and the signed `ignition-designer-assistant.modl` on a development Gateway with Perspective installed. Trust the module-signing certificate according to your Gateway's policy.
2. In Ignition MCP, enable Streamable HTTP. Grant the user's API token access to `ignition.projects.changes.preview`, `ignition.projects.changes.status`, and `ignition.projects.changes.apply`. Grant read and write targets under a narrow path, such as `Demo/com.inductiveautomation.perspective/views/Assistant/*`. The module uses the existing [write setting and target grants](CHANGE_WORKFLOWS.md). Direct project writes can remain disabled.
3. Reopen Designer, open a mutable Perspective project, and find **Designer Assistant** in the docking layout. Open **Settings** and choose a model. Enter the Ignition MCP API token for this session, or provide `IGNITION_MCP_API_TOKEN` in the Designer process environment. The default MCP path is `/data/ignition-mcp/mcp`. Requests go to the Gateway Designer is connected to. HTTP is supported on loopback, and remote Gateways need HTTPS trusted by the Designer JVM.
4. Send a prompt such as “Create a pump overview with a status section and a maintenance section.”
5. On **View preview**, enter an unused path such as `Assistant/PumpOverview` and click **Preview view**. Review the view JSON and Gateway plan. **Apply new view** submits the reviewed plan with its stored ID and hash. Enable the Gateway's authorized-write setting and run Preview again if it reports read-only mode.
6. Refresh the Designer project to open the new view. If the Apply result is unclear, use **Check status** while the plan is available. A Gateway restart or plan expiry can remove the receipt. Check the view path before preparing another preview.

The generator supports a flex container with text labels and up to six sections. The model proposes text in a small JSON format and the module generates the native view. The preview displays the complete generated JSON before Apply. The operation always uses `create` and checks for existing resources in both the Designer project and the Gateway. The release creates a view resource without adding a Perspective page route. Live bindings, scripts, calls to user-authored tools, and updates to open or saved views are outside this version.

## Model and authentication options

| Provider | Configuration |
| --- | --- |
| **Codex CLI** | Install Codex on the machine running Designer and sign in using your usual organization workflow. A blank profile uses the installed CLI's authentication with the default model unless you enter a model override. If Designer cannot find Codex, enter the executable's absolute path. |
| **Codex CLI with a managed profile** | Enter the name of a trusted, existing local Codex profile to use its model provider and authentication configuration. The assistant runs a read-only, ephemeral Codex task and disables configured MCP servers for that task. Use a CLI version that supports `exec --ephemeral` and `--ignore-user-config` (tested with 0.167.1). |
| **OpenAI API** | Select OpenAI-compatible HTTP, use `https://api.openai.com/v1/chat/completions`, and set a model and API key. |
| **Anthropic API** | Select Anthropic Messages, use `https://api.anthropic.com/v1/messages`, and set a model and API key. |
| **Corporate inference gateway or compatible model service** | Select the wire protocol your service implements and enter the full URL and model ID. For Chat Completions this can include OpenRouter, compatible Azure deployments, internal proxies, Ollama, and LM Studio. For example, Ollama's local endpoint is typically `http://127.0.0.1:11434/v1/chat/completions`. |

The HTTP provider can use a bearer credential, an API key in a custom header, or no authentication. Enter the credential for this session or use `env:VARIABLE_NAME` to read it from the Designer process environment. HTTP adapters do not initiate a browser SSO flow or refresh an enterprise access token. Use an existing Codex profile for managed CLI sign-in, or supply a current credential for an approved HTTP service.

Model providers receive the active project name, the current message, and recent chat history. Settings persist provider selection and other preferences locally. Credentials entered in the dialog stay in memory. The model and CLI do not receive the MCP token or the Apply operation. The Gateway still enforces MCP permissions and validates the saved resource after a write.

## Build and package

Use JDK 17 and the 8.3.9 Ignition SDK. Gradle can resolve the published SDK or use an existing 8.3.9 SDK tree with `-PassistantSdkDir=/path/to/sdk` (containing `core/designer`, `core/client`, and `core/common`).

```sh
./gradlew :designer-assistant:test :designer-assistant:zipModule
MODULE_IN="$PWD/designer-assistant/build/ignition-designer-assistant.unsigned.modl" \
MODULE_OUT="$PWD/designer-assistant/build/ignition-designer-assistant.modl" \
./scripts/sign-module.sh
```

The Gateway module retains its own build and signing artifact. The companion's module ID is `com.jg.ignition.designerassistant` and its JARs load in Designer scope.

## Validation on 8.3.9

The signed 0.1.0 companion and signed Ignition MCP 0.5.0 ran on an isolated official 8.3.9 Gateway. In native macOS Designer, the assistant dock generated a static Pump Overview view using a loopback model fixture. Preview returned a create plan. Apply created the view and displayed the Gateway's verified receipt. File → Update Project pulled the new resource, and the view opened and rendered on the native Designer canvas. A separate live test exercised the companion's Java MCP client, including explicit plan status and readback. The SDK build, signed package, 104 Java tests, and nine existing Gateway UI tests passed.

Codex CLI 0.167.1 completed a model turn through a synthetic local endpoint with a temporary named profile. The test verified that the configured MCP server did not launch. Automated HTTP fixtures cover the OpenAI-compatible and Anthropic request formats. Real enterprise sign-in, public model credentials, and the Windows and Linux Designer launch paths remain unverified.

## Perspective runtime chat

A later Perspective chat feature should run conversations and model authentication on the Gateway and render a component in normal Perspective views. Start with an optional runtime module so that operators can install a chat component without loading the Designer assistant or depending on a workstation's Codex process. Give each conversation a Perspective session identity, select tools by permissions, and keep credentials in Gateway-side configuration. Share the provider request and response contracts where useful, and keep publication, transcript retention, and runtime authorization with the Perspective module.
