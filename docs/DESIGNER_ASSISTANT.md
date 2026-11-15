# Designer Assistant 0.4.2

Designer Assistant is a separate module for **Ignition 8.3.9**. It uses the signed-in Designer session to reach the Ignition MCP Gateway module. The chat discovers the Gateway tool catalog, so scripts, Named Queries, Perspective, project resources, tags, UDTs, alarm pipelines, and available Gateway diagnostics use the same implementations and authorization rules as MCP clients.

## Install and use

1. Install signed **Ignition MCP 0.5.1** and **Designer Assistant 0.4.2** on the Gateway. Restart the Gateway if the module list shows **Pending restart**, then reopen Designer.
2. In **Services → Ignition MCP → Configuration → Access & writes**, add an authorization profile with an identity pattern such as `designer:jane`, using the username from Jane's Designer sign-in. Grant the tools and targets the user should be able to access. Enable Gateway writes if the user needs to apply changes. **Full access** is available for a trusted account on an isolated test Gateway. Keep production grants scoped to the user's work. Existing API token profiles continue to apply to API clients.
3. In Designer, open your project and find **View → Panels → Designer Assistant**. Open **Model settings** in the header to configure your provider. Press **Enter** to send and **Shift+Enter** for a new line.
4. Ask the assistant to inspect an existing resource or give it a specific change and destination. For example, “Create a Project Library script at Utilities/MemoryTags that defines a function for creating two memory tags named test and test1. Save the script without running the function.”
5. With **Review** selected beside the composer, choose **Details** to see Python, SQL, component summaries, and resource changes when available. Select **Approve** when the proposed change is ready. You can dismiss a proposal or send a revision. **Auto-apply** applies permitted changes within the current chat. New chats start in review mode.

Use **Attach** to select a project export ZIP or portable JSON resource bundle, then ask what it contains or where to import its resources. Export requests produce a **Save export…** button. The Gateway's existing size, format, conflict, and completeness checks apply.

The active project is taken from the authenticated Designer session. Open another project to work on its resources. Native inspection can see unsaved Designer state. Gateway changes persist directly and check for unsaved edits to their targets. After a successful project change, the assistant triggers Designer’s **Update Project** command. Concurrent edits use Designer’s standard conflict dialog. If the update cannot start, the chat points you to **File → Update Project**.

Gateway permissions still control committing changes, running Named Queries, writing live tag values, and acknowledging alarms. Existing simple UDT creation continues to use Designer's native tag permissions. Direct bundle writes require the Gateway's **Direct project changes** setting. Stored plans are scoped to the Designer session and project, expire in ten minutes, and check for changed resources before applying. Scoped profiles for stored plans should grant preview, status and apply plus the relevant resource paths.

See [Tool Reference](TOOL_REFERENCE.md) for the complete catalog and [Change Workflows](CHANGE_WORKFLOWS.md) for resource and bundle behavior.

## Model setup

| Provider | Configuration |
| --- | --- |
| **Installed Codex CLI** | Use the CLI's existing sign-in on the workstation. Set its absolute executable path if Designer cannot find it. A trusted local profile can supply your organization's model provider and authentication configuration. |
| **OpenAI-compatible HTTP** | Enter a full Chat Completions URL, model ID, and the applicable API credential. This includes compatible corporate proxies and local services. |
| **Anthropic Messages** | Enter the Messages API URL, model ID, and the applicable API credential. |

For a live model using an existing Codex sign-in, run `codex login status` on the Designer workstation. In the dock, open **Settings**, choose **Codex CLI (existing sign-in)**, and leave **Model** blank to use the CLI default. Leave **Trusted Codex profile** blank unless your organization uses a named profile. Set **Codex executable** to the absolute path reported by `command -v codex` if needed. Click **Test connection**, then **OK**. The check sends a synthetic prompt without project data.

For a direct OpenAI API connection, select **OpenAI-compatible HTTP**, set **Model URL** to `https://api.openai.com/v1/chat/completions`, and enter a Chat Completions model your API project can access, such as `gpt-5.4-mini`. Put the API key in **Model credential** and leave **Credential header** blank. Click **Test connection** and then **OK**.

The local QA model `designer-qa` on port 8103 is a scripted fixture. It drives predictable project reads and creates for tests. General questions can receive a canned fixture reply. Select a live provider to test free-form responses.

For an HTTP provider, a credential can be a bearer value, a value in a custom header, or an `env:VARIABLE_NAME` reference. Using a local or corporate model service that needs no credential is also supported. Settings persist the provider preferences locally. Credentials entered in the dialog stay in memory. The provider receives the project context needed for the conversation, including the contents of resources the assistant inspects, so use a provider appropriate for that project.

The installed CLI adapter uses ephemeral calls with the Designer Assistant managing the conversation and Ignition actions. The HTTP and CLI paths use the same instructions and review behavior. This version includes links to the official Ignition 8.3 manuals for tags, UDTs, scripting, named queries, and Perspective in the model context.

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
