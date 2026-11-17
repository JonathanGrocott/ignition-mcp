# Designer Assistant 0.4.7

Designer Assistant is a separate module for **Ignition 8.3.9**. It uses the signed-in Designer session to reach the Ignition MCP Gateway module. The chat discovers the Gateway tool catalog, so scripts, Named Queries, Perspective, project resources, tags, UDTs, alarm pipelines, and available Gateway diagnostics use the same implementations and authorization rules as MCP clients.

## Install and use

1. Install signed **Ignition MCP 0.5.1** and **Designer Assistant 0.4.7** on the Gateway. Restart the Gateway if the module list shows **Pending restart**, then reopen Designer.
2. In **Services → Ignition MCP → Configuration → Access & writes**, add an authorization profile with an identity pattern such as `designer:jane`, using the username from Jane's Designer sign-in. Grant the tools and targets the user should be able to access. Enable Gateway writes if the user needs to apply changes. **Full access** is available for a trusted account on an isolated test Gateway. Keep production grants scoped to the user's work. Existing API token profiles continue to apply to API clients.
3. In Designer, open your project and find **View → Panels → Designer Assistant**. Open **Model settings** in the header to configure your provider. Press **Enter** to send and **Shift+Enter** for a new line. Drag the handle above the message box to resize it.
4. Ask the assistant to inspect an existing resource or give it a specific change and destination. For example, “Create a Project Library script at Utilities/MemoryTags that defines a function for creating two memory tags named test and test1. Save the script without running the function.”
5. With **Review** selected beside the composer, choose **Details** to see Python, SQL, component summaries, and resource changes when available. Select **Approve** when the proposed change is ready. You can dismiss a proposal or send a revision. **Auto-apply** applies permitted changes within the current chat. New chats start in review mode.

Choose **New chat** to open a tab. Tabs take their name from the first message. Double-click a tab, use its **Rename chat…** menu, or press **F2** with the tab selected to give it a name. Switch between tabs to return to each conversation, draft, and pending proposal. Model settings are shared across tabs. Chats are kept while Designer is open.

Open a Perspective view, Project Library script, or Named Query and ask “What does this do?” The dock shows the active editor, and the assistant receives its exact path when you send the message. In Perspective, select a component and ask “What does this component do?” The assistant also receives the selected Project Browser resources and visible Tag Browser selections. It can inspect the referenced resource using the existing tools. Edits still pending inside an editor may be missing from resource reads.

Use **Attach** to select a JSON file up to 4 MiB or an Ignition project export ZIP up to 2 MiB. Ask what the file contains. Plain JSON can be inspected directly. For imports, use an Ignition ZIP or the supported resource bundle format. The input clears the attachment after sending. The most recent JSON file remains available for follow-up questions in that chat. Export requests produce a **Save export…** button.

For large results, the assistant can retrieve individual JSON sections or continue through a listing within the current turn. Saved model context and Gateway tool limits still apply. For a large edit, identify the exact resource and change so the assistant can inspect the relevant sections and preserve existing configuration.

The active project is taken from the authenticated Designer session. Open another project to work on its resources. Native inspection can see unsaved Designer state. Gateway changes persist directly and check for unsaved edits to their targets. After a successful project change, the assistant triggers Designer’s **Update Project** command. Concurrent edits use Designer’s standard conflict dialog. If the update cannot start, the chat points you to **File → Update Project**.

Gateway permissions still control committing changes, running Named Queries, writing live tag values, and acknowledging alarms. Existing simple UDT creation continues to use Designer's native tag permissions. Direct bundle writes require the Gateway's **Direct project changes** setting. Stored plans are scoped to the Designer session and project, expire in ten minutes, and check for changed resources before applying. Scoped profiles for stored plans should grant preview, status and apply plus the relevant resource paths.

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

Claude Code handles its own authentication, including any organization sign-in flow. If your workstation configuration relies on environment variables, make them available to the Designer process. [Claude Code CLI reference](https://code.claude.com/docs/en/cli-reference), [Claude Code authentication and distribution terms](https://code.claude.com/docs/en/legal-and-compliance)

Each provider remembers its URL, model or deployment, and applicable executable. Pasted keys stay in memory while Designer runs. A saved `env:VARIABLE_NAME` reference reads the credential from the Designer process environment. Use **OpenAI / compatible API** for a custom Chat Completions endpoint and authentication header. The chosen provider receives the project context needed for the conversation, including the contents of resources the assistant inspects.

The CLI adapters use short-lived calls. Designer Assistant manages the conversation, project tools, and review behavior for all providers. Kiro runs a temporary agent with an empty tool list and removes the local Kiro session after the call when the CLI is available. [Kiro headless mode](https://kiro.dev/docs/cli/headless/), [Kiro session management](https://kiro.dev/docs/cli/chat/session-management/)

## Build and verify

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
