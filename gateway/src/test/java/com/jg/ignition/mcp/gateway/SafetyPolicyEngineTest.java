package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyPolicyEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesDefaultDryRunWhenCommitMissing() {
        McpServerConfigResource config = new McpServerConfigResource(
            true,
            "ignition-mcp",
            java.util.List.of(),
            java.util.List.of(),
            true,
            true,
            100,
            100,
            50,
            true,
            25,
            java.util.List.of("[default]Line1/*"),
            java.util.List.of("[default]MCP/*"),
            java.util.List.of("*"),
            "",
            5000
        );

        SafetyPolicyEngine engine = new SafetyPolicyEngine(() -> config);
        ObjectNode args = mapper.createObjectNode();

        assertTrue(engine.isDryRun(args));
    }

    @Test
    void commitTrueTurnsOffDryRun() {
        SafetyPolicyEngine engine = new SafetyPolicyEngine(() -> McpServerConfigResource.DEFAULT);
        ObjectNode args = mapper.createObjectNode().put("commit", true);
        assertFalse(engine.isDryRun(args));
    }

    @Test
    void enforcesTagAllowlistPatterns() {
        McpServerConfigResource config = new McpServerConfigResource(
            true,
            "ignition-mcp",
            java.util.List.of(),
            java.util.List.of(),
            true,
            true,
            100,
            100,
            50,
            true,
            25,
            java.util.List.of("[default]AreaA/*"),
            java.util.List.of("[default]MCP/*"),
            java.util.List.of("prov:*"),
            "",
            5000
        );
        SafetyPolicyEngine engine = new SafetyPolicyEngine(() -> config);

        assertTrue(engine.isTagReadAllowed("[default]AreaA/Line1/Speed"));
        assertFalse(engine.isTagReadAllowed("[default]AreaB/Line9/Speed"));
        assertTrue(engine.isTagWriteAllowed("[default]MCP/Setpoint"));
        assertFalse(engine.isTagWriteAllowed("[default]Other/Setpoint"));
        assertTrue(engine.isAlarmAckAllowed("prov:line1/source1"));
    }
}
