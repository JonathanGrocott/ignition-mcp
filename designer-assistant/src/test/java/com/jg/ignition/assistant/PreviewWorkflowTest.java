package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

class PreviewWorkflowTest {
    @Test
    void previewCreatesOnlyAPlanAndApplySendsExactlyTheReviewedPlan() throws Exception {
        List<String> requests = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data/ignition-mcp/mcp", exchange -> {
            try {
                assertEquals("test-only", exchange.getRequestHeaders().getFirst("X-Ignition-API-Token"));
                if (exchange.getRequestMethod().equals("DELETE")) {
                    requests.add("DELETE");
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                requests.add(method);
                var params = request.path("params");
                if (method.equals("initialize")) {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
                    respond(exchange, request, JSON.createObjectNode());
                    return;
                }
                assertEquals("test-session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                if (method.equals("notifications/initialized")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                switch (params.path("name").asText()) {
                    case IgnitionMcpClient.PREVIEW -> {
                        var args = params.path("arguments");
                        var bundle = args.path("bundle");
                        assertEquals("Fixture", args.path("targetProject").asText());
                        assertEquals("ignition-mcp.project-resource-bundle.v1", bundle.path("format").asText());
                        var resources = bundle.path("resources");
                        assertEquals(1, resources.size());
                        var resource = resources.get(0);
                        assertEquals("create", resource.path("operation").asText());
                        JsonNode view = JSON.readTree(resource.path("data").path("view.json").path("value").asText());
                        assertEquals("ia.container.flex", view.path("root").path("type").asText());
                        assertEquals("Pump overview", view.path("root").path("children").path(0).path("props").path("text").asText());
                        assertFalse(view.toString().contains("propConfig"));
                        assertFalse(view.toString().contains("events"));
                        var plan = JSON.createObjectNode().put("targetProject", "Fixture")
                            .put("planId", "plan-1").put("planHash", "hash-1")
                            .put("expiresAt", Instant.now().plusSeconds(600).toEpochMilli())
                            .put("writesEnabledAtPreview", true);
                        plan.putArray("changes").addObject().put("action", "create")
                            .put("moduleId", "com.inductiveautomation.perspective")
                            .put("resourceType", "views").put("path", "Assistant/Pump");
                        respond(exchange, request, toolResult(plan));
                    }
                    case IgnitionMcpClient.APPLY -> {
                        assertEquals(JSON.readTree("""
                            {"planId":"plan-1","planHash":"hash-1","commit":true}
                            """), params.path("arguments"));
                        respond(exchange, request, toolResult(JSON.createObjectNode()
                            .put("state", "verified").put("committed", true).put("verified", true)));
                    }
                    default -> throw new AssertionError("Unexpected tool call");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/data/ignition-mcp/mcp");
            var client = new IgnitionMcpClient(endpoint, "test-only");
            var description = JSON.readTree("""
                {"title":"Pump overview","subtitle":"","sections":[{"heading":"Status","body":"Ready"}],
                 "root":{"type":"unexpected","events":{}}}
                """);
            assertThrows(IOException.class, () -> PerspectiveDraft.create("../Pump", description));
            var plan = client.preview("Fixture", PerspectiveDraft.create("Assistant/Pump", description));
            assertEquals(List.of("initialize", "notifications/initialized", "tools/call", "DELETE"), requests);
            assertFalse(plan.expired());

            var receipt = client.apply(plan);
            assertTrue(receipt.path("verified").asBoolean());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/call", "DELETE",
                "initialize", "notifications/initialized", "tools/call", "DELETE"), requests);
        } finally {
            server.stop(0);
        }
    }

    private static ObjectNode toolResult(JsonNode data) {
        return JSON.createObjectNode().put("isError", false).set("structuredContent", data);
    }

    private static void respond(HttpExchange exchange, JsonNode request, JsonNode result) throws IOException {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        byte[] bytes = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
