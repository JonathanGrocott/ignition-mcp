package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.jg.ignition.assistant.AssistantJson.JSON;

/** Returns model text to the caller. None of these providers receives an Ignition tool or credential. */
final class ModelProvider {
    enum Kind {
        CODEX_CLI("Codex CLI", "codex", ""),
        CLAUDE_CLI("Claude Code CLI", "claude", ""),
        KIRO_CLI("Kiro CLI", "kiro-cli", ""),
        OPENAI_COMPATIBLE("OpenAI / compatible API", "", "https://api.openai.com/v1/chat/completions"),
        ANTHROPIC("Anthropic API", "", "https://api.anthropic.com/v1/messages"),
        AZURE_OPENAI("Azure OpenAI / Foundry (API key)", "", azureEndpoint()),
        AZURE_ENTRA("Azure OpenAI / Foundry (Entra ID)", "az", azureEndpoint()),
        GEMINI("Google Gemini", "", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
        OLLAMA("Ollama (local)", "", "http://127.0.0.1:11434/v1/chat/completions"),
        BEDROCK("Amazon Bedrock (API key)", "", "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1/chat/completions");

        final String label;
        final String command;
        final String defaultEndpoint;

        Kind(String label, String command, String defaultEndpoint) {
            this.label = label;
            this.command = command;
            this.defaultEndpoint = defaultEndpoint;
        }

        private static String azureEndpoint() { return "https://YOUR-RESOURCE.openai.azure.com/openai/v1/chat/completions"; }

        boolean isCli() { return defaultEndpoint.isEmpty(); }
        boolean usesExecutable() { return !command.isEmpty(); }
        String cliName() { return this == AZURE_ENTRA ? "Azure CLI" : label; }
    }

    record Settings(Kind kind, String endpoint, String model, String credential, String credentialHeader,
                    String cliExecutable, String codexProfile) {
        // A Settings instance can carry a provider credential.
        @Override
        public String toString() { return "ModelProvider.Settings[" + kind + "]"; }

        String displayName() {
            if (model.isBlank()) {
                return kind.label;
            }
            return kind.isCli() ? kind.label + " · " + model : model;
        }

        String connectionDescription() {
            if (kind.isCli()) {
                return "Uses the installed " + kind.cliName()
                    + (kind == Kind.CODEX_CLI && !codexProfile.isBlank() ? " with profile " + codexProfile + "." : ".");
            }
            try {
                var url = HttpJson.endpoint(endpoint);
                return displayName() + " at " + url.getHost() + (url.getPort() < 0 ? "" : ":" + url.getPort());
            } catch (IOException e) {
                return "Model URL needs configuration.";
            }
        }
    }

    record Message(String role, String text) {
        Message {
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException("Unsupported chat role.");
            }
            Objects.requireNonNull(text);
        }
    }

