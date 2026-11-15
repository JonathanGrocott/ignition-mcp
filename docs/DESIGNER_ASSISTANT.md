# Designer Assistant 0.2.0

Designer Assistant is a separate module for Ignition **8.3.9**. Its dock can chat with a configured model, create a simple Perspective view, and use the **Ignition MCP 0.5.0** catalog for project, tag, and Gateway work.

## Install and try it

1. Install Ignition MCP 0.5.0 and the signed `ignition-designer-assistant.modl` on a development Gateway with Perspective installed. Trust the module-signing certificate according to your Gateway's policy.
2. In Ignition MCP, enable Streamable HTTP. Grant the user's API token access to `ignition.projects.changes.preview`, `ignition.projects.changes.status`, and `ignition.projects.changes.apply`. Grant read and write targets under a narrow path, such as `Demo/com.inductiveautomation.perspective/views/Assistant/*`. The module uses the existing [write setting and target grants](CHANGE_WORKFLOWS.md). Direct project writes can remain disabled.
3. Reopen Designer, open a mutable Perspective project, and find **Designer Assistant** in the docking layout. Open **Settings** and choose a model. Enter the Ignition MCP API token for this session, or provide `IGNITION_MCP_API_TOKEN` in the Designer process environment. The default MCP path is `/data/ignition-mcp/mcp`. Requests go to the Gateway Designer is connected to. HTTP is supported on loopback, and remote Gateways need HTTPS trusted by the Designer JVM.
4. Send a prompt such as “Create a pump overview with a status section and a maintenance section.”
5. On **View preview**, enter an unused path such as `Assistant/PumpOverview` and click **Preview view**. Review the view JSON and Gateway plan. **Apply new view** submits the reviewed plan with its stored ID and hash. Enable the Gateway's authorized-write setting and run Preview again if it reports read-only mode.
6. Refresh the Designer project to open the new view. If the Apply result is unclear, use **Check status** while the plan is available. A Gateway restart or plan expiry can remove the receipt. Check the view path before preparing another preview.

The simple generator supports a flex container with text labels and up to six sections. It creates a new native view without a page route, bindings, or scripts. Use **MCP tools** and an authorized native project resource bundle for edits, imports, page configuration, or more advanced view resources.

## Work with MCP tools

- Open **MCP tools → Load tools**, choose a tool, and edit its JSON arguments. The result pane initially shows the Gateway's input schema. Fields for an applicable project default to the open project. Alternatively, ask Chat to propose a tool call and review it in the same tab.
- **Run read** calls the selected read tool. **Use result in chat** attaches a small result to the next message. Review the result before attaching it because your configured provider will receive it with the prompt.
- **Preview call** either creates or looks up a stored project change plan, or calls a legacy tool with `commit: false`. The review shows the Gateway response and the request that **Apply reviewed call** will submit. Changing the arguments, selected tool, or settings invalidates the review. The model cannot execute the proposed call.
- Prefer stored project change plans for native view, script, query, or other resource bundles. They preserve the reviewed content and enforce a conflict check against the Gateway's current resource. Legacy writes, including tag and UDT definitions, use the Gateway's dry-run and commit contract. A dry-run does not lock the resource or guarantee that the commit will succeed. Save or reconcile relevant unsaved Designer edits before applying a Gateway project change, then use **File → Update Project**.
- Gateway policies still apply per tool and target. The demo view-only token cannot write scripts, queries, or tags. Named-query execution, tag value writes, and alarm acknowledgment are separate tool permissions. Unknown tool contracts are visible in the catalog and cannot be run from the dock until the module supports them.

Examples include “Create a Python project library at Assistant/Formatting”, “Draft a scalar named query at Assistant/Count for the Reporting database”, and “Create a Motor UDT under [default]_types_/Assistant with a Boolean Running member”. For precise operations, select the tool directly and fill in its arguments.

## Model and authentication options

