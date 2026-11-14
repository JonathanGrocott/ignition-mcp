package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class IgnitionMcpClient {
    static final String PREVIEW = "ignition.projects.changes.preview";
    static final String APPLY = "ignition.projects.changes.apply";
    static final String STATUS = "ignition.projects.changes.status";
    private static final String PROTOCOL = "2025-06-18";
    private final URI endpoint;
    private final String token;
    private String session;
    private int nextId;

    IgnitionMcpClient(URI endpoint, String token) throws IOException {
        if (token.isBlank()) {
            throw new IOException("Enter an Ignition MCP API token in Settings.");
        }
        this.endpoint = endpoint;
        this.token = token;
    }

    Plan preview(String project, PerspectiveDraft draft) throws IOException, InterruptedException {
        JsonNode result = call(PREVIEW, draft.previewArguments(project));
        draft.verifyPreview(project, result);
        Plan plan = new Plan(result.path("planId").asText(), result.path("planHash").asText(),
            result.path("expiresAt").asLong(), project, draft, result.path("writesEnabledAtPreview").asBoolean(), result);
        if (plan.id().isBlank() || plan.hash().isBlank() || plan.expired()) {
            throw new IOException("Gateway did not return a valid preview plan.");
        }
        return plan;
    }

    JsonNode apply(Plan plan) throws IOException, InterruptedException {
        if (plan.expired()) {
            throw new IOException("The preview expired. Preview the view again.");
        }
        return call(APPLY, JSON.createObjectNode().put("planId", plan.id())
            .put("planHash", plan.hash()).put("commit", true));
    }

    JsonNode status(Plan plan) throws IOException, InterruptedException {
        return call(STATUS, JSON.createObjectNode().put("planId", plan.id()));
    }

    private JsonNode call(String name, ObjectNode args) throws IOException, InterruptedException {
        try {
            return executeTool(name, args);
        } finally {
            closeSession();
        }
    }

    private JsonNode executeTool(String name, ObjectNode args) throws IOException, InterruptedException {
        initialize();
        ObjectNode params = JSON.createObjectNode().put("name", name);
        params.set("arguments", args);
        HttpJson.Response response = request("tools/call", params, false);
        JsonNode responseResult = result(response);
        JsonNode data = responseResult.path("structuredContent");
        if (responseResult.path("isError").asBoolean()) {
            String message = responseResult.path("content").path(0).path("text").asText("Gateway returned a tool error.");
            throw new IOException(message.substring(0, Math.min(400, message.length())));
        }
        if (!data.isObject()) {
            throw new IOException("Gateway did not return structured change data. Check Ignition MCP 0.5.0 or later.");
        }
        return data;
    }

    private void initialize() throws IOException, InterruptedException {
        if (session != null) {
            return;
        }
        ObjectNode params = JSON.createObjectNode().put("protocolVersion", PROTOCOL);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "Ignition Designer Assistant").put("version", "0.1.0");
        HttpJson.Response response = request("initialize", params, false);
        result(response);
        session = response.headers().firstValue("Mcp-Session-Id")
            .orElseThrow(() -> new IOException("Gateway did not open an MCP session."));
        HttpJson.Response notification = request("notifications/initialized", JSON.createObjectNode(), true);
        checkStatus(notification);
    }

    private void closeSession() {
        String id = session;
        session = null;
        if (id == null || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            HttpJson.send(HttpRequest.newBuilder(endpoint).DELETE()
                .header("X-Ignition-API-Token", token).header("Mcp-Session-Id", id)
                .header("MCP-Protocol-Version", PROTOCOL).build(), 3);
        } catch (IOException ignored) {
            // The server expires abandoned transport sessions.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpJson.Response request(String method, ObjectNode params, boolean notification)
        throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        if (!notification) {
            body.put("id", ++nextId);
        }
        body.set("params", params);
        HttpRequest.Builder request = HttpJson.post(endpoint, body, 45)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL)
            .header("X-Ignition-API-Token", token);
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return HttpJson.send(request.build(), 45);
    }

    private JsonNode result(HttpJson.Response response) throws IOException {
        checkStatus(response);
        JsonNode envelope = response.json();
        if (envelope == null || envelope.path("id").asInt(-1) != nextId) {
            throw new IOException("Unexpected MCP response ID.");
        }
        if (envelope.hasNonNull("error")) {
            throw new IOException(envelope.path("error").path("message").asText("Gateway returned an MCP error."));
        }
        return envelope.path("result");
    }

    private static void checkStatus(HttpJson.Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Ignition MCP returned HTTP " + response.status() + ". Check the endpoint and token.");
        }
    }

    record Plan(String id, String hash, long expiresAt, String project, PerspectiveDraft draft,
                boolean writesEnabled, JsonNode response) {
        boolean expired() { return expiresAt <= Instant.now().toEpochMilli(); }
    }
}
