package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class McpReview {
    private McpReview() {}

    static Review prepare(IgnitionMcpClient client, McpTool tool, ObjectNode args)
        throws IOException, InterruptedException {
        return switch (tool.mode()) {
            case READ -> read(client, tool, args);
            case DRY_RUN -> dryRun(client, tool, args);
            case STORED_PLAN -> {
                String requestedId = PerspectiveDraft.text(args, "planId", 128, false);
                String requestedHash = PerspectiveDraft.text(args, "planHash", 128, false);
                var reply = client.invoke(IgnitionMcpClient.STATUS, JSON.createObjectNode().put("planId", requestedId));
                if (reply.error()) {
                    yield new Review(reply, null);
                }
                if (!requestedId.equals(reply.data().path("planId").asText())
                    || !requestedHash.equals(reply.data().path("planHash").asText())) {
                    throw new IOException("The stored plan ID or hash did not match.");
                }
                yield new Review(reply, fromPlan(reply.data()));
            }
            case UNKNOWN -> throw new IOException("This Gateway tool has no recognized review contract.");
        };
    }

    private static Review read(IgnitionMcpClient client, McpTool tool, ObjectNode args)
        throws IOException, InterruptedException {
        var reply = client.invoke(tool.name(), args);
        if (reply.error() || !IgnitionMcpClient.PREVIEW.equals(tool.name())) {
            return new Review(reply, null);
        }
        if (!args.path("targetProject").asText().equals(reply.data().path("targetProject").asText())) {
            throw new IOException("The Gateway plan targeted a different project.");
        }
        return new Review(reply, fromPlan(reply.data()));
    }

    private static Review dryRun(IgnitionMcpClient client, McpTool tool, ObjectNode args)
        throws IOException, InterruptedException {
        var reply = client.invoke(tool.name(), tool.writeArguments(args, false));
        if (reply.error()) {
            return new Review(reply, null);
        }
        if (!tool.isDryRun(args, reply.data())) {
            throw new IOException("The Gateway did not confirm a dry-run. Inspect the target before retrying.");
        }
        Commit commit = new Commit(tool.name(), tool.writeArguments(args, true), Long.MAX_VALUE);
        return new Review(reply, commit);
    }

    private static Commit fromPlan(JsonNode plan) throws IOException {
        String id = PerspectiveDraft.text(plan, "planId", 128, false);
        String hash = PerspectiveDraft.text(plan, "planHash", 128, false);
        long expiry = plan.path("expiresAt").asLong();
        if (!plan.path("state").asText().equals("pending")
            || !plan.path("writesEnabledAtPreview").asBoolean() || expiry <= Instant.now().toEpochMilli()) {
            return null;
        }
        return new Commit(IgnitionMcpClient.APPLY,
            JSON.createObjectNode().put("planId", id).put("planHash", hash).put("commit", true), expiry);
    }

    record Review(IgnitionMcpClient.ToolReply reply, Commit commit) {}

    record Commit(String name, ObjectNode arguments, long expiresAt) {
        boolean expired() { return expiresAt <= Instant.now().toEpochMilli(); }

        IgnitionMcpClient.ToolReply apply(IgnitionMcpClient client) throws IOException, InterruptedException {
            if (expired()) {
                throw new IOException("The stored plan expired. Preview again.");
            }
            return client.invoke(name, arguments);
        }
    }
}