| Provider | Configuration |
| --- | --- |
| **Codex CLI** | Install Codex on the machine running Designer and sign in using your usual organization workflow. A blank profile uses the installed CLI's authentication with the default model unless you enter a model override. If Designer cannot find Codex, enter the executable's absolute path. |
| **Codex CLI with a managed profile** | Enter the name of a trusted, existing local Codex profile to use its model provider and authentication configuration. The assistant runs a read-only, ephemeral Codex task and disables configured MCP servers for that task. Use a CLI version that supports `exec --ephemeral` and `--ignore-user-config` (tested with 0.167.1). |
| **OpenAI API** | Select OpenAI-compatible HTTP, use `https://api.openai.com/v1/chat/completions`, and set a model and API key. |
| **Anthropic API** | Select Anthropic Messages, use `https://api.anthropic.com/v1/messages`, and set a model and API key. |
| **Corporate inference gateway or compatible model service** | Select the wire protocol your service implements and enter the full URL and model ID. For Chat Completions this can include OpenRouter, compatible Azure deployments, internal proxies, Ollama, and LM Studio. For example, Ollama's local endpoint is typically `http://127.0.0.1:11434/v1/chat/completions`. |

The HTTP provider can use a bearer credential, an API key in a custom header, or no authentication. Enter the credential for this session or use `env:VARIABLE_NAME` to read it from the Designer process environment. HTTP adapters do not initiate a browser SSO flow or refresh an enterprise access token. Use an existing Codex profile for managed CLI sign-in, or supply a current credential for an approved HTTP service.

Model providers receive the active project name, the Gateway's tool schemas, the current message, recent chat history, and any tool result you explicitly attach. Settings persist provider selection and other preferences locally. Credentials entered in the dialog stay in memory. The model and CLI receive neither the MCP token nor permission to run a tool.

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

The signed 0.1.0 companion and signed Ignition MCP 0.5.0 ran on an isolated official 8.3.9 Gateway. In native macOS Designer, the assistant dock generated a static Pump Overview view using a loopback model fixture. Preview returned a create plan. Apply created the view and displayed the Gateway's verified receipt. File → Update Project pulled the new resource, and the view opened and rendered on the native Designer canvas.

The signed 0.2.0 package was installed alongside Ignition MCP 0.5.0 on a fresh official 8.3.9 Gateway. The Swing dock integration test discovered the MCP catalog, used a synthetic model to propose a native Python library, named query, UDT type and simple Perspective view, and applied and read back all four. It also checked that previews did not create the script, query, or UDT before Apply and that an inventory result could be attached to chat. The test used the dock's actual Swing controls in a harness. Native Designer rendering and execution of the saved SQL or Jython were outside this run.

To repeat the integration test, use a disposable loopback 8.3.9 Gateway, a fresh project named `DesignerAssistantQA*`, and a private JSON file with `gateway`, `project`, and `token`. Grant the test token the required tools and scopes for the test project's `Assistant/` resources and `[default]_types_/Assistant/` UDT definitions and members. The test starts its own synthetic model, creates unique resources, and leaves them available for Designer inspection.

```sh
DESIGNER_ASSISTANT_QA_CONFIG=/private/path/credentials.json \
  ./gradlew :designer-assistant:test --rerun-tasks
```

Codex CLI 0.167.1 completed a model turn through a synthetic local endpoint with a temporary named profile. The test verified that the configured MCP server did not launch. Automated HTTP fixtures cover the OpenAI-compatible and Anthropic request formats. Real enterprise sign-in, public model credentials, and the Windows and Linux Designer launch paths remain unverified.

## Perspective runtime chat

A later Perspective chat feature should run conversations and model authentication on the Gateway and render a component in normal Perspective views. Start with an optional runtime module so that operators can install a chat component without loading the Designer assistant or depending on a workstation's Codex process. Give each conversation a Perspective session identity, select tools by permissions, and keep credentials in Gateway-side configuration. Share the provider request and response contracts where useful, and keep publication, transcript retention, and runtime authorization with the Perspective module.
