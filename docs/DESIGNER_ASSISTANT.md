# Designer Assistant

Designer Assistant adds a chat dock to **Ignition Designer 8.3.9**. Open a project and ask about its Perspective views, scripts, Named Queries, tags, UDTs, or other resources. You can also ask for changes and review them before applying them.

Designer connects to the model provider from the workstation. For Gateway-backed actions, the dock uses the signed-in Designer session and discovers the tools authorized for that user in Ignition MCP. It also uses native Designer APIs for working-copy inspection and basic UDT creation. Model credentials are configured on the workstation. Gateway authorization is configured by a Gateway administrator.

## Download and install

Use the matching pair on an Ignition **8.3.9** test Gateway.

| File | Version | Scope |
| --- | --- | --- |
| `ignition-mcp.modl` | **0.5.1** | Gateway tools and access configuration |
| `ignition-designer-assistant.modl` | **0.4.7** | Designer chat dock and local model connections |

Download both `.modl` files and their `.sha256` files from the [Ignition MCP 0.5.1 prerelease](https://github.com/JonathanGrocott/ignition-mcp/releases/tag/v0.5.1). Both modules use the same signing certificate as the 0.5.0 release.

Check each module against its accompanying `.sha256` file. From the folder containing a module and its checksum file, run `sha256sum -c ignition-mcp.modl.sha256` on Linux or `shasum -a 256 -c ignition-mcp.modl.sha256` on macOS. Repeat for `ignition-designer-assistant.modl.sha256`. In PowerShell, run `Get-FileHash -Algorithm SHA256 .\ignition-mcp.modl`, then repeat for the Designer module. Compare each hash with the first value in its checksum file.

1. In the Gateway, open **Platform → System → Modules** and choose **Install or Upgrade Module**. Install the Gateway module, then the Designer module.
2. Review the signing certificate when prompted. Follow any restart prompt and confirm both versions on the Modules page. Reopen Designer so the workstation loads the installed Designer module.
3. Open your project. If the dock is hidden, choose **View → Panels → Designer Assistant**.

[Ignition module installation and certificate verification](https://www.docs.inductiveautomation.com/docs/8.3/getting-started/installing-and-upgrading/installing-or-upgrading-a-module)

## Grant Gateway tool access

In **Services → Ignition MCP → Configuration → Access & writes**, add a reader profile for the user's Designer identity. Under **API token names**, enter `designer:jane` for a user who signs into Designer as `jane`. Enter the exact username that Ignition records. The dock authenticates through that Designer session and does not need an Ignition API key.

Start with scoped read tools and paths. For a first Perspective test, grant `ignition.perspective.views.list` and `ignition.perspective.views.read` and set **Readable project resources** to `MyProject/com.inductiveautomation.perspective/views/*`, replacing `MyProject` with the actual project name. Click **Save changes**. **Profiles only** applies matching profile grants without adding the Gateway's global grants. Check the effect on existing MCP clients before changing this Gateway-wide setting.

## Try a chat and review a change

1. Open **Model settings**, choose a provider below, and click **Test connection**. Click **OK** after it succeeds.
2. Ask “List the Perspective views in this project.” Open one view, select a component, and ask “What does this component do?” The dock shows the editor context it uses.
3. In a test project with write access, ask “Create a memory-tag UDT called Pump under Utilities/Cooling with a Boolean Running member.” For a different resource type, ask “Create a Project Library script at Utilities/MemoryTags. Save it without running it.”
4. In **Review** mode, inspect the proposal and any **Details** before you choose **Approve**. You can also dismiss it or send a revision. **Auto-apply** applies permitted changes in that chat. New chats start in **Review** mode.

Press **Enter** to send and **Shift+Enter** for a new line. Drag the handle above the message box to resize it.

Choose **New chat** to open a tab. Tabs take their name from the first message. Double-click a tab, use its **Rename chat…** menu, or press **F2** with the tab selected to give it a name. Switch between tabs to return to each conversation, draft, and pending proposal. Model settings are shared across tabs. Chats are kept while Designer is open.

Open a Perspective view, Project Library script, or Named Query and ask “What does this do?” The dock shows the active editor, and the assistant receives its exact path when you send the message. In Perspective, select a component and ask “What does this component do?” The assistant also receives the selected Project Browser resources and visible Tag Browser selections. It can inspect the referenced resource using the existing tools. Edits still pending inside an editor may be missing from resource reads.

Use **Attach** to select a JSON file up to 4 MiB or an Ignition project export ZIP up to 2 MiB. Ask what the file contains. Plain JSON can be inspected directly. For imports, use an Ignition ZIP or the supported resource bundle format. The input clears the attachment after sending. The most recent JSON file remains available for follow-up questions in that chat. Export requests produce a **Save export…** button.

For large results, the assistant can retrieve individual JSON sections or continue through a listing within the current turn. Saved model context and Gateway tool limits still apply. For a large edit, identify the exact resource and change so the assistant can inspect the relevant sections and preserve existing configuration.

The active project is taken from the authenticated Designer session. Open another project to work on its resources. Native inspection can see unsaved Designer state. Gateway changes persist directly and check for unsaved edits to their targets. After a successful project change, the assistant triggers Designer’s **Update Project** command. Concurrent edits use Designer’s standard conflict dialog. If the update cannot start, the chat points you to **File → Update Project**.

For Gateway tools, the Gateway write setting, matching profile grants, and target scopes still apply in both chat modes. This includes writes that run Named Queries, change live tag values, or acknowledge alarms. Native inspection and basic UDT creation use the signed-in Designer user's Ignition permissions. To limit that UDT path to read-only use, restrict the user's native tag editing permissions. Direct bundle writes also require **Direct project changes**. Stored plans are scoped to the Designer session and project, expire in ten minutes, and check for changed resources before applying. Grant preview, status and apply tools plus the relevant resource paths when using stored plans.

See [Tool Reference](TOOL_REFERENCE.md) for the complete catalog and [Change Workflows](CHANGE_WORKFLOWS.md) for resource and bundle behavior.

## Model setup

| Provider | Configuration |
| --- | --- |
| **Codex CLI** | Use the workstation's Codex sign-in. A trusted Codex profile can supply your organization's model provider and authentication configuration. |
| **Claude Code CLI** | Use the workstation's unmodified Claude Code installation with its own sign-in or supported provider configuration. Use a CLI release that supports `--safe-mode`. |
| **Kiro CLI** | Use the workstation's Kiro sign-in or its configured API key. Use a CLI release with V2 and `stream-json` support. |
| **OpenAI / compatible API** | Use `https://api.openai.com/v1/chat/completions` or a compatible endpoint, a model ID available to your API project, and its API credential. |
| **Anthropic API** | Use `https://api.anthropic.com/v1/messages` or a compatible endpoint, an Anthropic model ID available to your API account, and its API key. |
| **Azure OpenAI / Foundry (API key)** | Set the resource host, deployment name, and resource API key. |
| **Azure OpenAI / Foundry (Entra ID)** | Run `az login` on the workstation with an account granted inference access to the resource. Set the resource host and deployment name. Designer requests a token from Azure CLI for each model call. |
| **Google Gemini** | Use the prefilled Chat Completions URL, your Gemini model ID, and a Gemini API key. |
| **Ollama (local)** | Start Ollama on the workstation and enter a model shown by `ollama list`. The local URL is prefilled and normally requires no credential. |
| **Amazon Bedrock (API key)** | Set your AWS region in the URL, enter a model supported by Bedrock Chat Completions there, and supply a Bedrock API key. |

Open **Model settings** in the Designer Assistant dock and select your provider. For a CLI, verify authentication in your terminal with `codex login status`, `claude auth status`, or `kiro-cli whoami`. Leave **Model** and **CLI executable** blank to use the defaults. If Designer cannot find the CLI, use **Browse…** to select its executable. Restart the Designer Launcher to pick up a Windows PATH change. On Windows, `where.exe codex.exe`, `where.exe claude.exe`, and `where.exe kiro-cli.exe` report matching executables on PATH.

For a direct API, enter your **Model** and put the API key in **Model credential**. Leave **Credential header** blank for the provider default. A corporate proxy can use a custom header. Click **Test connection**, then **OK** to use the selected provider. The connection check sends a synthetic prompt without project data.

In Designer Assistant 0.4.8 development builds, a CLI provider shows its detected executable and discovery source. Open **Advanced** to choose another installation, browse for a managed executable, or select **Reset to auto-detect**. **Test connection** reports the launch, sign-in, and model reply separately. **Copy diagnostics** includes the CLI version and any detected error category. Use **Check Gateway tools** to see whether the signed-in Designer user has any Gateway tool grants.

Claude Code handles its own authentication, including any organization sign-in flow. If your workstation configuration relies on environment variables, make them available to the Designer process. [Claude Code CLI reference](https://code.claude.com/docs/en/cli-reference), [Claude Code authentication and distribution terms](https://code.claude.com/docs/en/legal-and-compliance)

Each provider remembers its URL, model or deployment, and applicable executable. Pasted keys stay in memory while Designer runs. A saved `env:VARIABLE_NAME` reference reads the credential from the Designer process environment. Use **OpenAI / compatible API** for a custom Chat Completions endpoint and authentication header. The chosen provider receives the project context needed for the conversation, including the contents of resources the assistant inspects.

The CLI adapters use short-lived calls. Designer Assistant manages the conversation, project tools, and review behavior for all providers. Kiro runs a temporary agent with an empty tool list and removes the local Kiro session after the call when the CLI is available. [Kiro headless mode](https://kiro.dev/docs/cli/headless/), [Kiro session management](https://kiro.dev/docs/cli/chat/session-management/)

## Troubleshooting

| Symptom | Check |
| --- | --- |
| The dock is missing | Verify both modules are active on the same 8.3.9 Gateway. Complete any pending restart, reopen Designer, and check **View → Panels**. |
| The connection test fails | For a CLI, check sign-in in a terminal on the Designer workstation. Use **Browse…** if lookup fails. For HTTP, verify the full endpoint, model or deployment, and credential. |
| Chat works but Gateway actions are unavailable | Check the selected Gateway, the saved `designer:<username>` profile, and its tool and target grants. Model connection and Gateway authorization are separate. |
| A proposal cannot be applied | Check the Gateway write mode, target grants, and any unsaved Designer edits. If a stored plan expired, ask for a fresh preview. |
| An applied project change is missing from the browser | Check for an Ignition conflict dialog. If the automatic update could not start, use **File → Update Project**. |

## Build and verify

See [Publishing signed modules](RELEASING.md) for development signing, release verification, and distribution.

Use JDK 17 and the 8.3.9 Ignition SDK. Gradle accepts an existing SDK tree with `-PassistantSdkDir=/path/to/sdk` containing `core/designer`, `core/client`, and `core/common`.

```sh
./gradlew test :designer-assistant:zipModule verifyModulePackage
node --test gateway/src/test/js/mcp-admin.test.cjs
./scripts/sign-module.sh
MODULE_IN="$PWD/designer-assistant/build/ignition-designer-assistant.unsigned.modl" \
MODULE_OUT="$PWD/designer-assistant/build/ignition-designer-assistant.modl" \
./scripts/sign-module.sh
```

The tests exercise the Gateway session and profile controls, catalog coverage, script review and auto-apply, batch dry-run enforcement, and the existing Designer keyboard and UDT workflows. To test end to end, use an isolated 8.3.9 Gateway, sign in to its Designer, and create a test script and Perspective view. Confirm each review-mode resource is absent before approval and appears after Gateway readback. Repeat with a different test path in auto-apply mode.

For the optional Gateway harness for simple UDTs, use a private JSON file with `gateway`, `project`, and `token`, a loopback 8.3.9 test Gateway with a project prefixed `DesignerAssistantQA`, and a compatible test model on port 8103. Seed the test folders `[default]_types_/Packaging/Line 1` and `[default]_types_/Utilities/Cooling`. Give the test token read access to the fixture's project resources and tag folders, plus tag-definition write access to those folders.

```sh
DESIGNER_ASSISTANT_QA_CONFIG=/private/path/credentials.json \
  ./gradlew :designer-assistant:test --tests '*.GatewayAssistantTest'
```
