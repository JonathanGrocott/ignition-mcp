package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

class McpToolTest {
    @Test
    void nestedBatchCommitFlagsCannotTurnPreviewIntoAWrite() throws Exception {
        McpTool tool = tool("ignition.tags.definition.write");
        var input = (ObjectNode) JSON.readTree("""
            {"commit":true,"definitions":[{"path":"[default]A","commit":true},
            {"definitions":[{"path":"[default]B","commit":true}]}]}
            """);

        var dryRun = tool.writeArguments(input, false);
        assertFalse(dryRun.path("commit").asBoolean());
        assertFalse(dryRun.at("/definitions/0/commit").asBoolean());
        assertFalse(dryRun.at("/definitions/1/definitions/0/commit").asBoolean());
        assertTrue(input.at("/definitions/0/commit").asBoolean());

        var commit = tool.writeArguments(dryRun, true);
        assertTrue(commit.at("/definitions/0/commit").asBoolean());
        assertTrue(commit.at("/definitions/1/definitions/0/commit").asBoolean());
        assertEquals("[default]B", commit.at("/definitions/1/definitions/0/path").asText());
        assertTrue(tool.isDryRun(input, JSON.readTree("""
            {"results":[{"isError":false,"structuredContent":{"dryRun":true}},
            {"isError":false,"structuredContent":{"results":[{"isError":false,"structuredContent":{"dryRun":true}}]}}]}
            """)));
        assertFalse(tool.isDryRun(input, JSON.readTree("""
            {"results":[{"isError":false,"structuredContent":{"dryRun":true}},
            {"isError":false,"structuredContent":{"dryRun":true}}]}
            """)));
    }

    @Test
    void modelMayProposeOnlyOneKnownActionAndCanStillReturnALegacyView() throws Exception {
        var tool = tool("ignition.namedqueries.write");
        var result = AssistantPanel.parseReply("""
            {"reply":"Drafted a query","tool":{"name":"ignition.namedqueries.write",
            "arguments":{"path":"Assistant/Answer","query":"SELECT 42"}}}
            """, List.of(tool));
        assertEquals(tool, result.tool().tool());
        assertEquals("SELECT 42", result.tool().arguments().path("query").asText());

        assertThrows(IOException.class, () -> AssistantPanel.parseReply("""
            {"reply":"A draft","tool":{"name":"ignition.namedqueries.write","arguments":{}}}
            """, List.of()));
        assertEquals(McpTool.Mode.UNKNOWN, tool("ignition.future.write").mode());
        assertEquals(McpTool.Mode.DRY_RUN, tool("ignition.namedqueries.execute").mode());

        assertNotNull(AssistantPanel.parseReply("""
            {"reply":"Drafted","view":{"title":"Pump","subtitle":"",
            "sections":[{"heading":"Status","body":"Ready"}]}}
            """, List.of()).view());
    }

    private static McpTool tool(String name) {
        ObjectNode schema = JSON.createObjectNode();
        schema.putObject("properties").putObject("commit").put("type", "boolean");
        return new McpTool(name, "", schema);
    }
}
