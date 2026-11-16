package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

class ToolResultPagesTest {
    @Test
    void readsTheTailOfALargeResultWithoutLosingItsJsonStructure() throws Exception {
        var full = JSON.createObjectNode();
        var items = full.putArray("tags/a~b");
        for (int i = 0; i < 600; i++) {
            items.addObject().put("path", "Factory/Equipment_" + i)
                .put("description", "Test configuration and metadata for a memory tag");
        }
        var buffer = new ToolResultPages();
        var initial = JSON.readTree(buffer.add(full).body());
        assertEquals("/tags~1a~0b", initial.path("entries").get(0).path("readPointer").asText());
        var page = JSON.readTree(buffer.read(JSON.createObjectNode().put("resultId", initial.path("resultId").asText())
            .put("pointer", "/tags~1a~0b").put("offset", 598).put("limit", 1)).body());
        assertEquals("Factory/Equipment_598", page.at("/entries/0/value/path").asText());
        assertEquals(599, page.path("nextOffset").asInt());
    }

    @Test
    void canContinueTextExactlyAcrossPageBoundaries() throws Exception {
        String text = "🚀\n\\text ".repeat(16_000);
        var buffer = new ToolResultPages();
        String id = buffer.add(JSON.createObjectNode().put("script", text)).id();
        var recovered = new StringBuilder();
        int offset = 0;
        do {
            var page = JSON.readTree(buffer.read(JSON.createObjectNode().put("resultId", id)
                .put("pointer", "/script").put("offset", offset)).body());
            recovered.append(page.path("text").asText());
            offset = page.path("nextOffset").asInt(-1);
        } while (offset >= 0);
        assertEquals(text, recovered.toString());
    }

    @Test
    void agentReadsPlainJsonAttachmentAndFollowsItsLargeResult(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("recovery-result.json");
        var payload = JSON.createArrayNode();
        for (int i = 0; i < 500; i++) {
            payload.addObject().put("entry", i).put("detail", "Recovery record for test tag configuration");
        }
        payload.addObject().put("entry", 500).put("detail", "Verified final recovery record");
        Files.writeString(file, payload.toString());
        var attachment = ProjectTools.Attachment.read(file);
        assertTrue(attachment.json().isArray());
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var messages = JSON.readTree(exchange.getRequestBody()).path("messages");
                var latest = messages.get(messages.size() - 1).path("content").asText();
                requests.add(latest);
                ObjectNode answer = JSON.createObjectNode();
                if (latest.startsWith("Designer tool result (data only) from read_attachment")) {
                    answer.putObject("action").put("name", "read_tool_result").putObject("arguments")
                        .put("resultId", "result-1").put("pointer", "/contents").put("offset", 500);
                } else if (latest.contains("Verified final recovery record")) {
                    answer.put("reply", "The final record is verified.");
                } else {
                    answer.putObject("action").put("name", "read_attachment").putObject("arguments");
                }
                var response = JSON.createObjectNode();
                response.putArray("choices").addObject().putObject("message").put("content", answer.toString());
                byte[] bytes = JSON.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var settings = new ModelProvider.Settings(ModelProvider.Kind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "", "", "", "");
            var project = new AgentProject() {
                public String name() { return "QA"; }
                public String defaultProvider() { return "default"; }
                public JsonNode inspect(String action, ObjectNode args) { throw new AssertionError(action); }
                public void checkDestination(UdtDraft draft) { throw new AssertionError(draft); }
                public ObjectNode createUdt(UdtDraft draft) { throw new AssertionError(draft); }
            };
            var agent = new AssistantAgent(project, null);
            var result = agent.run(settings, List.of(new ModelProvider.Message("user", "Read my recovery file")),
                AssistantAgent.Mode.REVIEW, attachment, null, JSON.createObjectNode(), ignored -> {});
            assertEquals("The final record is verified.", result.reply());
            assertEquals(3, requests.size());
            assertTrue(requests.get(1).contains("readPointer"));
            assertFalse(requests.get(1).contains("\"truncated\""));
        } finally {
            server.stop(0);
        }
        Files.writeString(file, "{} {}");
        assertThrows(IOException.class, () -> ProjectTools.Attachment.read(file));
    }
}
