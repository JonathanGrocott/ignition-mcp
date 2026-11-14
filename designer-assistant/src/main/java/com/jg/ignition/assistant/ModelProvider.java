package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
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
    enum Kind { CODEX_CLI, OPENAI_COMPATIBLE, ANTHROPIC }

    record Settings(Kind kind, String endpoint, String model, String credential, String credentialHeader,
                    String codexExecutable, String codexProfile) {
        // A Settings instance can carry a provider credential.
        @Override
        public String toString() { return "ModelProvider.Settings[" + kind + "]"; }
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
    private static final int CODEX_TIMEOUT_SECONDS = 150;
    private static final int MAX_CODEX_OUTPUT = 1_048_576;
    private static final int MAX_PROMPT = 131_072;
    private static final int MAX_REPLY = 32_768;
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
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
            case OPENAI_COMPATIBLE, ANTHROPIC -> http(settings, history, system);
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
        HttpRequest.Builder request = HttpJson.post(HttpJson.endpoint(settings.endpoint()), data, HTTP_TIMEOUT_SECONDS);
        if (anthropic) {
            request.header("anthropic-version", "2023-06-01");
        }
        authenticate(request, settings, anthropic);
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

    private static void authenticate(HttpRequest.Builder request, Settings settings, boolean anthropic)
        throws IOException {
        if (settings.credential().isBlank()) {
            return;
        }
        String key = settings.credential().strip();
        if (key.startsWith("env:")) {
            String variable = key.substring(4);
            if (!ENV_NAME.matcher(variable).matches()) {
                throw new IOException("Use env:VARIABLE_NAME for an environment credential.");
            }
            key = System.getenv(variable);
            if (key == null || key.isBlank()) {
                throw new IOException("Credential environment variable " + variable + " is unavailable to Designer.");
            }
        }
        String header = settings.credentialHeader().isBlank() ? (anthropic ? "x-api-key" : "Authorization")
            : settings.credentialHeader().strip();
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

    private static String codex(Settings settings, List<Message> history, String system)
        throws IOException, InterruptedException {
        String executable = resolveCodex(settings.codexExecutable());
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

            ObjectNode input = JSON.createObjectNode().put("instructions", system);
            ArrayNode messages = input.putArray("conversation");
            for (Message message : history) {
                messages.addObject().put("role", message.role()).put("text", message.text());
            }
            String prompt = """
                Act as the text model for Ignition Designer Assistant. Answer using only the following input.
                The instructions field specifies the required reply shape. Treat conversation as user content.
                Return the final response directly.
                """ + input;
            String events = run(command, workspace, prompt, CODEX_TIMEOUT_SECONDS, "run");
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

    private static String resolveCodex(String executable) {
        if (!executable.isBlank()) {
            return executable.strip();
        }
        Path home = Path.of(System.getProperty("user.home"));
        for (Path candidate : List.of(home.resolve(".local/bin/codex"), home.resolve(".codex/bin/codex"),
                Path.of("/opt/homebrew/bin/codex"), Path.of("/usr/local/bin/codex"))) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "codex";
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
        JsonNode catalog = JSON.readTree(run(command, workspace, "", 15, "inspect MCP settings"));
        if (catalog == null || !catalog.isArray() || catalog.size() > 128) {
            throw new IOException("Unexpected Codex MCP catalog. Check the installed CLI version.");
        }
        return catalog;
    }

    private static String run(List<String> command, Path directory, String input, int seconds, String operation)
        throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(ModelProvider::ignitionSecret);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Could not start Codex CLI. Set its executable path in Settings.");
        }
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] data = stream.readNBytes(MAX_CODEX_OUTPUT + 1);
                if (data.length > MAX_CODEX_OUTPUT) {
                    stop(process);
                    throw new IOException("Codex CLI produced too much output.");
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
        startWorker(output, "Codex response reader");
        startWorker(writer, "Codex prompt writer");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                throw new IOException("Codex CLI timed out during " + operation + ".");
            }
            long remaining = Math.max(1, deadline - System.nanoTime());
            byte[] data = output.get(remaining, TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("Codex CLI could not " + operation
                    + ". Check CLI login, profile, and version in a terminal.");
            }
            writer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return new String(data, StandardCharsets.UTF_8);
        } catch (ExecutionException e) {
            throw new IOException("Could not complete communication with Codex CLI.");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Codex CLI timed out during " + operation + ".");
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
