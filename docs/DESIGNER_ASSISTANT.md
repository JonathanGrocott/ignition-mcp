# Designer Assistant 0.3.0

Designer Assistant is a separate module for **Ignition 8.3.9**. The chat can inspect the open project, browse its tag providers and UDT definitions, read tag values and quality, and inspect Perspective, project scripts, and named-query resources. It can create a UDT definition with memory members in an existing folder you choose.

## Install and use

1. Install the signed `ignition-designer-assistant.modl` in the Gateway and reopen Designer.
2. Open a project and find **View → Panels → Designer Assistant**. Set up your provider in **Settings**. The dock uses the current authenticated Designer connection for project and tag operations.
3. Ask “What scripts, tags, and UDTs are in this project?” or “Read the script at Utilities/Formatting.” The assistant can make several read calls during a turn.
4. Ask “Create a Motor UDT in Packaging/Line 1 with a Boolean Running member.” Give a provider if the project has no default or you want a different provider. The folder is under **UDT Definitions**. For the definition root, say so explicitly.
5. With **Ask before changes**, review the destination and member list in the chat, then choose **Approve creation**, dismiss it, or send a revision. With **Auto-apply**, the assistant immediately attempts eligible UDT creations in this chat. New chats start in review mode.
6. The assistant checks the parent folder and uses Ignition's abort-on-collision policy. The receipt reports success after native readback confirms the type and member configuration. Use **Show in Tag Browser** to locate the definition.

The same native operations use the signed-in Designer's project and tag access. The mode picker determines whether the assistant pauses for a review before invoking an eligible create. This version accepts simple UDT definitions with memory members and leaves existing definitions unchanged.

## Model setup

| Provider | Configuration |
| --- | --- |
| **Installed Codex CLI** | Use the CLI's existing sign-in on the workstation. Set its absolute executable path if Designer cannot find it. A trusted local profile can supply your organization's model provider and authentication configuration. |
| **OpenAI-compatible HTTP** | Enter a full Chat Completions URL, model ID, and the applicable API credential. This includes compatible corporate proxies and local services. |
| **Anthropic Messages** | Enter the Messages API URL, model ID, and the applicable API credential. |

For an HTTP provider, a credential can be a bearer value, a value in a custom header, or an `env:VARIABLE_NAME` reference. Using a local or corporate model service that needs no credential is also supported. Settings persist the provider preferences locally. Credentials entered in the dialog stay in memory. The provider receives the project context needed for the conversation, including the contents of resources the assistant inspects, so use a provider appropriate for that project.

The installed CLI adapter uses ephemeral calls with the Designer Assistant managing the conversation and native actions. The HTTP and CLI paths use the same instructions and review behavior. This version includes links to the official Ignition 8.3 manuals for tags, UDTs, scripting, named queries, and Perspective in the model context.

## Build and verify

Use JDK 17 and the 8.3.9 Ignition SDK. Gradle accepts an existing SDK tree with `-PassistantSdkDir=/path/to/sdk` containing `core/designer`, `core/client`, and `core/common`.

```sh
./gradlew test :designer-assistant:zipModule
MODULE_IN="$PWD/designer-assistant/build/ignition-designer-assistant.unsigned.modl" \
MODULE_OUT="$PWD/designer-assistant/build/ignition-designer-assistant.modl" \
./scripts/sign-module.sh
```

The signed module was verified in a native macOS Designer connected to a local Ignition 8.3.9 Gateway. A loopback model drove the assistant to inspect the open project's resources and UDTs. Review mode produced a draft in `Packaging/Line 1` and left it absent until approval. Auto-apply created a separate definition in `Utilities/Cooling`. The native adapter read back and checked both definitions. A Swing test exercises the conversation, approval, dismissal, and mode controls. The local integration harness can also test the same agent against the Gateway using the existing Ignition MCP module as an independent transport.

For the optional Gateway harness, use a private JSON file with `gateway`, `project`, and `token`, a loopback 8.3.9 test Gateway with a project prefixed `DesignerAssistantQA`, and a compatible test model on port 8103. Seed the test folders `[default]_types_/Packaging/Line 1` and `[default]_types_/Utilities/Cooling`. Give the test token read access to the fixture's project resources and tag folders, plus tag-definition write access to those folders.

```sh
DESIGNER_ASSISTANT_QA_CONFIG=/private/path/credentials.json \
  ./gradlew :designer-assistant:test --tests '*.GatewayAssistantTest'
```