    private static final int HTTP_TIMEOUT_SECONDS = 90;
    private static final int CLI_TIMEOUT_SECONDS = 150;
    private static final int MAX_CLI_OUTPUT = 1_048_576;
    static final int MAX_PROMPT = 131_072;
    private static final int MAX_REPLY = 32_768;
    private static final Pattern ENV_CREDENTIAL = Pattern.compile("env:([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern KIRO_SESSION_ID = Pattern.compile("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern MCP_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");
    private static final List<String> CLI_RESTRICTIONS = List.of(
        "-c", "approval_policy=\"never\"",
        "-c", "web_search=\"disabled\"",
        "-c", "shell_environment_policy.inherit=\"none\"",
        "--disable", "shell_tool", "--disable", "hooks", "--disable", "plugins",
        "--disable", "apps", "--disable", "memories", "--disable", "multi_agent", "--disable", "multi_agent_v2",
        "--disable", "goals", "--disable", "view_image", "--disable", "image_generation"
    );

    private ModelProvider() {}

    static void checkConnection(Settings settings) throws IOException, InterruptedException {
        String reply = complete(settings, List.of(new Message("user", "Run the model connection check.")),
            "This is a connection check. Reply with exactly DESIGNER_READY and no other text.");
        if (!"DESIGNER_READY".equals(reply)) {
            throw new IOException("The provider responded, but the reply did not match the connection check.");
        }
    }

    static String complete(Settings settings, List<Message> history, String system)
        throws IOException, InterruptedException {
        if (history.isEmpty()) {
            throw new IOException("Enter a chat message.");
        }
        if (system.length() + history.stream().mapToLong(m -> m.text().length()).sum() > MAX_PROMPT) {
            throw new IOException("Conversation is too long. Start a new chat.");
        }
        String response = switch (settings.kind()) {
            case CODEX_CLI -> codex(settings, history, system);
            case CLAUDE_CLI -> claude(settings, history, system);
            case KIRO_CLI -> kiro(settings, history, system);
            default -> http(settings, history, system);
        };
        if (response.isBlank() || response.length() > MAX_REPLY) {
            throw new IOException("The model returned an empty or oversized reply.");
        }
        return response.strip();
    }

    private static String http(Settings settings, List<Message> history, String system)
        throws IOException, InterruptedException {
        boolean anthropic = settings.kind() == Kind.ANTHROPIC;
        if (settings.model().isBlank()) {
            throw new IOException("Enter the provider's model ID in Settings.");
        }
        ObjectNode data = JSON.createObjectNode().put("model", settings.model().strip()).put("stream", false);
        ArrayNode messages = data.putArray("messages");
        if (anthropic) {
            data.put("system", system).put("max_tokens", 4096);
        } else {
            messages.addObject().put("role", "system").put("content", system);
        }
        for (Message message : history) {
            messages.addObject().put("role", message.role()).put("content", message.text());
        }
        URI endpoint = HttpJson.endpoint(settings.endpoint());
        HttpRequest.Builder request = HttpJson.post(endpoint, data, HTTP_TIMEOUT_SECONDS);
        if (anthropic) {
            request.header("anthropic-version", "2023-06-01");
        }
        authenticate(request, settings, endpoint);
        HttpJson.Response response = HttpJson.send(request.build(), HTTP_TIMEOUT_SECONDS);
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Model provider returned HTTP " + response.status()
                + ". Check the endpoint, model ID, and authentication.");
        }
        JsonNode result = response.json();
        if (result == null) {
            throw new IOException("Model provider returned an empty response.");
        }
        if (!anthropic) {
            return result.path("choices").path(0).path("message").path("content").asText("");
        }
        StringBuilder resultText = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                resultText.append(block.path("text").asText(""));
            }
        }
        return resultText.toString();
    }

    static boolean environmentReference(String value) {
        return ENV_CREDENTIAL.matcher(value).matches();
    }

    private static void authenticate(HttpRequest.Builder request, Settings settings, URI endpoint)
        throws IOException, InterruptedException {
        if (settings.kind() == Kind.AZURE_ENTRA) {
            request.header("Authorization", "Bearer " + azureToken(settings.cliExecutable(), azureTokenScope(endpoint)));
            return;
        }
        if (settings.kind() == Kind.AZURE_OPENAI) {
            azureTokenScope(endpoint);
        }
        if (settings.credential().isBlank()) {
            return;
        }
        String key = settings.credential().strip();
        if (key.startsWith("env:")) {
            var reference = ENV_CREDENTIAL.matcher(key);
            if (!reference.matches()) {
                throw new IOException("Use env:VARIABLE_NAME for an environment credential.");
            }
            String variable = reference.group(1);
            key = System.getenv(variable);
            if (key == null || key.isBlank()) {
                throw new IOException("Credential environment variable " + variable + " is unavailable to Designer.");
            }
        }
        String header = settings.credentialHeader().isBlank() ? switch (settings.kind()) {
            case ANTHROPIC -> "x-api-key";
            case AZURE_OPENAI -> "api-key";
            default -> "Authorization";
        } : settings.credentialHeader().strip();
        if ("authorization".equalsIgnoreCase(header) && !key.strip().contains(" ")) {
            key = "Bearer " + key;
        }
        try {
            request.header(header, key);
        } catch (IllegalArgumentException e) {
            // HttpRequest's error can include the credential.
            throw new IOException("Invalid model credential or credential header.");
        }
    }

    static String azureTokenScope(URI endpoint) throws IOException {
        String host = Objects.toString(endpoint.getHost(), "").toLowerCase(Locale.ROOT);
        if ("https".equalsIgnoreCase(endpoint.getScheme()) && "/openai/v1/chat/completions".equals(endpoint.getPath())
                && !host.startsWith("your-resource.") && (endpoint.getPort() == -1 || endpoint.getPort() == 443)) {
            if (host.endsWith(".openai.azure.com")) {
                return "https://cognitiveservices.azure.com/.default";
            }
            if (host.endsWith(".services.ai.azure.com")) {
                return "https://ai.azure.com/.default";
            }
        }
        throw new IOException("Enter your Azure OpenAI or Foundry HTTPS resource URL ending in /openai/v1/chat/completions.");
    }

    static String azureToken(String configured, String scope) throws IOException, InterruptedException {
        String az = CliExecutable.resolve(configured, Kind.AZURE_ENTRA.command);
        var command = new ArrayList<String>();
        if (CliExecutable.windows() && az.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            // Microsoft's Windows distribution invokes its bundled Python through az.cmd.
            var python = Path.of(az).toAbsolutePath().resolveSibling("../python.exe").normalize();
            if (!Files.isRegularFile(python)) {
                throw new IOException("Select az.cmd from the installed Microsoft Azure CLI.");
            }
            command.addAll(List.of(python.toString(), "-IBm", "azure.cli"));
        } else {
            command.add(az);
        }
        command.addAll(List.of("account", "get-access-token", "--scope", scope, "--query", "accessToken",
            "--output", "tsv", "--only-show-errors"));
        Path workspace = Files.createTempDirectory("ignition-assistant-azure-");
        try {
            String result = run(Kind.AZURE_ENTRA, command, workspace, "", 25, "get an Entra access token").strip();
            if (!result.matches("[A-Za-z0-9._~+/=-]{1,32768}")) {
                throw new IOException("Azure CLI did not return an access token. Run az login in a terminal.");
            }
            return result;
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private static String codex(Settings settings, List<Message> history, String system)
        throws IOException, InterruptedException {
        String executable = CliExecutable.resolve(settings.cliExecutable(), Kind.CODEX_CLI.command);
        String profile = settings.codexProfile().strip();
        if (!profile.isEmpty() && !PROFILE_NAME.matcher(profile).matches()) {
            throw new IOException("Enter a Codex profile name, not a path.");
        }

        Path workspace = Files.createTempDirectory("ignition-assistant-codex-");
        try {
            List<String> base = new ArrayList<>();
            base.add(executable);
            base.addAll(CLI_RESTRICTIONS);
            if (!profile.isEmpty()) {
                base.addAll(List.of("--profile", profile));
            }
            if (!profile.isEmpty()) {
                disableMcp(base, workspace);
            }
            List<String> command = new ArrayList<>(base);
            command.addAll(List.of("exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check",
                "--color", "never", "--json"));
            if (profile.isEmpty()) {
                command.add("--ignore-user-config");
            }
            if (!settings.model().isBlank()) {
                command.addAll(List.of("--model", settings.model().strip()));
            }
            command.add("-");

            String events = run(Kind.CODEX_CLI, command, workspace, cliPrompt(history, system), CLI_TIMEOUT_SECONDS, "run");
            String answer = "";
            for (String line : events.lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event = JSON.readTree(line);
                JsonNode item = event.path("item");
                if (!event.isObject()) {
                    throw new IOException("Unexpected Codex CLI event.");
                }
                if ("item.completed".equals(event.path("type").asText())
                        && "agent_message".equals(item.path("type").asText())) {
                    answer = item.path("text").asText("");
                }
            }
            return answer;
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private static String claude(Settings settings, List<Message> history, String system)
        throws IOException, InterruptedException {
        Path workspace = Files.createTempDirectory("ignition-assistant-claude-");
        try {
            // Claude authenticates locally. Designer retains all project reads, proposals, and writes.
            var command = new ArrayList<>(List.of(CliExecutable.resolve(settings.cliExecutable(), Kind.CLAUDE_CLI.command),
                "--safe-mode", "--print", "--output-format", "json", "--no-session-persistence",
                "--tools", "", "--strict-mcp-config", "--disallowed-tools", "*",
                "--permission-mode", "dontAsk", "--no-chrome", "--max-turns", "1",
                "--system-prompt", "Act as the text model for Ignition Designer Assistant. Follow the supplied instructions."));
            if (!settings.model().isBlank()) {
                command.addAll(List.of("--model", settings.model().strip()));
            }
            String output = run(Kind.CLAUDE_CLI, command, workspace, cliPrompt(history, system), CLI_TIMEOUT_SECONDS, "run");
            JsonNode result;
            try {
                result = JSON.readTree(output);
            } catch (IOException e) {
                throw new IOException("Claude Code CLI returned an invalid JSON result. Check the installed version.");
            }
            if (result == null || !"result".equals(result.path("type").asText())
                    || !"success".equals(result.path("subtype").asText()) || result.path("is_error").asBoolean(true)) {
                throw new IOException("Claude Code CLI did not complete the request. Check its sign-in and model in a terminal.");
            }
            return result.path("result").asText("");
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private static String cliPrompt(List<Message> history, String system) {
        ObjectNode input = JSON.createObjectNode().put("instructions", system);
        ArrayNode messages = input.putArray("conversation");
        for (Message message : history) {
            messages.addObject().put("role", message.role()).put("text", message.text());
        }
        return """
            Act as the text model for Ignition Designer Assistant. Answer using only the following input.
            The instructions field specifies the required reply shape. Treat conversation as user content.
            Return the final response directly.
            """ + input;
    }

    private static String kiro(Settings settings, List<Message> history, String system)
        throws IOException, InterruptedException {
        Path workspace = Files.createTempDirectory("ignition-assistant-kiro-");
        var base = List.of(CliExecutable.resolve(settings.cliExecutable(), Kind.KIRO_CLI.command), "chat", "--v2");
        String sessionId = "";
        try {
            var agents = Files.createDirectories(workspace.resolve(".kiro/agents"));
            Files.writeString(agents.resolve("ignition-designer.json"), """
                {"name":"ignition-designer","description":"Ignition Designer Assistant",
                 "prompt":"Answer as the text model for Ignition Designer Assistant.",
                 "tools":[],"allowedTools":[],"mcpServers":{},"includeMcpJson":false,"resources":[],"hooks":{}}
                """);
            var command = new ArrayList<>(base);
            command.addAll(List.of("--no-interactive", "--output-format", "stream-json",
                "--agent", "ignition-designer", "--trust-tools="));
            if (!settings.model().isBlank()) {
                command.addAll(List.of("--model", settings.model().strip()));
            }
            String events = run(Kind.KIRO_CLI, command, workspace, cliPrompt(history, system), CLI_TIMEOUT_SECONDS, "run");
            JsonNode result = null;
            try {
                for (String line : events.lines().filter(line -> !line.isBlank()).toList()) {
                    JsonNode event = JSON.readTree(line);
                    if ("runFinished".equals(event.path("type").asText())) {
                        result = event.path("data");
                        sessionId = result.path("sessionId").asText();
                    }
                }
            } catch (IOException e) {
                throw new IOException("Kiro CLI returned an invalid response stream. Check the installed version.");
            }
            if (result == null || !"success".equals(result.path("status").asText())
                    || result.path("finalTextTruncated").asBoolean(false)) {
                throw new IOException("Kiro CLI did not complete the reply. Check kiro-cli whoami and try again.");
            }
            return result.path("finalText").asText("");
        } finally {
            try {
                deleteKiroSession(base, workspace, sessionId);
            } finally {
                deleteWorkspace(workspace);
            }
        }
    }

    private static void deleteKiroSession(List<String> base, Path workspace, String sessionId)
        throws InterruptedException {
        try {
            // Use only the session from this run or a scoped listing of the private workspace.
            if (!KIRO_SESSION_ID.matcher(sessionId).matches()) {
                var list = new ArrayList<>(base);
                list.addAll(List.of("--list-sessions", "--format", "json"));
                JsonNode workspaces = JSON.readTree(run(Kind.KIRO_CLI, list, workspace, "", 5, "list temporary sessions"));
                String path = workspace.toRealPath().toString();
                if (workspaces != null && workspaces.isArray() && workspaces.size() == 1
                        && path.equals(workspaces.get(0).path("cwd").asText())) {
                    var sessions = workspaces.get(0).path("sessions");
                    if (sessions.isArray() && sessions.size() == 1) {
                        sessionId = sessions.get(0).path("sessionId").asText();
                    }
                }
            }
            if (KIRO_SESSION_ID.matcher(sessionId).matches()) {
                var delete = new ArrayList<>(base);
                delete.addAll(List.of("--delete-session", sessionId, "--session-source", "v2"));
                run(Kind.KIRO_CLI, delete, workspace, "", 5, "remove the temporary session");
            }
        } catch (IOException ignored) {
            // Kiro may retain a session if the CLI cannot complete its cleanup command.
        }
    }

    private static void disableMcp(List<String> base, Path workspace) throws IOException, InterruptedException {
        JsonNode first = mcpCatalog(base, workspace);
        List<String> disabled = new ArrayList<>();
        for (JsonNode server : first) {
            String name = server.path("name").asText("");
            if (!MCP_NAME.matcher(name).matches()) {
                throw new IOException("Codex has an unsupported MCP server name. Use a profile with no enabled MCP servers.");
            }
            disabled.add("\"" + name + "\"={enabled=false}");
        }
        if (disabled.isEmpty()) {
            return;
        }
        base.addAll(List.of("-c", "mcp_servers={" + String.join(",", disabled) + "}"));
        for (JsonNode server : mcpCatalog(base, workspace)) {
            if (server.path("enabled").asBoolean(true)) {
                throw new IOException("A Codex MCP server is still enabled. Check managed CLI settings.");
            }
        }
    }

    private static JsonNode mcpCatalog(List<String> base, Path workspace) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(base);
        command.addAll(List.of("mcp", "list", "--json"));
        JsonNode catalog = JSON.readTree(run(Kind.CODEX_CLI, command, workspace, "", 15, "inspect MCP settings"));
        if (catalog == null || !catalog.isArray() || catalog.size() > 128) {
            throw new IOException("Unexpected Codex MCP catalog. Check the installed CLI version.");
        }
        return catalog;
    }

    private static String run(Kind kind, List<String> command, Path directory, String input, int seconds, String operation)
        throws IOException, InterruptedException {
        String cli = kind.cliName();
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(ModelProvider::ignitionSecret);
        if (kind == Kind.KIRO_CLI) {
            builder.environment().put("KIRO_LOG_NO_COLOR", "1");
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Could not start " + cli + ". Set its executable path in Settings.");
        }
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] data = stream.readNBytes(MAX_CLI_OUTPUT + 1);
                if (data.length > MAX_CLI_OUTPUT) {
                    stop(process);
                    throw new IOException(cli + " produced too much output.");
                }
                return data;
            }
        });
        FutureTask<Void> writer = new FutureTask<>(() -> {
            try (var stream = process.getOutputStream()) {
                stream.write(input.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        startWorker(output, cli + " response reader");
        startWorker(writer, cli + " prompt writer");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                throw new IOException(cli + " timed out during " + operation + ".");
            }
            long remaining = Math.max(1, deadline - System.nanoTime());
            byte[] data = output.get(remaining, TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) {
                String hint = switch (kind) {
                    case CLAUDE_CLI -> "Check its sign-in and model in a terminal and use a version with --safe-mode.";
                    case KIRO_CLI -> "Check kiro-cli whoami and use a version with V2 stream-json support.";
                    case AZURE_ENTRA -> "Sign in with az login and check access to your Azure deployment.";
                    default -> "Check CLI login, profile, and version in a terminal.";
                };
                throw new IOException(cli + " could not " + operation + ". " + hint);
            }
            writer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return new String(data, StandardCharsets.UTF_8);
        } catch (ExecutionException e) {
            throw new IOException("Could not complete communication with " + cli + ".");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException(cli + " timed out during " + operation + ".");
        } finally {
            if (process.isAlive()) {
                stop(process);
            }
            output.cancel(true);
            writer.cancel(true);
        }
    }

    private static boolean ignitionSecret(String key) {
        String uppercase = key.toUpperCase(Locale.ROOT);
        return uppercase.startsWith("IGNITION_") || uppercase.startsWith("MCP_");
    }

    private static void startWorker(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void deleteWorkspace(Path workspace) {
        try {
            // walkFileTree does not follow symlinks out of this private directory.
            Files.walkFileTree(workspace, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes)
                    throws IOException {
                    Files.deleteIfExists(path);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(path);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A failed cleanup should not discard an otherwise successful response.
        }
    }
}
