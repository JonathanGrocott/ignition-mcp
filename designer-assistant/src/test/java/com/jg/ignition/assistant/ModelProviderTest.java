package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

class ModelProviderTest {
    private static final String SYSTEM = "Reply as JSON.";
    private static final List<ModelProvider.Message> HISTORY = List.of(
        new ModelProvider.Message("user", "Example request"),
        new ModelProvider.Message("assistant", "Example reply"),
        new ModelProvider.Message("user", "Next request")
    );

    @Test
    void openAiCompatibleSendsScopedCredentialAndHistory() throws Exception {
        try (MockEndpoint server = new MockEndpoint(
            "{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}", "x-company-key")) {
            var config = new ModelProvider.Settings(ModelProvider.Kind.OPENAI_COMPATIBLE,
                server.url(), "local-model", "test-secret", "x-company-key", "", "");
            assertEquals("answer", ModelProvider.complete(config, HISTORY, SYSTEM));
            assertEquals("test-secret", server.credential.get());
            assertEquals("local-model", server.data.get().path("model").asText());
            assertEquals(SYSTEM, server.data.get().at("/messages/0/content").asText());
            assertEquals("Next request", server.data.get().at("/messages/3/content").asText());
            assertFalse(server.data.get().has("tools"));
        }
    }

    @Test
    void anthropicSendsSystemSeparatelyAndReadsTextBlocks() throws Exception {
        try (MockEndpoint server = new MockEndpoint(
            "{\"content\":[{\"type\":\"thinking\"},{\"type\":\"text\",\"text\":\"answer\"}]}", "x-api-key")) {
            var config = new ModelProvider.Settings(ModelProvider.Kind.ANTHROPIC,
                server.url(), "claude-test", "test-secret", "", "", "");
            assertEquals("answer", ModelProvider.complete(config, HISTORY, SYSTEM));
            assertEquals("test-secret", server.credential.get());
            assertEquals("2023-06-01", server.anthropicVersion.get());
            assertEquals(SYSTEM, server.data.get().path("system").asText());
            assertEquals("user", server.data.get().at("/messages/0/role").asText());
            assertTrue(server.data.get().path("max_tokens").asInt() > 0);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void codexReadsStdinWithManagedProfileAndDisablesConfiguredMcp(@TempDir Path directory) throws Exception {
        Path executable = directory.resolve("codex");
        Files.writeString(executable, """
            #!/bin/sh
            case "$*" in
              *"mcp list --json"*)
                case "$*" in
                  *'"test.server"={enabled=false}'*) printf '[{"name":"test.server","enabled":false}]' ;;
                  *) printf '[{"name":"test.server","enabled":true}]' ;;
                esac
                exit 0 ;;
            esac
            case "$*" in
              *'--profile corp.example'*'mcp_servers={"test.server"={enabled=false}}'*'exec --sandbox read-only --ephemeral'*) ;;
              *) exit 10 ;;
            esac
            test -z "$(ls -A)" || exit 11
            prompt="$(cat)"
            case "$prompt" in
              *"Next request"*) ;;
              *) exit 12 ;;
            esac
            printf '%s\\n' '{"type":"item.completed","item":{"type":"agent_message","text":"answer"}}'
            """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var config = new ModelProvider.Settings(ModelProvider.Kind.CODEX_CLI,
            "", "model", "ignore-this-token", "", executable.toString(), "corp.example");
        assertEquals("answer", ModelProvider.complete(config, HISTORY, SYSTEM));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void codexDefaultSkipsUserConfigAndKeepsExistingCliAuth(@TempDir Path directory) throws Exception {
        Path executable = directory.resolve("codex");
        Files.writeString(executable, """
            #!/bin/sh
            case "$*" in
              *'exec --sandbox read-only --ephemeral'*'--ignore-user-config'*) ;;
              *) exit 10 ;;
            esac
            cat >/dev/null
            printf '%s\\n' '{"type":"item.completed","item":{"type":"agent_message","text":"answer"}}'
            """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var config = new ModelProvider.Settings(ModelProvider.Kind.CODEX_CLI,
            "", "", "", "", executable.toString(), "");
        assertEquals("answer", ModelProvider.complete(config, HISTORY, SYSTEM));
    }

    private static final class MockEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<JsonNode> data = new AtomicReference<>();
        private final AtomicReference<String> credential = new AtomicReference<>();
        private final AtomicReference<String> anthropicVersion = new AtomicReference<>();

        MockEndpoint(String reply, String header) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/provider", exchange -> {
                try {
                    data.set(JSON.readTree(exchange.getRequestBody()));
                    credential.set(exchange.getRequestHeaders().getFirst(header));
                    anthropicVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
                    byte[] content = reply.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/provider"; }

        @Override
        public void close() { server.stop(0); }
    }
}
